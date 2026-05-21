# Insights Pre-Computation Optimization

> **CCE Compliance Service** — Pre-computed metrics for the Insights Service  
> **Status**: Proposed | **Target**: v1.2.0  
> **Last Updated**: 2025-05-28  
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

### 1.1 Design Constraint: No Core Table Denormalization

> **Customer directive:** Core operational tables must NOT be modified for insights purposes (e.g., no adding `facility_id` to `protocol_instance`, no adding `practitioner_ref` to `compliance_event_log`). Instead, all insights data is served from **separate pre-computed tables** that are updated incrementally in real-time by service code.
>
> These pre-computed tables are **temporary** — they will be replaced by a dedicated data pipeline in a future release. The tables must be fully self-contained and droppable without affecting core application functionality.

### 1.2 Insights Service Query Breakdown

| Category | Queries | Primary Tables | Bottleneck |
|----------|---------|----------------|------------|
| Event volume & processing | 13 | `compliance_event_log` | Full-table GROUP BY on millions of rows |
| Ingestion analytics | 18 | `inbound_event` | Anti-join pipeline loss, self-join overlap detection |
| Step analytics & compliance | 5 | `step_instance`, `protocol_instance` | PERCENTILE_CONT, conditional aggregates |
| Deviation analytics | 7 | `deviation`, `step_instance`, `protocol_instance` | Multi-table JOINs, HAVING clauses |
| Facility-scoped queries | 5+ | `protocol_instance ↔ compliance_event_log` | JOIN to resolve facility_id |
| Intelligence delivery analytics | — | `intelligence_delivery`, `destination_adaptor_mapping`, `receiver_adaptor` | Not yet implemented; will require 3-table JOINs |

### 1.3 Cross-Service Optimization Plan

Optimizations are distributed across all four upstream services. Each service owns its own `docs/insights-optimization.md`:

| Service | New Pre-Computed Tables | Core Table Changes | Doc |
|---------|------------------------|-------------------|-----|
| **Compliance** (this doc) | `compliance_summary`, `step_completion_stats`, `deviation_summary`, `practitioner_activity_daily` | Rename `event_log` → `compliance_event_log`, dead column removal (cleanup only) | `cce-compliance-service/docs/insights-optimization.md` |
| **Collector** | `ingestion_summary_daily`, `event_volume_daily`, `pipeline_loss_daily` | None | `cce-collector-service/docs/insights-optimization.md` |
| **Scheduler** | `transition_log`, `step_state_snapshot` | None | `cce-scheduler-service/docs/insights-optimization.md` |
| **Intelligence** | `delivery_summary_daily`, `adaptor_health_snapshot` | None | `cce-intelligence-service/docs/insights-optimization.md` |

---

## 2. Optimizations Owned by Compliance Service

### 2.1 New Table: `compliance_summary`

**Problem:** The Insights Service computes per-protocol-instance compliance rates on every request by counting step states across `step_instance` rows and joining with `protocol_instance`. Facility-scoped queries require an additional JOIN to `compliance_event_log` to resolve `facility_id`.

**Solution:** Maintain a pre-computed summary row per `protocol_instance`, updated incrementally when steps are completed or state transitions occur. This table captures `facility_id` from the CloudEvent at enrollment time, eliminating the need to modify `protocol_instance`.

**Schema:**

```sql
CREATE TABLE compliance_summary (
    protocol_instance_id UUID PRIMARY KEY REFERENCES protocol_instance(id),
    patient_id           VARCHAR(100) NOT NULL,
    facility_id          VARCHAR(100),           -- captured from CloudEvent facilityid at enrollment
    protocol_definition_id UUID NOT NULL,
    total_steps          INTEGER NOT NULL DEFAULT 0,
    completed_steps      INTEGER NOT NULL DEFAULT 0,
    overdue_steps        INTEGER NOT NULL DEFAULT 0,
    missed_steps         INTEGER NOT NULL DEFAULT 0,
    compliance_rate      NUMERIC(5,2),           -- completed/total * 100
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
| Patient enrolled (`ComplianceEngine.processMatch()`) | Insert row with `facility_id` from `event.getFacilityid()`, `patient_id`, `protocol_definition_id` |
| Step created (`StepInstanceService.createStep()`) | `total_steps += 1` |
| Step completed (`StepInstanceService.completeStep()`) | `completed_steps += 1`, recalculate `compliance_rate` |
| Step → OVERDUE (scheduler trigger consumed) | `overdue_steps += 1` |
| Step → MISSED (scheduler trigger consumed) | `missed_steps += 1` |

**Insights Service query replacement:**

| Current Query | Replacement |
|---------------|-------------|
| Per-patient compliance rate (COUNT + GROUP BY on step_instance) | `SELECT * FROM compliance_summary WHERE protocol_definition_id = ?` |
| Facility compliance summary (3-table JOIN) | `SELECT facility_id, AVG(compliance_rate), COUNT(*) FROM compliance_summary WHERE facility_id = ? GROUP BY facility_id` |
| Facility ranking by compliance rate | `SELECT facility_id, AVG(compliance_rate) FROM compliance_summary GROUP BY facility_id ORDER BY AVG(compliance_rate)` |
| Active patients by facility | `SELECT patient_id FROM compliance_summary WHERE facility_id = ? AND compliance_rate < 100` |

---

### 2.2 New Table: `step_completion_stats`

**Problem:** The Insights Service computes per-action timeliness distribution (EARLY/ON_TIME/LATE counts), average days to complete, and median days to complete using `PERCENTILE_CONT(0.5)` — an expensive aggregate that requires sorting all matching rows.

**Solution:** Maintain running statistics per `(protocol_definition_id, action_id)`, updated incrementally on each step completion. The `protocol_definition_id` is resolved by JOINing through `protocol_instance` at update time (single FK hop), avoiding the need to add it to `step_instance`.

**Schema:**

```sql
CREATE TABLE step_completion_stats (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    protocol_definition_id UUID NOT NULL,
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
1. Resolve `protocolDefinitionId` via `step.getProtocolInstance().getProtocolDefinitionId()`
2. Compute `daysToComplete = EXTRACT(EPOCH FROM (completedAt - dueDate)) / 86400`
3. Increment `total_completed` and the appropriate timeliness bucket (`early_count`, `on_time_count`, or `late_count`)
4. Add `daysToComplete` to `total_days_to_complete` (running sum for AVG calculation)
5. Update `min_days_to_complete` and `max_days_to_complete`

**On `StepInstanceService.createStep()`:** Increment `total_reached`.

**Insights Service query replacement:**

| Current Query | Replacement |
|---------------|-------------|
| `PERCENTILE_CONT(0.5)` + `AVG(days)` + `COUNT(CASE completion_status)` per action | `SELECT * FROM step_completion_stats WHERE protocol_definition_id = ?` |
| Completion funnel (reached vs completed per action) | `SELECT action_id, total_reached, total_completed FROM step_completion_stats WHERE protocol_definition_id = ?` |

> **Note:** Median is approximated by `total_days_to_complete / total_completed` (mean). True median requires storing all values or using a streaming algorithm (e.g., t-digest). For dashboard purposes, mean is acceptable.

---

### 2.3 New Table: `deviation_summary`

**Problem:** Deviation analytics queries require JOINs across `deviation`, `step_instance`, and `protocol_instance`, with `GROUP BY` on `deviation_type` and `HAVING` clauses for repeat-deviation patients. Facility scoping adds yet another JOIN.

**Solution:** Maintain per-protocol-instance deviation counts, updated when deviations are created or resolved. Captures `facility_id` from `compliance_summary` (or the original CloudEvent) at creation time.

**Schema:**

```sql
CREATE TABLE deviation_summary (
    protocol_instance_id UUID PRIMARY KEY REFERENCES protocol_instance(id),
    patient_id           VARCHAR(100) NOT NULL,
    facility_id          VARCHAR(100),           -- captured from compliance_summary at creation
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

### 2.4 New Table: `practitioner_activity_daily`

**Problem:** The Insights Service extracts practitioner references from `compliance_event_log.data` JSONB using a 5-path `COALESCE` across Encounter, Observation, Condition, MedicationRequest, and Procedure FHIR resources. This is evaluated for every row on each query (40+ lines of SQL).

**Solution:** Maintain a daily pre-aggregated table of practitioner activity, populated at event processing time when the FHIR resource is parsed. This avoids modifying `compliance_event_log` while providing fast practitioner analytics.

**Schema:**

```sql
CREATE TABLE practitioner_activity_daily (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    summary_date         DATE NOT NULL,
    practitioner_ref     VARCHAR(200) NOT NULL,
    practitioner_display VARCHAR(200),
    facility_id          VARCHAR(100),
    resource_type        VARCHAR(100),
    event_count          BIGINT NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (summary_date, practitioner_ref, facility_id, resource_type)
);

CREATE INDEX idx_practitioner_daily_date ON practitioner_activity_daily (summary_date);
CREATE INDEX idx_practitioner_daily_ref ON practitioner_activity_daily (practitioner_ref);
CREATE INDEX idx_practitioner_daily_facility ON practitioner_activity_daily (facility_id);
```

**Update trigger:** In `ComplianceEngine`, after extracting the FHIR resource type, also extract the practitioner reference using the 5-path COALESCE logic. If a practitioner is found, upsert the corresponding summary row:

```sql
INSERT INTO practitioner_activity_daily (summary_date, practitioner_ref, practitioner_display, facility_id, resource_type, event_count)
VALUES (:date, :practitionerRef, :practitionerDisplay, :facilityId, :resourceType, 1)
ON CONFLICT (summary_date, practitioner_ref, facility_id, resource_type)
DO UPDATE SET
    event_count = practitioner_activity_daily.event_count + 1,
    updated_at = now();
```

**Insights Service query replacement:**

| Current Query | Replacement |
|---------------|-------------|
| 5-path JSONB COALESCE + GROUP BY + HAVING (40 lines) | `SELECT practitioner_ref, practitioner_display, SUM(event_count) FROM practitioner_activity_daily GROUP BY practitioner_ref, practitioner_display` |
| Events per practitioner per facility | `SELECT practitioner_ref, facility_id, SUM(event_count) FROM practitioner_activity_daily WHERE facility_id = ? GROUP BY 1, 2` |
| Active practitioners in date range | `SELECT DISTINCT practitioner_ref FROM practitioner_activity_daily WHERE summary_date BETWEEN ? AND ?` |

---

### 2.5 Dead Column Removal

**Problem:** Three columns were present in the original schema but never populated anywhere in the codebase:

| Table | Column | Type | Finding |
|-------|--------|------|--------|
| `compliance_event_log` | `matched_step_instance_id` | `UUID` | Never set — `setMatchedStepInstanceId()` has zero call sites |
| `audit_log` | `ip_address` | `VARCHAR(45)` | Never set — `setIpAddress()` has zero call sites |
| `intelligence_event_log` | `error_message` | `TEXT` | Never set — `setErrorMessage()` has zero call sites |

**Solution (fresh deploy):** These columns are **not included** in the initial DDL. The corresponding fields, getters, and setters are removed from the JPA entities.

---

### 2.6 Table Naming: `compliance_event_log`

**Rationale:** The original table name `event_log` is generic and ambiguous in a shared database with tables from multiple CCE services (`inbound_event` from Collector, `intelligence_event_log` from Compliance/Intelligence). The fresh deployment uses `compliance_event_log` to clearly indicate Compliance Service ownership, aligning with `intelligence_event_log` naming conventions.

**Code changes:**

- `EventLog.java`: `@Table(name = "compliance_event_log")`

---

## 3. Consistency Guarantees

All pre-computed tables are updated **synchronously within the same transaction** as the source operation (step creation, step completion, deviation creation, event processing). This guarantees:

- **No eventual consistency lag** — summary tables are always consistent with source tables
- **No separate batch job required** — updates are incremental, not full recomputation
- **Crash safety** — if the transaction rolls back, both the source change and the summary update roll back together
- **Clean removal** — when the data pipeline replaces these tables, they can be dropped and the corresponding service code removed without affecting core operations

### 3.1 No Backfill Required

Since this is a fresh deployment with no existing data, all optimizations are included in the initial schema from day 1. Summary tables (`compliance_summary`, `step_completion_stats`, `deviation_summary`, `practitioner_activity_daily`) populate organically as events flow through the system. No backfill migrations are needed.

---

## 4. Summary of Changes

| Change | Type | Affected Entity | Trigger |
|--------|------|-----------------|---------|
| New `compliance_summary` table | Table | New entity | Enrollment, step create/complete, scheduler transitions |
| New `step_completion_stats` table | Table | New entity | Step create/complete |
| New `deviation_summary` table | Table | New entity | Deviation create, step complete |
| New `practitioner_activity_daily` table | Table | New entity | Event processing (practitioner extraction) |
| Omit `matched_step_instance_id` from `compliance_event_log` | Not created | `EventLog` | — |
| Omit `ip_address` from `audit_log` | Not created | `AuditLog` | — |
| Omit `error_message` from `intelligence_event_log` | Not created | `IntelligenceEventLog` | — |
| Table named `compliance_event_log` (not `event_log`) | Naming | `EventLog` | — |

> **Note:** Core operational tables (`protocol_instance`, `step_instance`, `compliance_event_log`) retain their original schemas unchanged. All insights data is served from the pre-computed tables above.

### 4.1 Estimated Query Reduction for Insights Service

| Insights Query Category | Current Complexity | After Optimization |
|------------------------|-------------------|-------------------|
| Facility compliance (5 queries) | 3-table JOIN | Direct `WHERE facility_id = ?` on `compliance_summary` |
| Compliance rate | COUNT + GROUP BY on step_instance | Single row read from `compliance_summary` |
| Step analytics (timeliness, funnel) | PERCENTILE_CONT + conditional aggregates | Single row read from `step_completion_stats` |
| Deviation analytics (7 queries) | 3-table JOIN + HAVING | Single read from `deviation_summary` |
| Practitioner lookup | 5-path JSONB COALESCE | `GROUP BY` on `practitioner_activity_daily` |
| Facility ranking | 3-table JOIN + sort | `AVG(compliance_rate)` on `compliance_summary` |

### 4.2 Flyway Migration Plan (Fresh Deployment)

Since all services are deployed fresh, the optimized schema is defined in the initial Flyway migrations. No ALTER TABLE or backfill migrations are needed.

| Order | Migration | Description |
|-------|-----------|-------------|
| V1 | `V1__initial_schema.sql` | All core tables with original schema (no insights columns added). `compliance_event_log` naming, dead columns excluded. |
| V2 | `V2__create_compliance_summary.sql` | Pre-computed compliance summary table |
| V3 | `V3__create_step_completion_stats.sql` | Pre-computed step completion statistics table |
| V4 | `V4__create_deviation_summary.sql` | Pre-computed deviation summary table |
| V5 | `V5__create_practitioner_activity_daily.sql` | Pre-computed practitioner activity table |

---

## 5. Future: Data Pipeline Replacement

All pre-computed tables defined in this document are **temporary**. They will be replaced by a dedicated data pipeline (e.g., CDC-based streaming to a data warehouse) in a future release. When the data pipeline is implemented:

1. Drop the pre-computed tables (`compliance_summary`, `step_completion_stats`, `deviation_summary`, `practitioner_activity_daily`)
2. Remove the corresponding repository, entity, and service code that maintains them
3. Core operational tables remain completely unchanged — no rollback needed
4. The Insights Service switches from querying pre-computed tables to querying the data warehouse

This separation of concerns ensures that insights optimization never creates technical debt in the core application layer.

