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
