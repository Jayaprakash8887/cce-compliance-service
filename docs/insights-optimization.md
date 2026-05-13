# Insights Pre-Computation Optimization

> **CCE Compliance Service** — Pre-computed metrics for the Insights Service  
> **Status**: Proposed | **Target**: v1.2.0  
> **Last Updated**: 2026-05-11

---

## 1. Problem Statement

The CCE Insights Service (read-only analytics) executes 43 SQL queries against tables owned by the Compliance, Collector, and Scheduler services. Several of these queries involve:

- **Expensive JOINs** — `facility_id` exists only on `event_log`, forcing 5+ queries to JOIN `protocol_instance → event_log` just to filter/group by facility
- **Full-table aggregations** — `GROUP BY` on millions of `event_log` and `step_instance` rows for dashboard metrics that change infrequently
- **Complex JSONB extraction** — practitioner references extracted via 5-path `COALESCE` from `event_log.data` on every query
- **Percentile calculations** — `PERCENTILE_CONT(0.5)` on `step_instance` for median completion times (requires full sort)
- **Anti-joins** — pipeline loss detection uses `NOT EXISTS` subquery correlating `inbound_event` with `event_log` (O(n²) worst-case)

At production scale (millions of events, hundreds of thousands of steps), these queries become the primary bottleneck for dashboard responsiveness, even with the Insights Service's 3-tier Caffeine cache.

### 1.1 Insights Service Query Breakdown

| Category | Queries | Primary Tables | Bottleneck |
|----------|---------|----------------|------------|
| Event volume & processing | 13 | `event_log` | Full-table GROUP BY on millions of rows |
| Ingestion analytics | 18 | `inbound_event` | Anti-join pipeline loss, self-join overlap detection |
| Step analytics & compliance | 5 | `step_instance`, `protocol_instance` | PERCENTILE_CONT, conditional aggregates |
| Deviation analytics | 7 | `deviation`, `step_instance`, `protocol_instance` | Multi-table JOINs, HAVING clauses |
| Facility-scoped queries | 5+ | `protocol_instance ↔ event_log` | JOIN to resolve facility_id |
| Intelligence delivery analytics | — | `intelligence_delivery`, `destination_adaptor_mapping`, `receiver_adaptor` | Not yet implemented; will require 3-table JOINs |

### 1.2 Cross-Service Optimization Plan

Optimizations are distributed across all four upstream services. Each service owns its own `docs/insights-optimization.md`:

| Service | New Tables | Column Changes | Doc |
|---------|------------|----------------|-----|
| **Compliance** (this doc) | `compliance_summary`, `step_completion_stats`, `deviation_summary` | `facility_id` on `protocol_instance`, `practitioner_ref`/`practitioner_display` on `event_log` | `cce-compliance-service/docs/insights-optimization.md` |
| **Collector** | `ingestion_summary_daily`, `event_volume_daily` | `matched` on `inbound_event` | `cce-collector-service/docs/insights-optimization.md` |
| **Scheduler** | `transition_log`, `step_state_snapshot` | — | `cce-scheduler-service/docs/insights-optimization.md` |
| **Intelligence** | `delivery_summary_daily`, `adaptor_health_snapshot` | `facility_id` on `intelligence_delivery` | `cce-intelligence-service/docs/insights-optimization.md` |

---

## 2. Optimizations Owned by Compliance Service

### 2.1 Denormalize `facility_id` onto `protocol_instance`

**Problem:** `facility_id` is only stored on `event_log`. The Insights Service must JOIN `protocol_instance` with `event_log` in 5+ queries just to group or filter by facility (facility compliance summary, facility ranking, active patients by facility, facility event counts, facility-patient mapping).

**Solution:** Add `facility_id VARCHAR(100)` column to `protocol_instance`. Populate at enrollment time from the inbound CloudEvent's `facilityid` extension attribute.

**Schema change:**

```sql
-- Flyway migration
ALTER TABLE protocol_instance ADD COLUMN facility_id VARCHAR(100);
CREATE INDEX idx_protocol_instance_facility ON protocol_instance (facility_id);

-- Backfill from event_log (one-time)
UPDATE protocol_instance pi
SET facility_id = (
    SELECT el.facility_id
    FROM event_log el
    WHERE el.protocol_instance_id = pi.id
      AND el.facility_id IS NOT NULL
    ORDER BY el.received_at ASC
    LIMIT 1
);
```

**Code change:** In `ComplianceEngine.processMatch()` and `processExplicitMatch()`, set `protocolInstance.setFacilityId(event.getFacilityid())` at enrollment time.

**Impact on Insights Service:**  
- Eliminates JOIN to `event_log` in facility compliance, facility ranking, active patients, and facility event count queries
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

### 2.5 Denormalize `practitioner_ref` on `event_log`

**Problem:** The Insights Service extracts practitioner references from `event_log.data` JSONB using a 5-path `COALESCE` across Encounter, Observation, Condition, MedicationRequest, and Procedure FHIR resources. This is evaluated for every row on each query.

**Solution:** Extract and store `practitioner_ref` and `practitioner_display` as materialized columns on `event_log` at event processing time.

**Schema change:**

```sql
ALTER TABLE event_log ADD COLUMN practitioner_ref VARCHAR(200);
ALTER TABLE event_log ADD COLUMN practitioner_display VARCHAR(200);
CREATE INDEX idx_event_log_practitioner ON event_log (practitioner_ref) WHERE practitioner_ref IS NOT NULL;
```

**Code change:** In `ComplianceEngine`, after extracting the FHIR resource type, also extract the practitioner reference using the same 5-path COALESCE logic and set it on the `EventLog` entity before persisting.

**Insights Service query replacement:**

| Current Query | Replacement |
|---------------|-------------|
| 5-path JSONB COALESCE + GROUP BY + HAVING (40 lines) | `SELECT practitioner_ref, practitioner_display, ... FROM event_log WHERE practitioner_ref IS NOT NULL GROUP BY ...` |

---

### 2.6 Dead Column Removal

**Problem:** Three columns across three entities are declared in the JPA model but never populated anywhere in the codebase:

| Table | Column | Type | Finding |
|-------|--------|------|--------|
| `event_log` | `matched_step_instance_id` | `UUID` | Never set — `setMatchedStepInstanceId()` has zero call sites |
| `audit_log` | `ip_address` | `VARCHAR(45)` | Never set — `setIpAddress()` has zero call sites |
| `intelligence_event_log` | `error_message` | `TEXT` | Never set — `setErrorMessage()` has zero call sites |

**Solution:** Remove columns from the database and corresponding fields from the JPA entities.

**Schema changes:**

```sql
ALTER TABLE event_log DROP COLUMN IF EXISTS matched_step_instance_id;
ALTER TABLE audit_log DROP COLUMN IF EXISTS ip_address;
ALTER TABLE intelligence_event_log DROP COLUMN IF EXISTS error_message;
```

**Code changes:** Remove the fields, getters, setters, and any DTO mapping references for all three columns.

**Risk:** None — the columns are never read or written. Removal is a pure cleanup.

---

### 2.7 event_log Normalization — step_instance FK & Write-Only Column Removal

**Problem:** After matching, `ComplianceEngine` sets three columns on `event_log` — `protocol_instance_id`, `protocol_definition_id`, and `action_id` — but these are **write-only**: they are persisted but never read back by any repository query or business logic. They exist solely for traceability, but the same information is fully derivable through the matched step instance:

```
event_log → step_instance → protocol_instance → protocol_definition_id
                          → action_id
```

Meanwhile, there is no FK from `event_log` to `step_instance`, making it impossible to efficiently navigate from an event to the step it matched.

**Solution (Phase 1):**

1. **Add `step_instance_id` FK** to `event_log` — a proper foreign key to the matched step, set during `completeStep()` in `ComplianceEngine`
2. **Drop 3 write-only columns** — `protocol_instance_id`, `protocol_definition_id`, `action_id` from `event_log` (derivable via `step_instance_id → step_instance → protocol_instance`)
3. **Add reverse-lookup index** on `step_instance` for event matching

**Schema changes:**

```sql
-- Add step_instance_id FK (nullable — not all events match a step)
ALTER TABLE event_log ADD COLUMN step_instance_id UUID REFERENCES step_instance(id);
CREATE INDEX idx_event_log_step_instance ON event_log (step_instance_id) WHERE step_instance_id IS NOT NULL;

-- Backfill from existing data (protocol_instance_id + action_id → step_instance)
UPDATE event_log el
SET step_instance_id = si.id
FROM step_instance si
WHERE si.protocol_instance_id = el.protocol_instance_id
  AND si.action_id = el.action_id
  AND si.state = 'COMPLETED'
  AND el.processing_status = 'MATCHED'
  AND el.step_instance_id IS NULL;

-- Drop write-only columns after backfill
ALTER TABLE event_log DROP COLUMN protocol_instance_id;
ALTER TABLE event_log DROP COLUMN protocol_definition_id;
ALTER TABLE event_log DROP COLUMN action_id;
```

**Code changes:**

- `EventLog.java`: Add `stepInstanceId` field (UUID, nullable). Remove `protocolInstanceId`, `protocolDefinitionId`, `actionId` fields.
- `ComplianceEngine.processMatch()`: Replace `eventLog.setProtocolInstanceId()` / `.setProtocolDefinitionId()` / `.setActionId()` with `eventLog.setStepInstanceId(step.getId())`
- `DtoMapper`: Update `EventLogDto` mapping to derive `protocolInstanceId`, `protocolDefinitionId`, `actionId` from `stepInstance` relationship (lazy load or JOIN fetch)
- `PatientTrackingController`: No change — DTO still exposes same fields

**Insights Service impact:** Queries that currently JOIN `event_log.protocol_instance_id` to `protocol_instance` can instead JOIN `event_log.step_instance_id` to `step_instance` (which has its own `protocol_instance_id`). Net effect: same or fewer JOINs.

---

### 2.8 step_instance Enhancement — Add protocol_definition_id

**Problem:** Many Insights queries need `protocol_definition_id` alongside step-level data, but `step_instance` only has `protocol_instance_id`. This forces a JOIN through `protocol_instance` just to get the protocol definition:

```sql
SELECT si.*, pi.protocol_definition_id
FROM step_instance si
JOIN protocol_instance pi ON pi.id = si.protocol_instance_id
WHERE pi.protocol_definition_id = ?
```

**Solution:** Denormalize `protocol_definition_id` onto `step_instance`. This is a stable FK that never changes after enrollment, so there is no update anomaly risk.

**Schema changes:**

```sql
ALTER TABLE step_instance ADD COLUMN protocol_definition_id UUID;
CREATE INDEX idx_step_instance_protocol_def ON step_instance (protocol_definition_id);

-- Backfill from protocol_instance
UPDATE step_instance si
SET protocol_definition_id = pi.protocol_definition_id
FROM protocol_instance pi
WHERE pi.id = si.protocol_instance_id;

ALTER TABLE step_instance ALTER COLUMN protocol_definition_id SET NOT NULL;
```

**Code changes:**

- `StepInstance.java`: Add `protocolDefinitionId` field (UUID, NOT NULL)
- `StepInstanceService.createStep()`: Set `protocolDefinitionId` from the `ProtocolInstance` during step creation

**Insights Service impact:** Eliminates the `protocol_instance` JOIN for 12+ queries that only need `protocol_definition_id`. Direct filter:

```sql
SELECT * FROM step_instance WHERE protocol_definition_id = ?
```

---

### 2.9 event_log Normalization Phase 2 — inbound_event FK (Deferred)

**Problem:** 6 of the 16 columns on `event_log` duplicate data already present in the Collector Service's `inbound_event` table:

| event_log column | inbound_event column | Duplicated? |
|-----------------|---------------------|-------------|
| `cloudeventsid` | `cloudevents_id` | Yes — identical CloudEvents ID |
| `source` | `source` | Yes |
| `source_event_id` | `source_event_id` | Yes |
| `subject` | `subject` | Yes |
| `type` | `type` | Yes |
| `event_time` | `event_time` | Yes |

Additionally, `event_log.data` (JSONB, ~2–5 KB per row) stores the extracted FHIR resource, while `inbound_event.raw_payload` stores the full CloudEvent envelope (which *contains* the same FHIR resource in `data`). After §2.5 materializes `practitioner_ref` and `practitioner_display`, and `resource_type` is derivable from step matching, zero Insights queries would need to touch `event_log.data`.

**Solution:** Add an `inbound_event_id` FK to `event_log`, enabling the 6 duplicated columns and `data` JSONB to be dropped. Queries needing envelope metadata JOIN through the FK.

**Prerequisite:** The Collector Service must publish the `inbound_event.id` (UUID) as a CloudEvents extension attribute in the Kafka message so the Compliance Service can capture it. See **Collector Service §2.4**.

**Schema changes (deferred until Collector publishes ID):**

```sql
-- Add FK to inbound_event
ALTER TABLE event_log ADD COLUMN inbound_event_id UUID;
CREATE INDEX idx_event_log_inbound_event ON event_log (inbound_event_id) WHERE inbound_event_id IS NOT NULL;

-- Backfill from matching cloudeventsid + source
UPDATE event_log el
SET inbound_event_id = ie.id
FROM inbound_event ie
WHERE ie.cloudevents_id = el.cloudeventsid
  AND ie.source = el.source
  AND el.inbound_event_id IS NULL;

-- Phase 2b: drop duplicated columns (only after all queries are migrated)
ALTER TABLE event_log DROP COLUMN source_event_id;
ALTER TABLE event_log DROP COLUMN subject;
ALTER TABLE event_log DROP COLUMN type;
ALTER TABLE event_log DROP COLUMN event_time;
ALTER TABLE event_log DROP COLUMN data;
-- Keep cloudeventsid + source for idempotency check (existsByCloudeventsIdAndSource)
```

> **Note:** `cloudeventsid` and `source` are retained because they form the idempotency unique constraint checked on every inbound event. Dropping them would require changing the idempotency check to use `inbound_event_id` instead, which has a circular dependency (the compliance service needs to check idempotency *before* it knows the `inbound_event_id`).

**Phasing:**

| Phase | Action | Depends On |
|-------|--------|------------|
| Phase 2a | Add `inbound_event_id` column + backfill | Collector publishes `inboundeventid` extension (§2.4) |
| Phase 2b | Drop `source_event_id`, `subject`, `type`, `event_time` | Insights queries migrated to JOIN `inbound_event` |
| Phase 2c | Drop `data` JSONB | §2.5 `practitioner_ref`/`practitioner_display` materialized + Insights queries verified |

---

## 3. Consistency Guarantees

All pre-computed tables are updated **synchronously within the same transaction** as the source operation (step creation, step completion, deviation creation). This guarantees:

- **No eventual consistency lag** — summary tables are always consistent with source tables
- **No separate batch job required** — updates are incremental, not full recomputation
- **Crash safety** — if the transaction rolls back, both the source change and the summary update roll back together

### 3.1 Backfill Strategy

For existing data, a one-time Flyway migration populates the summary tables from source tables:

```sql
-- compliance_summary backfill
INSERT INTO compliance_summary (protocol_instance_id, patient_id, facility_id, protocol_definition_id, total_steps, completed_steps, overdue_steps, missed_steps, compliance_rate, updated_at)
SELECT
    pi.id,
    pi.patient_id,
    pi.facility_id,
    pi.protocol_definition_id,
    COUNT(si.id),
    COUNT(si.id) FILTER (WHERE si.state = 'COMPLETED'),
    COUNT(si.id) FILTER (WHERE si.state = 'OVERDUE'),
    COUNT(si.id) FILTER (WHERE si.state = 'MISSED'),
    CASE WHEN COUNT(si.id) > 0
         THEN ROUND(COUNT(si.id) FILTER (WHERE si.state = 'COMPLETED') * 100.0 / COUNT(si.id), 2)
         ELSE 0 END,
    now()
FROM protocol_instance pi
LEFT JOIN step_instance si ON si.protocol_instance_id = pi.id
GROUP BY pi.id, pi.patient_id, pi.facility_id, pi.protocol_definition_id;
```

---

## 4. Summary of Changes

| Change | Type | Affected Entity | Trigger |
|--------|------|-----------------|---------|
| Add `facility_id` to `protocol_instance` | Column | `ProtocolInstance` | Enrollment |
| Add `practitioner_ref`, `practitioner_display` to `event_log` | Column | `EventLog` | Event processing |
| New `compliance_summary` table | Table | New entity | Step create/complete, scheduler transitions |
| New `step_completion_stats` table | Table | New entity | Step create/complete |
| New `deviation_summary` table | Table | New entity | Deviation create, step complete |
| Drop `matched_step_instance_id` from `event_log` | Drop column | `EventLog` | — |
| Drop `ip_address` from `audit_log` | Drop column | `AuditLog` | — |
| Drop `error_message` from `intelligence_event_log` | Drop column | `IntelligenceEventLog` | — |
| Add `step_instance_id` FK to `event_log` | Column + FK | `EventLog` | Step completion |
| Drop `protocol_instance_id`, `protocol_definition_id`, `action_id` from `event_log` | Drop columns | `EventLog` | — (derivable via `step_instance_id`) |
| Add `protocol_definition_id` to `step_instance` | Column | `StepInstance` | Step creation |
| Add `inbound_event_id` FK to `event_log` (deferred) | Column + FK | `EventLog` | Event processing (requires Collector §2.4) |

### 4.1 Estimated Query Reduction for Insights Service

| Insights Query Category | Current Complexity | After Optimization |
|------------------------|-------------------|-------------------|
| Facility compliance (5 queries) | 3-table JOIN | Direct `WHERE facility_id = ?` |
| Compliance rate | COUNT + GROUP BY on step_instance | Single row read from `compliance_summary` |
| Step analytics (timeliness, funnel) | PERCENTILE_CONT + conditional aggregates | Single row read from `step_completion_stats` |
| Deviation analytics (7 queries) | 3-table JOIN + HAVING | Single read from `deviation_summary` |
| Practitioner lookup | 5-path JSONB COALESCE | Direct column read |
| Facility ranking | 3-table JOIN + sort | `AVG(compliance_rate)` on `compliance_summary` |

### 4.2 Flyway Migration Plan

| Order | Migration | Description |
|-------|-----------|-------------|
| V10 | `V10__add_facility_id_to_protocol_instance.sql` | Add column + index + backfill |
| V11 | `V11__add_practitioner_to_event_log.sql` | Add columns + partial index |
| V12 | `V12__create_compliance_summary.sql` | Create table + backfill from existing data |
| V13 | `V13__create_step_completion_stats.sql` | Create table + backfill |
| V14 | `V14__create_deviation_summary.sql` | Create table + backfill |
| V15 | `V15__drop_dead_columns.sql` | Drop `event_log.matched_step_instance_id`, `audit_log.ip_address`, `intelligence_event_log.error_message` |
| V16 | `V16__add_step_instance_fk_to_event_log.sql` | Add `step_instance_id` FK + partial index + backfill |
| V17 | `V17__drop_write_only_event_log_columns.sql` | Drop `protocol_instance_id`, `protocol_definition_id`, `action_id` from `event_log` |
| V18 | `V18__add_protocol_definition_id_to_step_instance.sql` | Add column + index + backfill + NOT NULL |
| V19 *(deferred)* | `V19__add_inbound_event_id_to_event_log.sql` | Add FK + partial index + backfill (requires Collector §2.4) |
| V20 *(deferred)* | `V20__drop_duplicated_event_log_columns.sql` | Drop `source_event_id`, `subject`, `type`, `event_time`, `data` |
