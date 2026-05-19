# Insights Pre-Computation Optimization

> **CCE Compliance Service** — Pre-computed metrics for the Insights Service  
> **Status**: Proposed | **Target**: v1.2.0  
> **Last Updated**: 2026-05-14  
> **Deployment Model**: Fresh deployment (no existing data to migrate)

---

## 1. Problem Statement

The CCE Insights Service (read-only analytics) executes 43 SQL queries against tables owned by the Compliance, Collector, and Scheduler services. Several of these queries involve:

- **Expensive JOINs** — `facility_id` exists only on `compliance_event_log`, forcing 5+ queries to JOIN `protocol_instance → compliance_event_log` just to filter/group by facility
- **Full-table aggregations** — `GROUP BY` on millions of `compliance_event_log` and `step_instance` rows for dashboard metrics that change infrequently
- **Complex JSONB extraction** — practitioner references extracted via 5-path `COALESCE` from `compliance_event_log.data` on every query
- **Percentile calculations** — `PERCENTILE_CONT(0.5)` on `step_instance` for median completion times (requires full sort)
- **Anti-joins** — pipeline loss detection uses `NOT EXISTS` subquery correlating `inbound_event` with `compliance_event_log` (O(n²) worst-case)

At production scale (millions of events, hundreds of thousands of steps), these queries become the primary bottleneck for dashboard responsiveness, even with the Insights Service's 3-tier Caffeine cache.

### 1.1 Insights Service Query Breakdown

| Category | Queries | Primary Tables | Bottleneck |
|----------|---------|----------------|------------|
| Event volume & processing | 13 | `compliance_event_log` | Full-table GROUP BY on millions of rows |
| Ingestion analytics | 18 | `inbound_event` | Anti-join pipeline loss, self-join overlap detection |
| Step analytics & compliance | 5 | `step_instance`, `protocol_instance` | PERCENTILE_CONT, conditional aggregates |
| Deviation analytics | 7 | `deviation`, `step_instance`, `protocol_instance` | Multi-table JOINs, HAVING clauses |
| Facility-scoped queries | 5+ | `protocol_instance ↔ compliance_event_log` | JOIN to resolve facility_id |
| Intelligence delivery analytics | — | `intelligence_delivery`, `destination_adaptor_mapping`, `receiver_adaptor` | Not yet implemented; will require 3-table JOINs |

### 1.2 Cross-Service Optimization Plan

Optimizations are distributed across all four upstream services. Each service owns its own `docs/insights-optimization.md`:

| Service | New Tables | Column Changes | Doc |
|---------|------------|----------------|-----|
| **Compliance** (this doc) | `compliance_summary`, `step_completion_stats`, `deviation_summary` | Rename `event_log` → `compliance_event_log`, `facility_id` on `protocol_instance`, `practitioner_ref`/`practitioner_display` on `compliance_event_log`, `step_instance_id` FK on `compliance_event_log`, `protocol_definition_id` on `step_instance`, dead column removal | `cce-compliance-service/docs/insights-optimization.md` |
| **Collector** | `ingestion_summary_daily`, `event_volume_daily` | `matched` on `inbound_event` | `cce-collector-service/docs/insights-optimization.md` |
| **Scheduler** | `transition_log`, `step_state_snapshot` | — | `cce-scheduler-service/docs/insights-optimization.md` |
| **Intelligence** | `delivery_summary_daily`, `adaptor_health_snapshot` | `facility_id` on `intelligence_delivery` | `cce-intelligence-service/docs/insights-optimization.md` |

---

## 2. Optimizations Owned by Compliance Service

### 2.1 Denormalize `facility_id` onto `protocol_instance`

**Problem:** `facility_id` is only stored on `compliance_event_log`. The Insights Service must JOIN `protocol_instance` with `compliance_event_log` in 5+ queries just to group or filter by facility (facility compliance summary, facility ranking, active patients by facility, facility event counts, facility-patient mapping).

**Solution:** Include `facility_id VARCHAR(100)` on `protocol_instance` from the initial schema. Populate at enrollment time from the inbound CloudEvent's `facilityid` extension attribute.

**Schema (included in initial `protocol_instance` DDL):**

```sql
-- Within CREATE TABLE protocol_instance:
    facility_id VARCHAR(100),
-- Index:
CREATE INDEX idx_protocol_instance_facility ON protocol_instance (facility_id);
```

**Code change:** In `ComplianceEngine.processMatch()` and `processExplicitMatch()`, set `protocolInstance.setFacilityId(event.getFacilityid())` at enrollment time.

**Impact on Insights Service:**  
- Eliminates JOIN to `compliance_event_log` in facility compliance, facility ranking, active patients, and facility event count queries
- Reduces query complexity from 3-table JOIN to direct `WHERE facility_id = ?`

---

### 2.2 New Table: `compliance_summary`

**Problem:** The Insights Service computes per-protocol-instance compliance rates on every request by counting step states across `step_instance` rows and joining with `protocol_instance`.

**Solution:** Maintain a pre-computed summary row per `protocol_instance`, updated incrementally when steps are completed or state transitions occur.

**Schema:**

```sql
CREATE TABLE compliance_summary (
    protocol_instance_id UUID PRIMARY KEY REFERENCES protocol_instance(id),
    patient_id           VARCHAR(100) NOT NULL,
    facility_id          VARCHAR(100),
    protocol_definition_id UUID NOT NULL,
    total_steps          INTEGER NOT NULL DEFAULT 0,
    completed_steps      INTEGER NOT NULL DEFAULT 0,
    overdue_steps        INTEGER NOT NULL DEFAULT 0,
    missed_steps         INTEGER NOT NULL DEFAULT 0,
    compliance_rate      NUMERIC(5,2),        -- completed/total * 100
    last_step_completed_at TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_compliance_summary_facility ON compliance_summary (facility_id);
CREATE INDEX idx_compliance_summary_protocol_def ON compliance_summary (protocol_definition_id);
CREATE INDEX idx_compliance_summary_patient ON compliance_summary (patient_id);
```

**Update triggers (within Compliance Service):**

| Event | Action |
|-------|--------|
| Step created (`StepInstanceService.createStep()`) | `total_steps += 1` |
| Step completed (`StepInstanceService.completeStep()`) | `completed_steps += 1`, recalculate `compliance_rate` |
| Step → OVERDUE (scheduler trigger consumed) | `overdue_steps += 1` |
| Step → MISSED (scheduler trigger consumed) | `missed_steps += 1` |

**Insights Service query replacement:**

| Current Query | Replacement |
|---------------|-------------|
| `SELECT pi.*, COUNT(si.*), COUNT(si.* WHERE state='COMPLETED') FROM protocol_instance pi JOIN step_instance si ...` | `SELECT * FROM compliance_summary WHERE protocol_definition_id = ?` |
| Facility compliance summary (3-table JOIN) | `SELECT facility_id, AVG(compliance_rate), COUNT(*) FROM compliance_summary WHERE facility_id = ? GROUP BY facility_id` |
| Facility ranking by compliance rate | `SELECT facility_id, AVG(compliance_rate) FROM compliance_summary GROUP BY facility_id ORDER BY AVG(compliance_rate)` |

---

### 2.3 New Table: `step_completion_stats`

**Problem:** The Insights Service computes per-action timeliness distribution (EARLY/ON_TIME/LATE counts), average days to complete, and median days to complete using `PERCENTILE_CONT(0.5)` — an expensive aggregate that requires sorting all matching rows.

**Solution:** Maintain running statistics per `(protocol_definition_id, action_id)`, updated incrementally on each step completion.

**Schema:**

```sql
CREATE TABLE step_completion_stats (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    protocol_definition_id UUID NOT NULL REFERENCES protocol_definition(id),
    action_id              VARCHAR(200) NOT NULL,
    total_reached          INTEGER NOT NULL DEFAULT 0,
    total_completed        INTEGER NOT NULL DEFAULT 0,
    early_count            INTEGER NOT NULL DEFAULT 0,
    on_time_count          INTEGER NOT NULL DEFAULT 0,
    late_count             INTEGER NOT NULL DEFAULT 0,
    total_days_to_complete NUMERIC(12,2) NOT NULL DEFAULT 0,  -- running sum for AVG
    min_days_to_complete   NUMERIC(8,2),
    max_days_to_complete   NUMERIC(8,2),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (protocol_definition_id, action_id)
);
```

**Update trigger:** On `StepInstanceService.completeStep()`:
1. Compute `daysToComplete = EXTRACT(EPOCH FROM (completedAt - dueDate)) / 86400`
2. Increment `total_completed` and the appropriate timeliness bucket (`early_count`, `on_time_count`, or `late_count`)
3. Add `daysToComplete` to `total_days_to_complete` (running sum for AVG calculation)
4. Update `min_days_to_complete` and `max_days_to_complete`

**On `StepInstanceService.createStep()`:** Increment `total_reached`.

**Insights Service query replacement:**

| Current Query | Replacement |
|---------------|-------------|
| `PERCENTILE_CONT(0.5)` + `AVG(days)` + `COUNT(CASE completion_status)` per action | `SELECT * FROM step_completion_stats WHERE protocol_definition_id = ?` |
| Completion funnel (reached vs completed per action) | `SELECT action_id, total_reached, total_completed FROM step_completion_stats WHERE protocol_definition_id = ?` |

> **Note:** Median is approximated by `total_days_to_complete / total_completed` (mean). True median requires storing all values or using a streaming algorithm (e.g., t-digest). For dashboard purposes, mean is acceptable. If exact median is required, consider storing a histogram bucket array in a JSONB column.

---

### 2.4 New Table: `deviation_summary`

**Problem:** Deviation analytics queries require JOINs across `deviation`, `step_instance`, and `protocol_instance`, with `GROUP BY` on `deviation_type` and `HAVING` clauses for repeat-deviation patients.

**Solution:** Maintain per-protocol-instance deviation counts, updated when deviations are created or resolved.

**Schema:**

```sql
CREATE TABLE deviation_summary (
    protocol_instance_id UUID PRIMARY KEY REFERENCES protocol_instance(id),
    patient_id           VARCHAR(100) NOT NULL,
    facility_id          VARCHAR(100),
    overdue_count        INTEGER NOT NULL DEFAULT 0,
    missed_count         INTEGER NOT NULL DEFAULT 0,
    order_violation_count INTEGER NOT NULL DEFAULT 0,
    total_deviations     INTEGER NOT NULL DEFAULT 0,
    resolved_count       INTEGER NOT NULL DEFAULT 0,   -- OVERDUE steps that reached COMPLETED
    first_deviation_at   TIMESTAMPTZ,
    last_deviation_at    TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deviation_summary_facility ON deviation_summary (facility_id);
CREATE INDEX idx_deviation_summary_patient ON deviation_summary (patient_id);
CREATE INDEX idx_deviation_summary_total ON deviation_summary (total_deviations);
```

**Update triggers:**

| Event | Action |
|-------|--------|
| Deviation created (`DeviationService.createDeviation()`) | Increment type-specific counter + `total_deviations`, update `last_deviation_at` |
| Overdue step completed | `resolved_count += 1` |

**Insights Service query replacement:**

| Current Query | Replacement |
|---------------|-------------|
| Deviation trends (3-table JOIN + GROUP BY type) | `SELECT * FROM deviation_summary WHERE facility_id = ?` |
| Resolution rate (FILTER-based aggregates) | `SELECT SUM(resolved_count)::float / NULLIF(SUM(overdue_count), 0) FROM deviation_summary` |
| Repeat deviation patients (GROUP BY HAVING) | `SELECT patient_id FROM deviation_summary WHERE total_deviations >= :minDeviations` |
| Deviation count by facility | `SELECT facility_id, SUM(total_deviations) FROM deviation_summary GROUP BY facility_id` |

---

### 2.5 Denormalize `practitioner_ref` on `compliance_event_log`

**Problem:** The Insights Service extracts practitioner references from `compliance_event_log.data` JSONB using a 5-path `COALESCE` across Encounter, Observation, Condition, MedicationRequest, and Procedure FHIR resources. This is evaluated for every row on each query.

**Solution:** Include `practitioner_ref` and `practitioner_display` as materialized columns on `compliance_event_log` from the initial schema. Populate at event processing time.

**Schema (included in initial `compliance_event_log` DDL):**

```sql
-- Within CREATE TABLE compliance_event_log:
    practitioner_ref     VARCHAR(200),
    practitioner_display VARCHAR(200),
-- Index:
CREATE INDEX idx_cel_practitioner ON compliance_event_log (practitioner_ref) WHERE practitioner_ref IS NOT NULL;
```

**Code change:** In `ComplianceEngine`, after extracting the FHIR resource type, also extract the practitioner reference using the same 5-path COALESCE logic and set it on the `EventLog` entity before persisting.

**Insights Service query replacement:**

| Current Query | Replacement |
|---------------|-------------|
| 5-path JSONB COALESCE + GROUP BY + HAVING (40 lines) | `SELECT practitioner_ref, practitioner_display, ... FROM compliance_event_log WHERE practitioner_ref IS NOT NULL GROUP BY ...` |

---

### 2.6 Dead Column Removal

**Problem:** Three columns were present in the original schema but never populated anywhere in the codebase:

| Table | Column | Type | Finding |
|-------|--------|------|--------|
| `compliance_event_log` | `matched_step_instance_id` | `UUID` | Never set — `setMatchedStepInstanceId()` has zero call sites |
| `audit_log` | `ip_address` | `VARCHAR(45)` | Never set — `setIpAddress()` has zero call sites |
| `intelligence_event_log` | `error_message` | `TEXT` | Never set — `setErrorMessage()` has zero call sites |

**Solution (fresh deploy):** These columns are **not included** in the initial DDL. The corresponding fields, getters, and setters are removed from the JPA entities.

---

### 2.7 compliance_event_log Normalization — step_instance FK & Write-Only Column Removal

**Problem:** The original schema had three columns on `compliance_event_log` — `protocol_instance_id`, `protocol_definition_id`, and `action_id` — that were **write-only**: persisted but never read back by any repository query or business logic. The same information is fully derivable through the matched step instance:

```
compliance_event_log → step_instance → protocol_instance → protocol_definition_id
                          → action_id
```

**Solution (fresh deploy):**

1. **Include `step_instance_id` FK** on `compliance_event_log` from day 1 — a proper foreign key to the matched step, set during `completeStep()` in `ComplianceEngine`
2. **Omit the 3 write-only columns** — `protocol_instance_id`, `protocol_definition_id`, `action_id` are not included in the initial DDL

**Schema (included in initial `compliance_event_log` DDL):**

```sql
-- Within CREATE TABLE compliance_event_log:
    step_instance_id UUID REFERENCES step_instance(id),
-- Index:
CREATE INDEX idx_cel_step_instance ON compliance_event_log (step_instance_id) WHERE step_instance_id IS NOT NULL;
```

**Code changes:**

- `EventLog.java`: Has `stepInstanceId` field (UUID, nullable). Does not have `protocolInstanceId`, `protocolDefinitionId`, or `actionId` fields.
- `ComplianceEngine.processMatch()`: Sets `eventLog.setStepInstanceId(step.getId())`
- `DtoMapper`: `EventLogDto` derives `protocolInstanceId`, `protocolDefinitionId`, `actionId` from `stepInstance` relationship (lazy load or JOIN fetch)
- `PatientTrackingController`: No change — DTO still exposes same fields

**Insights Service impact:** Queries JOIN `compliance_event_log.step_instance_id` to `step_instance` (which has `protocol_instance_id` and `protocol_definition_id`). Same or fewer JOINs than the original schema.

---

### 2.8 step_instance Enhancement — Add protocol_definition_id

**Problem:** Many Insights queries need `protocol_definition_id` alongside step-level data, but the original `step_instance` only had `protocol_instance_id`. This forces a JOIN through `protocol_instance` just to get the protocol definition.

**Solution (fresh deploy):** Include `protocol_definition_id` on `step_instance` from the initial schema. This is a stable FK that never changes after enrollment, so there is no update anomaly risk.

**Schema (included in initial `step_instance` DDL):**

```sql
-- Within CREATE TABLE step_instance:
    protocol_definition_id UUID NOT NULL,
-- Index:
CREATE INDEX idx_step_instance_protocol_def ON step_instance (protocol_definition_id);
```

**Code changes:**

- `StepInstance.java`: Has `protocolDefinitionId` field (UUID, NOT NULL)
- `StepInstanceService.createStep()`: Sets `protocolDefinitionId` from the `ProtocolInstance` during step creation

**Insights Service impact:** Eliminates the `protocol_instance` JOIN for 12+ queries that only need `protocol_definition_id`. Direct filter:

```sql
SELECT * FROM step_instance WHERE protocol_definition_id = ?
```

---

### 2.9 compliance_event_log Normalization Phase 2 — inbound_event FK

**Problem:** 6 columns on `compliance_event_log` duplicate data already present in the Collector Service's `inbound_event` table:

| compliance_event_log column | inbound_event column | Duplicated? |
|-----------------|---------------------|-------------|
| `cloudeventsid` | `cloudevents_id` | Yes — identical CloudEvents ID |
| `source` | `source` | Yes |
| `source_event_id` | `source_event_id` | Yes |
| `subject` | `subject` | Yes |
| `type` | `type` | Yes |
| `event_time` | `event_time` | Yes |

Additionally, `compliance_event_log.data` (JSONB, ~2–5 KB per row) stores the extracted FHIR resource, while `inbound_event.raw_payload` stores the full CloudEvent envelope (which *contains* the same FHIR resource in `data`). After §2.5 materializes `practitioner_ref` and `practitioner_display`, and `resource_type` is derivable from step matching, zero Insights queries would need to touch `compliance_event_log.data`.

**Solution (fresh deploy):** Include `inbound_event_id` FK on `compliance_event_log` from the initial schema. The Collector Service publishes `inboundeventid` as a CloudEvents extension attribute (see **Collector Service §2.4**), which the Compliance Service captures at event processing time.

**Schema (included in initial `compliance_event_log` DDL):**

```sql
-- Within CREATE TABLE compliance_event_log:
    inbound_event_id UUID,
-- Index:
CREATE INDEX idx_cel_inbound_event ON compliance_event_log (inbound_event_id) WHERE inbound_event_id IS NOT NULL;
```

The duplicated columns (`source_event_id`, `subject`, `type`, `event_time`, `data`) are **not included** in the initial DDL. Queries needing envelope metadata JOIN through the FK to `inbound_event`.

> **Note:** `cloudeventsid` and `source` are retained because they form the idempotency unique constraint checked on every inbound event. Dropping them would require changing the idempotency check to use `inbound_event_id` instead, which has a circular dependency (the compliance service needs to check idempotency *before* it knows the `inbound_event_id`).

**Phasing:**

| Phase | Action | Status |
|-------|--------|--------|
| Phase 1 | Include `inbound_event_id` column in initial DDL | Included from day 1 |
| Phase 2 | Omit `source_event_id`, `subject`, `type`, `event_time`, `data` from initial DDL | Included from day 1 |
| Prerequisite | Collector publishes `inboundeventid` extension (§2.4) | Must be deployed first |

---

### 2.10 Table Naming: `compliance_event_log`

**Rationale:** The original table name `event_log` is generic and ambiguous in a shared database with tables from multiple CCE services (`inbound_event` from Collector, `intelligence_event_log` from Compliance/Intelligence). The fresh deployment uses `compliance_event_log` to clearly indicate Compliance Service ownership, aligning with `intelligence_event_log` naming conventions.

**Code changes:**

- `EventLog.java`: `@Table(name = "compliance_event_log")`

---

## 3. Consistency Guarantees

All pre-computed tables are updated **synchronously within the same transaction** as the source operation (step creation, step completion, deviation creation). This guarantees:

- **No eventual consistency lag** — summary tables are always consistent with source tables
- **No separate batch job required** — updates are incremental, not full recomputation
- **Crash safety** — if the transaction rolls back, both the source change and the summary update roll back together

### 3.1 No Backfill Required

Since this is a fresh deployment with no existing data, all optimizations are included in the initial schema from day 1. Summary tables (`compliance_summary`, `step_completion_stats`, `deviation_summary`) populate organically as events flow through the system. No backfill migrations are needed.

---

## 4. Summary of Changes

| Change | Type | Affected Entity | Trigger |
|--------|------|-----------------|---------|
| Add `facility_id` to `protocol_instance` | Column | `ProtocolInstance` | Enrollment |
| Add `practitioner_ref`, `practitioner_display` to `compliance_event_log` | Column | `EventLog` | Event processing |
| New `compliance_summary` table | Table | New entity | Step create/complete, scheduler transitions |
| New `step_completion_stats` table | Table | New entity | Step create/complete |
| New `deviation_summary` table | Table | New entity | Deviation create, step complete |
| Omit `matched_step_instance_id` from `compliance_event_log` | Not created | `EventLog` | — |
| Omit `ip_address` from `audit_log` | Not created | `AuditLog` | — |
| Omit `error_message` from `intelligence_event_log` | Not created | `IntelligenceEventLog` | — |
| Include `step_instance_id` FK on `compliance_event_log` | Column + FK | `EventLog` | Step completion |
| Omit `protocol_instance_id`, `protocol_definition_id`, `action_id` from `compliance_event_log` | Not created | `EventLog` | — (derivable via `step_instance_id`) |
| Include `protocol_definition_id` on `step_instance` | Column | `StepInstance` | Step creation |
| Include `inbound_event_id` FK on `compliance_event_log` | Column + FK | `EventLog` | Event processing (requires Collector §2.4) |
| Omit `source_event_id`, `subject`, `type`, `event_time`, `data` from `compliance_event_log` | Not created | `EventLog` | — (available via `inbound_event_id` FK) |
| Table named `compliance_event_log` (not `event_log`) | Naming | `EventLog` | — |

### 4.1 Estimated Query Reduction for Insights Service

| Insights Query Category | Current Complexity | After Optimization |
|------------------------|-------------------|-------------------|
| Facility compliance (5 queries) | 3-table JOIN | Direct `WHERE facility_id = ?` |
| Compliance rate | COUNT + GROUP BY on step_instance | Single row read from `compliance_summary` |
| Step analytics (timeliness, funnel) | PERCENTILE_CONT + conditional aggregates | Single row read from `step_completion_stats` |
| Deviation analytics (7 queries) | 3-table JOIN + HAVING | Single read from `deviation_summary` |
| Practitioner lookup | 5-path JSONB COALESCE | Direct column read |
| Facility ranking | 3-table JOIN + sort | `AVG(compliance_rate)` on `compliance_summary` |

### 4.2 Flyway Migration Plan (Fresh Deployment)

Since all services are deployed fresh, the optimized schema is defined in the initial Flyway migrations. No ALTER TABLE or backfill migrations are needed.

| Order | Migration | Description |
|-------|-----------|-------------|
| V1 | `V1__initial_schema.sql` | All core tables with optimized columns from day 1: `compliance_event_log` (with `step_instance_id`, `inbound_event_id`, `practitioner_ref`, `practitioner_display`; without dead/write-only/duplicated columns), `protocol_instance` (with `facility_id`), `step_instance` (with `protocol_definition_id`), `audit_log` (without `ip_address`), `intelligence_event_log` (without `error_message`) |
| V2 | `V2__create_compliance_summary.sql` | Pre-computed compliance summary table |
| V3 | `V3__create_step_completion_stats.sql` | Pre-computed step completion statistics table |
| V4 | `V4__create_deviation_summary.sql` | Pre-computed deviation summary table |

