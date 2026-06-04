# Core Schema Optimization

> **CCE Compliance Service** — Schema cleanup for fresh deployment  
> **Status**: Proposed | **Target**: v2.0.0  
> **Last Updated**: 2026-05-27  
> **Deployment Model**: Fresh deployment (no existing data to migrate)

---

## 1. Overview

This document covers core schema normalization and cleanup decisions for the Compliance Service's fresh deployment. 

---

## 2. Table Naming: `compliance_event_log`

**Rationale:** The original table name `event_log` is generic and ambiguous in a shared database with tables from multiple CCE services (`inbound_event` from Collector, `intelligence_event_log` from Compliance/Intelligence). The fresh deployment uses `compliance_event_log` to clearly indicate Compliance Service ownership, aligning with `intelligence_event_log` naming conventions.

**Code changes:**

- `EventLog.java`: `@Table(name = "compliance_event_log")`

---

## 3. Dead Column Removal

**Problem:** Three columns were present in the original schema but never populated anywhere in the codebase:

| Table | Column | Type | Finding |
|-------|--------|------|--------|
| `compliance_event_log` | `matched_step_instance_id` | `UUID` | Never set — `setMatchedStepInstanceId()` has zero call sites |
| `audit_log` | `ip_address` | `VARCHAR(45)` | Never set — `setIpAddress()` has zero call sites |
| `intelligence_event_log` | `error_message` | `TEXT` | Never set — `setErrorMessage()` has zero call sites |

**Solution (fresh deploy):** These columns are **not included** in the initial DDL. The corresponding fields, getters, and setters are removed from the JPA entities.

**Impact:** No application behavior changes. These columns were never read or written by any business logic.

---

## 4. compliance_event_log Normalization — Remove unused Columns

**Problem:** 6 columns on `compliance_event_log` are not used for any functionality — insights and filtering are served by the data pipeline.

| compliance_event_log column | Status |
|----------------------------|--------|
| `source_event_id` | Remove — never read by any query or business logic |
| `subject` | Remove — only read by `findBySubject()` for the removed Events API |
| `type` | Remove — only read by `DtoMapper` for the removed Events API |
| `event_time` | Remove — only read by `DtoMapper` for the removed Events API |
| `correlation_id` | Remove — only used for MDC logging (available on the Kafka message at processing time, not needed after) |
| `facility_id` | Remove — only used for facility-scoped queries in removed APIs; available on Kafka message and in data pipeline |

**Solution (fresh deploy):**

1. Remove 6 columns

**Resulting `compliance_event_log` schema:**

```sql
CREATE TABLE compliance_event_log (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cloudevents_id      VARCHAR NOT NULL,
    source              VARCHAR NOT NULL,
    processing_status   VARCHAR NOT NULL,
    data                JSONB,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT compliance_event_log_cloudevents_id_source_key UNIQUE (cloudevents_id, source),
    CONSTRAINT compliance_event_log_processing_status_check CHECK (processing_status IN ('MATCHED', 'ZERO_MATCH', 'DUPLICATE'))
);
```



---

## 5. compliance_event_log Normalization — Remove Step Columns + FK on step_instance

**Problem:** The original schema had three columns on `compliance_event_log` — `protocol_instance_id`, `protocol_definition_id`, and `action_id` — that were **write-only**: persisted at event processing time but never read back by any repository query or business logic. The same information is derivable from the step that was completed by the event.

**Solution (fresh deploy):**

1. **Remove** `protocol_instance_id`, `protocol_definition_id`, and `action_id` from `compliance_event_log`
2. **Add** `completed_by_event_id UUID REFERENCES compliance_event_log(id)` on `step_instance` — records which event completed the step

This inverts the relationship: instead of the event log knowing which step it matched, the step knows which event completed it. This is semantically correct — a step has exactly one completing event (or none if still pending), while an event can complete multiple steps (multiple trigger matches).

**Schema changes:**

```sql
-- Within CREATE TABLE step_instance:
    completed_by_event_id UUID REFERENCES compliance_event_log(id),
-- Index:
CREATE INDEX idx_step_instance_completed_event ON step_instance (completed_by_event_id)
    WHERE completed_by_event_id IS NOT NULL;
```

**Columns NOT included in `compliance_event_log` DDL:**
- `protocol_instance_id` — derivable via `step_instance WHERE completed_by_event_id = ?`
- `protocol_definition_id` — derivable via `step_instance → protocol_instance`
- `action_id` — derivable via `step_instance.action_id`

**Code changes:**

- `StepInstance.java`: Add `completedByEventId` field (UUID, nullable)
- `EventLog.java`: Remove `protocolInstanceId`, `protocolDefinitionId`, `actionId` fields
- `StepInstanceService.completeStep()`: Set `step.setCompletedByEventId(eventLog.getId())`
- `DtoMapper`: `EventLogDto` — if step context is needed in the API response, resolve via `step_instance WHERE completed_by_event_id = eventLog.id` (or eager-fetch in query)

**Relationship diagram:**

```
compliance_event_log ←── step_instance
       (1)          completed_by_event_id FK   (n)
```

**Benefits:**
- `compliance_event_log` becomes a lean audit record (id, cloudeventsid, source, processing_status, data, received_at)
- Step-event linkage is on the correct side of the relationship (the step knows its completing event)
- No orphan FK issues — events that don't match any step simply have no step referencing them

---

## 6. Flyway Migration

These changes are incorporated into the initial schema migration:

| Migration | Change |
|-----------|--------|
| `V1__initial_schema.sql` | Table named `compliance_event_log` (not `event_log`); dead columns excluded; redundant envelope columns excluded; step-related columns excluded; `step_instance.completed_by_event_id` FK included |

---

## 7. Summary

| Change | Type | Affected Entity | Rationale |
|--------|------|-----------------|-----------|
| Table named `compliance_event_log` (not `event_log`) | Naming | `EventLog` | Disambiguate in shared DB |
| Omit `matched_step_instance_id` from `compliance_event_log` | Not created | `EventLog` | Zero call sites — dead code |
| Omit `ip_address` from `audit_log` | Not created | `AuditLog` | Zero call sites — dead code |
| Omit `error_message` from `intelligence_event_log` | Not created | `IntelligenceEventLog` | Zero call sites — dead code |
| Omit `source_event_id`, `subject`, `type`, `event_time`, `correlation_id`, `facility_id` from `compliance_event_log` | Not created | `EventLog` | Events API removed — data served by data pipeline |
| Remove `GET /v1/patients/{patientId}/events` endpoint | API removal | `PatientTrackingController` | Event query responsibility moved to data pipeline |
| Add `completed_by_event_id` FK on `step_instance` | Column + FK | `StepInstance` | Track completing event on the correct side of relationship |
| Omit `protocol_instance_id`, `protocol_definition_id`, `action_id` from `compliance_event_log` | Not created | `EventLog` | Derivable via `step_instance.completed_by_event_id` |
