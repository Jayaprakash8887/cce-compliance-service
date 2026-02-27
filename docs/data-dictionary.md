# Data Dictionary

> **CCE Compliance Service** — Complete database schema reference  
> **Database**: PostgreSQL 16 | **Schema**: `public` | **Migration**: Flyway  
> **Last Updated**: 2026-02-25

---

## Table of Contents

1. [Overview](#1-overview)
2. [plan_definition](#2-plan_definition)
3. [protocol_instance](#3-protocol_instance)
4. [step_instance](#4-step_instance)
5. [deviation](#5-deviation)
6. [trigger_index](#6-trigger_index)
7. [event_log](#7-event_log)
8. [audit_log](#8-audit_log)
9. [Enumerated Value Reference](#9-enumerated-value-reference)
10. [Relationships & Foreign Keys](#10-relationships--foreign-keys)
11. [Indexes](#11-indexes)
12. [Partitioning Strategy](#12-partitioning-strategy)
13. [JSONB Column Schemas](#13-jsonb-column-schemas)

---

## 1. Overview

The CCE Compliance Service database consists of **7 tables** that support the full compliance engine lifecycle — from protocol definition loading and trigger indexing, through patient enrollment and step tracking, to event logging, deviation detection, and operational audit trails.

### Entity Relationship Summary

```
plan_definition ──1:N──▶ protocol_instance ──1:N──▶ step_instance
       │                        │                        │
       │                        └───1:N──▶ deviation ◀──N:1┘
       │
       └──1:N──▶ trigger_index

event_log          (standalone, references by UUID but no FK constraint)
audit_log          (standalone, immutable audit trail)
```

### Table Summary

| # | Table | Purpose | Row Growth | Partitioned |
|---|-------|---------|-----------|-------------|
| 1 | `plan_definition` | Stores FHIR R4 PlanDefinition resources (protocol templates) | Low (tens) | No |
| 2 | `protocol_instance` | Patient enrollments in specific protocols | Medium (per-patient) | No |
| 3 | `step_instance` | Individual action steps within a patient's protocol journey | Medium–High | No |
| 4 | `deviation` | Compliance deviations (overdue, missed, ambiguous) | Medium | No |
| 5 | `trigger_index` | Inverted index for fast Tier 1 structural event matching | Low (rebuilt on protocol load) | No |
| 6 | `event_log` | Immutable log of all inbound CloudEvents and their processing outcomes | High (every event) | **Yes** (monthly by `received_at`) |
| 7 | `audit_log` | System and user audit trail | Medium–High | No |

---

## 2. plan_definition

### Purpose

Stores FHIR R4 **PlanDefinition** resources that define compliance protocols. Each row represents a versioned protocol template containing actions, triggers, conditions, timing constraints, and related action dependencies. The full PlanDefinition JSON is stored in a JSONB column to preserve the complete FHIR resource while allowing PostgreSQL JSON queries.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | **Primary key.** Auto-generated unique identifier for this protocol definition. |
| `url` | `VARCHAR` | **NOT NULL** | — | **FHIR canonical URL.** Globally unique identifier for the protocol (e.g., `http://openphc.org/fhir/PlanDefinition/anc-high-risk`). Combined with `version` forms the canonical reference. |
| `version` | `VARCHAR` | **NOT NULL** | — | **Semantic version.** Version string of the protocol definition (e.g., `2.1`). Allows multiple versions of the same protocol URL to coexist. |
| `status` | `VARCHAR` | **NOT NULL** | — | **Lifecycle status.** Current status of this definition. Only `ACTIVE` definitions participate in trigger matching. See [Enumerated Values: PlanDefinitionStatus](#plandefinitionstatus). |
| `definition` | `JSONB` | **NOT NULL** | — | **Full FHIR PlanDefinition JSON.** The complete R4 PlanDefinition resource stored as JSONB. Contains `action[]` with triggers, conditions, timing, and related actions. Indexed with GIN for JSON path queries. See [JSONB: definition](#plan_definition-definition). |
| `loaded_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | **Load timestamp.** When this protocol definition was loaded into the system. |

### Constraints

| Type | Name | Details |
|------|------|---------|
| Primary Key | `plan_definition_pkey` | `id` |
| Unique | `plan_definition_url_version_key` | `(url, version)` — Prevents duplicate protocol versions. |
| Check | — | `status IN ('ACTIVE', 'RETIRED')` |

### Indexes

| Name | Columns | Type | Purpose |
|------|---------|------|---------|
| `idx_plan_definition_triggers` | `definition` | GIN (`jsonb_path_ops`) | Enables fast JSON path queries against the PlanDefinition body (e.g., searching for specific action IDs or trigger types). |

### Canonical Reference

The **canonical reference** is the combination `url|version` (e.g., `http://openphc.org/fhir/PlanDefinition/anc-high-risk|2.1`). This is computed by the JPA entity method `getCanonical()` and is stored in `protocol_instance.protocol_canonical` for denormalized lookups.

---

## 3. protocol_instance

### Purpose

Represents a **patient's enrollment** in a specific compliance protocol. Created when the Compliance Engine processes an inbound event that matches a protocol's enrollment trigger. Each patient can have at most one `ACTIVE` instance per protocol definition. Tracks the full lifecycle from enrollment through completion or withdrawal.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | **Primary key.** Unique identifier for this protocol enrollment instance. |
| `patient_id` | `VARCHAR` | **NOT NULL** | — | **Patient identifier.** The UPID (Unique Patient Identifier) of the enrolled patient (e.g., `260115-0001-7823`). Derived from the CloudEvent `subject` field. |
| `protocol_canonical` | `VARCHAR` | **NOT NULL** | — | **Denormalized canonical reference.** The `url|version` of the enrolled protocol (e.g., `http://openphc.org/fhir/PlanDefinition/anc-high-risk|2.1`). Stored for fast display without joining `plan_definition`. |
| `plan_definition_id` | `UUID` | **NOT NULL** | — | **Foreign key → `plan_definition.id`.** Links this enrollment to the exact protocol definition version. |
| `enrolled_at` | `TIMESTAMPTZ` | **NOT NULL** | — | **Enrollment timestamp.** When the patient was enrolled in this protocol. Used as the anchor for timing calculations (e.g., "8 weeks post-enrollment"). |
| `status` | `VARCHAR` | **NOT NULL** | — | **Instance lifecycle status.** See [Enumerated Values: ProtocolInstanceStatus](#protocolinstancestatus). |
| `created_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | **Record creation timestamp.** Immutable once set. |
| `updated_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | **Last modification timestamp.** Updated on every state change. |

### Constraints

| Type | Name | Details |
|------|------|---------|
| Primary Key | `protocol_instance_pkey` | `id` |
| Foreign Key | `protocol_instance_plan_definition_id_fkey` | `plan_definition_id` → `plan_definition(id)` |
| Check | — | `status IN ('ACTIVE', 'COMPLETED', 'WITHDRAWN', 'EXPIRED')` |

### Indexes

| Name | Columns | Type | Purpose |
|------|---------|------|---------|
| `idx_protocol_instance_patient` | `patient_id` | B-tree | Fast lookup of all protocol enrollments for a patient (used by patient-centric tracking APIs). |
| `idx_protocol_instance_status` | `status` | Partial B-tree (`WHERE status = 'ACTIVE'`) | Optimizes queries for active enrollments, which are the most frequently accessed. |

---

## 4. step_instance

### Purpose

Tracks an **individual action occurrence** within a patient's protocol journey. Each step corresponds to a single `action` defined in the PlanDefinition (e.g., "ANC Visit 1", "BCG Vaccination", "Dispense Antimalarial"). Steps follow a state machine lifecycle: `PENDING → DUE → OVERDUE → MISSED` (scheduler-driven) or `→ COMPLETED` (event-driven) or `→ SKIPPED` (manual). Repeating steps (e.g., monthly checkups) are differentiated by `repeat_index`.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | **Primary key.** Unique identifier for this step occurrence. |
| `protocol_instance_id` | `UUID` | **NOT NULL** | — | **Foreign key → `protocol_instance.id`.** The patient's protocol enrollment this step belongs to. |
| `action_id` | `VARCHAR` | **NOT NULL** | — | **PlanDefinition action ID.** References the `action.id` from the PlanDefinition that this step instantiates (e.g., `anc-visit-1`, `bcg-vaccination`, `dispense-medication`). |
| `repeat_index` | `INTEGER` | **NOT NULL** | `0` | **Repeat occurrence counter.** Zero-based index for repeating actions. For example, the `monthly-checkup` action with `count: 6` will have steps with `repeat_index` 0–5. Non-repeating actions always have index 0. |
| `state` | `VARCHAR` | **NOT NULL** | — | **Current step state.** The lifecycle state of this step instance. See [Enumerated Values: StepState](#stepstate). |
| `due_date` | `TIMESTAMPTZ` | Yes | — | **Scheduled due date.** When this step is expected to be completed.  Calculated from the protocol's `relatedAction.offsetDuration` relative to the enrollment date. `NULL` if the step has no scheduled timing (e.g., event-triggered steps). |
| `overdue_date` | `TIMESTAMPTZ` | Yes | — | **Overdue threshold date.** The date after which the step is considered overdue. Typically `due_date + tolerance_days`. Used by the Scheduler Service to trigger `DUE → OVERDUE` transition. |
| `missed_date` | `TIMESTAMPTZ` | Yes | — | **Missed cutoff date.** The date after which the step is considered missed. Used by the Scheduler Service to trigger `OVERDUE → MISSED` transition. |
| `completed_at` | `TIMESTAMPTZ` | Yes | — | **Completion timestamp.** When the step was marked as completed by an inbound event match. `NULL` for non-completed steps. |
| `completed_by_source` | `VARCHAR` | Yes | — | **Completing event source.** The `source` field from the CloudEvent that completed this step (e.g., `rhie-mediator`, `smartcare-emr`). Enables provenance tracking. |
| `completion_status` | `VARCHAR` | Yes | — | **Timeliness classification.** Whether the step was completed early, on time, or late relative to the `due_date`. See [Enumerated Values: CompletionStatus](#completionstatus). `NULL` for uncompleted steps. |
| `matched_event_id` | `UUID` | Yes | — | **Matching event log reference.** Links to the `event_log.id` of the CloudEvent that completed this step. No FK constraint (event_log is partitioned). |
| `created_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | **Record creation timestamp.** Immutable once set. |
| `updated_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | **Last modification timestamp.** Updated on every state transition. |

### Constraints

| Type | Name | Details |
|------|------|---------|
| Primary Key | `step_instance_pkey` | `id` |
| Foreign Key | `step_instance_protocol_instance_id_fkey` | `protocol_instance_id` → `protocol_instance(id)` |
| Check | — | `state IN ('PENDING', 'DUE', 'OVERDUE', 'MISSED', 'COMPLETED', 'SKIPPED')` |
| Check | — | `completion_status IN ('ON_TIME', 'EARLY', 'LATE')` |

### Indexes

| Name | Columns | Type | Purpose |
|------|---------|------|---------|
| `idx_step_instance_protocol` | `protocol_instance_id` | B-tree | Fast lookup of all steps within a protocol instance. |
| `idx_step_instance_state` | `state` | Partial B-tree (`WHERE state IN ('PENDING', 'DUE', 'OVERDUE')`) | Optimizes scheduler queries finding active (non-terminal) steps. |
| `idx_step_instance_due_date` | `due_date` | Partial B-tree (`WHERE state IN ('PENDING', 'DUE', 'OVERDUE')`) | Optimizes Scheduler Service queries for time-based transitions. |

### State Machine

```
                    ┌──────────┐
         ┌─────────│ PENDING  │─────────┐
         │         └────┬─────┘         │
         │   scheduler  │               │ event match
         │   (due_date  │               │ (completes)
         │    reached)  ▼               ▼
         │         ┌──────────┐    ┌───────────┐
         │         │   DUE    │───▶│ COMPLETED │
         │         └────┬─────┘    └───────────┘
         │   tolerance  │               ▲
         │   window     │               │ event match
         │   expired    ▼               │
         │         ┌──────────┐         │
         │         │ OVERDUE  │─────────┘
         │         └────┬─────┘
         │   missed     │
         │   cutoff     ▼
         │         ┌──────────┐
         │         │  MISSED  │
         │         └──────────┘
         │
         │  manual skip
         ▼
    ┌──────────┐
    │ SKIPPED  │
    └──────────┘
```

---

## 5. deviation

### Purpose

Records **compliance deviations** detected during protocol execution. A deviation is created when a step transitions to `OVERDUE` or `MISSED`, or when an inbound event matches multiple protocol actions ambiguously. Each deviation triggers an intelligence event published to `cce.intelligence.triggers` for downstream alerting and analytics.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | **Primary key.** Unique identifier for this deviation record. |
| `protocol_instance_id` | `UUID` | **NOT NULL** | — | **Foreign key → `protocol_instance.id`.** The patient's protocol enrollment where the deviation was detected. |
| `step_instance_id` | `UUID` | **NOT NULL** | — | **Foreign key → `step_instance.id`.** The specific step that triggered the deviation. |
| `deviation_type` | `VARCHAR` | **NOT NULL** | — | **Type classification.** Category of the deviation. See [Enumerated Values: DeviationType](#deviationtype). |
| `detected_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | **Detection timestamp.** When the deviation was identified. |
| `intelligence_event_id` | `UUID` | Yes | — | **Intelligence trigger reference.** Links to the intelligence event published to Kafka for downstream alerting. `NULL` if the intelligence trigger has not yet been correlated. |
| `metadata` | `JSONB` | Yes | — | **Additional context.** Flexible JSON payload providing deviation-specific details. See [JSONB: deviation metadata](#deviation-metadata). |

### Constraints

| Type | Name | Details |
|------|------|---------|
| Primary Key | `deviation_pkey` | `id` |
| Foreign Key | `deviation_protocol_instance_id_fkey` | `protocol_instance_id` → `protocol_instance(id)` |
| Foreign Key | `deviation_step_instance_id_fkey` | `step_instance_id` → `step_instance(id)` |
| Check | — | `deviation_type IN ('OVERDUE', 'MISSED', 'AMBIGUOUS')` |

### Indexes

| Name | Columns | Type | Purpose |
|------|---------|------|---------|
| `idx_deviation_protocol` | `protocol_instance_id` | B-tree | Find all deviations for a specific protocol enrollment. |
| `idx_deviation_type` | `deviation_type` | B-tree | Filter deviations by type for analytics and reporting. |

---

## 6. trigger_index

### Purpose

An **inverted index** that enables fast **Tier 1 structural matching** of inbound CloudEvents to PlanDefinition actions. Built at protocol load time by decomposing each action's trigger definitions into (resourceType, codeSystem, codeValue) rows. When an event arrives, the Compliance Engine queries this table by the event's resource type and code to find candidate actions without parsing every PlanDefinition. Rebuilt whenever a protocol is reloaded.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `resource_type` | `VARCHAR` | **NOT NULL** | — | **FHIR resource type.** The resource type from the trigger's `DataRequirement.type` (e.g., `Encounter`, `Observation`, `Immunization`, `Condition`, `MedicationRequest`, `MedicationDispense`, `ServiceRequest`, `EpisodeOfCare`). |
| `code_system` | `VARCHAR` | **NOT NULL** | `''` (empty string) | **Code system URI.** The `system` from the trigger's `codeFilter.code.coding` (e.g., `http://openphc.org/encounter-types`, `http://loinc.org`, `http://snomed.info/sct`). Empty string when no specific code is required (resource-type-only match). |
| `code_value` | `VARCHAR` | **NOT NULL** | `''` (empty string) | **Code value.** The `code` from the trigger's `codeFilter.code.coding` (e.g., `anc-visit`, `8480-6`, `429681001`). Empty string when no specific code is required. |
| `plan_definition_id` | `UUID` | **NOT NULL** | — | **Foreign key → `plan_definition.id`.** The protocol definition that owns this trigger. |
| `action_id` | `VARCHAR` | **NOT NULL** | — | **PlanDefinition action ID.** The `action.id` from the PlanDefinition this trigger belongs to (e.g., `enrollment`, `anc-visit-1`, `high-bp-followup`). |
| `trigger_mode` | `VARCHAR` | **NOT NULL** | — | **Trigger type.** How the trigger is activated. See [Enumerated Values: TriggerMode](#triggermode). |

### Constraints

| Type | Name | Details |
|------|------|---------|
| Primary Key (Composite) | `trigger_index_pkey` | `(resource_type, code_system, code_value, plan_definition_id, action_id)` |
| Foreign Key | `trigger_index_plan_definition_id_fkey` | `plan_definition_id` → `plan_definition(id)` |
| Check | — | `trigger_mode IN ('DATA_ADDED', 'NAMED_EVENT')` |

### Indexes

| Name | Columns | Type | Purpose |
|------|---------|------|---------|
| `idx_trigger_index_resource` | `resource_type` | B-tree | Tier 1 resource-type-only matching (when event has no code). |
| `idx_trigger_index_code` | `(resource_type, code_system, code_value)` | B-tree | Tier 1 full structural matching by resource type + code. Primary query path for the matching pipeline. |

### Matching Query

The `TriggerIndexRepository` uses the following JPQL for Tier 1 matching:

```sql
SELECT ti FROM TriggerIndex ti
WHERE ti.resourceType = :resourceType
  AND (ti.codeSystem IS NULL
       OR (ti.codeSystem = :codeSystem AND ti.codeValue = :codeValue))
```

This returns all trigger index entries matching the resource type whose code filter either matches exactly or has no code constraint (empty string).

---

## 7. event_log

### Purpose

**Immutable append-only log** of every inbound CloudEvent received by the Compliance Engine. Records the full event payload, processing outcome (matched, zero_match, ambiguous, duplicate), and links to the resulting protocol instance and step. Used for:
- **Idempotency**: The `(cloudevents_id, source)` unique constraint prevents duplicate processing.
- **Auditability**: Complete provenance trail of every clinical event processed.
- **Troubleshooting**: Full payload preservation enables replay and debugging.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | **Primary key.** Unique identifier for this event log entry. |
| `cloudevents_id` | `VARCHAR` | **NOT NULL** | — | **CloudEvents ID.** The `id` from the CloudEvents envelope (e.g., `evt-a1b2c3d4-1111-4000-8000-000000000001`). Used with `source` for idempotency. |
| `source` | `VARCHAR` | **NOT NULL** | — | **CloudEvents source.** Origin system of the event (e.g., `rhie-mediator`, `smartcare-emr`, `chw-app-musanze`). |
| `source_event_id` | `VARCHAR` | Yes | — | **Source system's event ID.** Optional external identifier from the originating system (e.g., `enc-visit-20260129-001`). Enables cross-referencing with source systems. |
| `subject` | `VARCHAR` | **NOT NULL** | — | **Patient identifier.** The UPID of the patient this event relates to. Copied from the CloudEvent `subject` field. |
| `type` | `VARCHAR` | **NOT NULL** | — | **CloudEvents type.** Event type URI (e.g., `org.openphc.cce.encounter`, `org.openphc.cce.observation`, `org.openphc.cce.immunization`). |
| `event_time` | `TIMESTAMPTZ` | **NOT NULL** | — | **Clinical event time.** When the clinical event actually occurred (from CloudEvent `time`). Used for compliance timing calculations. May differ from `received_at` for late-arriving events. |
| `received_at` | `TIMESTAMPTZ` | **NOT NULL** | — | **Ingestion timestamp.** When the CCE Compliance Service received the event. Used as the **partition key** for table partitioning. |
| `correlation_id` | `VARCHAR` | **NOT NULL** | — | **Distributed tracing ID.** Correlation identifier for tracking the event across the RHIE/CCE ecosystem. Propagated from the CloudEvent `correlationid` extension. |
| `data` | `JSONB` | **NOT NULL** | — | **Full event payload.** The complete CloudEvent `data` body (typically a FHIR resource as JSON). Preserved for audit, replay, and debugging. See [JSONB: event data](#event_log-data). |
| `protocol_instance_id` | `UUID` | Yes | — | **Matched protocol instance.** The `protocol_instance.id` that this event was matched to. `NULL` for zero-match or duplicate events. |
| `protocol_definition_id` | `UUID` | Yes | — | **Matched protocol definition.** The `plan_definition.id` of the matched protocol. `NULL` for zero-match or duplicate events. |
| `action_id` | `VARCHAR` | Yes | — | **Matched action ID.** The PlanDefinition `action.id` that this event was matched to (e.g., `anc-visit-1`). `NULL` for zero-match or duplicate events. |
| `facility_id` | `VARCHAR` | Yes | — | **Facility identifier.** FOSA ID of the health facility where the event originated (from CloudEvent `facilityid` extension). |
| `processing_status` | `VARCHAR` | **NOT NULL** | — | **Processing outcome.** The result of the Compliance Engine's matching pipeline. See [Enumerated Values: ProcessingStatus](#processingstatus). |
| `matched_step_instance_id` | `UUID` | Yes | — | **Completed step reference.** The `step_instance.id` that was completed as a result of this event. `NULL` for non-matched events. |

### Constraints

| Type | Name | Details |
|------|------|---------|
| Unique | `event_log_cloudevents_id_source_received_at_key` | `(cloudevents_id, source, received_at)` — Idempotency guard. Includes `received_at` because the table is partitioned by it. |
| Unique (partial) | `idx_event_log_source_sourceeventid` | `(source, source_event_id, received_at) WHERE source_event_id IS NOT NULL` — Prevents duplicate source-side events. |

> **Note**: No foreign key constraints exist from `event_log` to other tables because this table is partitioned. PostgreSQL partitioned tables have restrictions on cross-table FK references. Referential integrity is maintained at the application layer.

### Indexes

| Name | Columns | Type | Purpose |
|------|---------|------|---------|
| `idx_event_log_subject` | `subject` | B-tree | Patient-centric event history queries (`/v1/patients/{patientId}/events`). |
| `idx_event_log_facility` | `facility_id` | Partial B-tree (`WHERE facility_id IS NOT NULL`) | Facility-level event queries for administrative reporting. |

---

## 8. audit_log

### Purpose

**Immutable audit trail** for all significant system operations. Records both system-generated events (step completions, protocol enrollments, deviations) and API-driven operations (protocol loads, retirements). Used for compliance reporting, security auditing, and operational monitoring.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | **Primary key.** Unique identifier for this audit entry. |
| `event_category` | `VARCHAR` | **NOT NULL** | — | **High-level category.** Groups related audit events (e.g., `COMPLIANCE`, `PROTOCOL_MANAGEMENT`, `SECURITY`). |
| `event_type` | `VARCHAR` | **NOT NULL** | — | **Specific event type.** The action that was performed (e.g., `STEP_COMPLETED`, `PROTOCOL_LOADED`, `PROTOCOL_RETIRED`, `DEVIATION_DETECTED`). |
| `actor` | `VARCHAR` | Yes | — | **Who performed the action.** For system-generated events: `SYSTEM`. For API calls: the authenticated user identity. `NULL` for anonymous system operations. |
| `resource_type` | `VARCHAR` | Yes | — | **Affected resource type.** The type of entity that was affected (e.g., `StepInstance`, `ProtocolInstance`, `PlanDefinition`). |
| `resource_id` | `VARCHAR` | Yes | — | **Affected resource ID.** The UUID of the affected entity, stored as VARCHAR for flexibility. |
| `details` | `JSONB` | Yes | — | **Additional context.** Flexible JSON payload with audit-event-specific details. See [JSONB: audit details](#audit_log-details). |
| `ip_address` | `VARCHAR` | Yes | — | **Client IP address.** The IP of the client that initiated the action. `NULL` for system-generated events. |
| `timestamp` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | **Event timestamp.** When the audited action occurred. |

### Constraints

| Type | Name | Details |
|------|------|---------|
| Primary Key | `audit_log_pkey` | `id` |

### Indexes

| Name | Columns | Type | Purpose |
|------|---------|------|---------|
| `idx_audit_log_category` | `event_category` | B-tree | Filter audit entries by category. |
| `idx_audit_log_actor` | `actor` | B-tree | Find all actions performed by a specific actor. |
| `idx_audit_log_timestamp` | `timestamp` | B-tree | Time-range queries for audit reporting. |

---

## 10. Enumerated Value Reference

### PlanDefinitionStatus

| Value | Description |
|-------|-------------|
| `ACTIVE` | Protocol is active and participates in trigger matching. New patient enrollments are allowed. |
| `RETIRED` | Protocol has been deactivated. Existing enrollments continue but no new enrollments are created. Trigger index entries are removed. |

### ProtocolInstanceStatus

| Value | Description |
|-------|-------------|
| `ACTIVE` | Patient is currently enrolled and the protocol is being tracked. Steps can be created, transitioned, and completed. |
| `COMPLETED` | All required steps have been completed. Protocol monitoring has ended successfully. |
| `WITHDRAWN` | Patient was manually withdrawn from the protocol (e.g., transfer to another facility, patient request). |
| `EXPIRED` | Protocol exceeded its maximum duration without completion. |

### StepState

| Value | Description | Transitions From | Transitions To |
|-------|-------------|-----------------|----------------|
| `PENDING` | Step created but not yet due. Waiting for `due_date` to arrive. | *(initial)* | `DUE`, `COMPLETED`, `SKIPPED` |
| `DUE` | Step is now due for completion. The `due_date` has been reached. | `PENDING` | `OVERDUE`, `COMPLETED`, `SKIPPED` |
| `OVERDUE` | Step was not completed within the tolerance window. A deviation is recorded. | `DUE` | `MISSED`, `COMPLETED`, `SKIPPED` |
| `MISSED` | Step was not completed and has exceeded the missed cutoff. A deviation is recorded. | `OVERDUE` | *(terminal)* |
| `COMPLETED` | Step was completed by a matching inbound event. | `PENDING`, `DUE`, `OVERDUE` | *(terminal)* |
| `SKIPPED` | Step was manually skipped by an operator. | `PENDING`, `DUE`, `OVERDUE` | *(terminal)* |

### CompletionStatus

| Value | Description | Condition |
|-------|-------------|-----------|
| `EARLY` | Step was completed before the due date. | `completed_at < due_date` |
| `ON_TIME` | Step was completed within the expected window. | `due_date ≤ completed_at ≤ overdue_date` (or no `due_date` set) |
| `LATE` | Step was completed after the overdue date. | `completed_at > overdue_date` |

### DeviationType

| Value | Description | Trigger |
|-------|-------------|---------|
| `OVERDUE` | Step exceeded its tolerance window without completion. | Scheduler transitions step from `DUE` → `OVERDUE`. |
| `MISSED` | Step was never completed and exceeded the missed cutoff. | Scheduler transitions step from `OVERDUE` → `MISSED`. |
| `AMBIGUOUS` | An inbound event matched multiple protocol actions, preventing definitive step completion. | Compliance Engine detects >1 confirmed match after Tier 2 evaluation. |

### ProcessingStatus

| Value | Description |
|-------|-------------|
| `MATCHED` | Event was successfully matched to exactly one protocol action. The corresponding step was completed. |
| `ZERO_MATCH` | Event did not match any trigger in the trigger index (Tier 1) or all matches failed Tier 2 condition evaluation. The event is logged but no protocol state changes occur. |
| `AMBIGUOUS` | Event matched multiple protocol actions. Deviations are recorded but no step is completed. Requires manual investigation. |
| `DUPLICATE` | Event was already processed (detected via `cloudevents_id + source` idempotency check). No processing occurs. |

### TriggerMode

| Value | Description |
|-------|-------------|
| `DATA_ADDED` | Trigger fires when a FHIR resource matching the specified type and code filters is received. Standard mode for FHIR-based events. |
| `NAMED_EVENT` | Trigger fires on a named event type (e.g., `home-visit-completed`). Used for non-FHIR payloads from legacy or CHW systems. |

### FailureStage

| Value | Description |
|-------|-------------|
| `KAFKA_PUBLISH` | Failure occurred while publishing a message to a Kafka topic (e.g., dead letter, intelligence trigger). |
| `PROCESSING` | Failure occurred during the Compliance Engine's matching pipeline (e.g., condition evaluation error, database error). |
| `VALIDATION` | Failure occurred during input validation (e.g., malformed CloudEvent, invalid FHIR resource, missing required fields). |

---

## 11. Relationships & Foreign Keys

| Parent Table | Child Table | FK Column | Cascade | Description |
|-------------|-------------|-----------|---------|-------------|
| `plan_definition` | `protocol_instance` | `plan_definition_id` | No cascade | Protocol enrollment references the protocol template. Deletion is prevented by default if instances exist. |
| `plan_definition` | `trigger_index` | `plan_definition_id` | Application-managed | Trigger index entries are explicitly deleted when a protocol is retired or rebuilt. |
| `protocol_instance` | `step_instance` | `protocol_instance_id` | JPA `CascadeType.ALL` | Steps are fully managed by their parent protocol instance. |
| `protocol_instance` | `deviation` | `protocol_instance_id` | JPA `CascadeType.ALL` | Deviations are fully managed by their parent protocol instance. |
| `step_instance` | `deviation` | `step_instance_id` | No cascade (DB level) | Deviations reference the specific step but are not cascade-deleted at DB level. |

> **Note**: `event_log` has no foreign key relationships to other tables due to its partitioned nature. Cross-references (e.g., `matched_step_instance_id` → `step_instance.id`) are maintained at the application layer.

---

## 12. Indexes

### Complete Index Inventory

| Table | Index Name | Column(s) | Type | Partial Filter |
|-------|-----------|-----------|------|----------------|
| `plan_definition` | `idx_plan_definition_triggers` | `definition` | GIN (jsonb_path_ops) | — |
| `protocol_instance` | `idx_protocol_instance_patient` | `patient_id` | B-tree | — |
| `protocol_instance` | `idx_protocol_instance_status` | `status` | B-tree | `WHERE status = 'ACTIVE'` |
| `step_instance` | `idx_step_instance_protocol` | `protocol_instance_id` | B-tree | — |
| `step_instance` | `idx_step_instance_state` | `state` | B-tree | `WHERE state IN ('PENDING', 'DUE', 'OVERDUE')` |
| `step_instance` | `idx_step_instance_due_date` | `due_date` | B-tree | `WHERE state IN ('PENDING', 'DUE', 'OVERDUE')` |
| `deviation` | `idx_deviation_protocol` | `protocol_instance_id` | B-tree | — |
| `deviation` | `idx_deviation_type` | `deviation_type` | B-tree | — |
| `trigger_index` | `idx_trigger_index_resource` | `resource_type` | B-tree | — |
| `trigger_index` | `idx_trigger_index_code` | `(resource_type, code_system, code_value)` | B-tree | — |
| `event_log` | `idx_event_log_subject` | `subject` | B-tree | — |
| `event_log` | `idx_event_log_facility` | `facility_id` | B-tree | `WHERE facility_id IS NOT NULL` |
| `audit_log` | `idx_audit_log_category` | `event_category` | B-tree | — |
| `audit_log` | `idx_audit_log_actor` | `actor` | B-tree | — |
| `audit_log` | `idx_audit_log_timestamp` | `timestamp` | B-tree | — |

---

## 13. Partitioning Strategy

### event_log — Range Partitioning by `received_at`

The `event_log` table uses **monthly range partitioning** on the `received_at` column to manage high-volume event data efficiently.

| Partition | Range Start (inclusive) | Range End (exclusive) |
|-----------|------------------------|-----------------------|
| `event_log_2026_02` | `2026-02-01` | `2026-03-01` |
| `event_log_2026_03` | `2026-03-01` | `2026-04-01` |
| `event_log_2026_04` | `2026-04-01` | `2026-05-01` |
| `event_log_2026_05` | `2026-05-01` | `2026-06-01` |
| `event_log_2026_06` | `2026-06-01` | `2026-07-01` |

**Operational Notes:**
- New partitions must be created **before** the start of each month. Failure to create a partition will cause `INSERT` failures for events in that month.
- Old partitions can be detached and archived for long-term storage without affecting active queries.
- The unique constraint `(cloudevents_id, source, received_at)` includes `received_at` because PostgreSQL requires the partition key in unique constraints.
- Partition creation should be automated via a scheduled job or operational runbook.

**Creating a New Partition:**
```sql
CREATE TABLE event_log_2026_07 PARTITION OF event_log
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
```

---

## 14. JSONB Column Schemas

### plan_definition — `definition`

The `definition` column stores the complete FHIR R4 PlanDefinition resource. Key paths used by the application:

```jsonc
{
  "resourceType": "PlanDefinition",
  "url": "http://openphc.org/fhir/PlanDefinition/anc-high-risk",
  "version": "2.1",
  "status": "active",
  "title": "ANC High-Risk Monitoring Protocol",
  "action": [
    {
      "id": "anc-visit-1",                    // action identifier (→ trigger_index.action_id)
      "title": "ANC Visit 1",
      "trigger": [{
        "type": "data-added",                  // trigger mode
        "data": [{
          "type": "Encounter",                 // → trigger_index.resource_type
          "codeFilter": [{
            "path": "type",                    // FHIR element path
            "code": [{
              "system": "http://openphc.org/encounter-types",  // → trigger_index.code_system
              "code": "anc-visit"                              // → trigger_index.code_value
            }]
          }]
        }],
        "condition": {                         // Tier 2 condition (optional)
          "language": "text/jsonlogic",
          "expression": "{\">\": [{\"var\": \"resource.valueQuantity.value\"}, 140]}"
        }
      }],
      "condition": [{                          // action-level condition (optional)
        "kind": "applicability",
        "expression": {
          "language": "text/jsonlogic",
          "expression": "..."
        }
      }],
      "relatedAction": [{                      // step dependencies 
        "actionId": "enrollment",
        "relationship": "after-start",
        "offsetDuration": { "value": 8, "unit": "wk" }
      }],
      "timingTiming": {                        // repeating step timing
        "repeat": { "count": 6, "frequency": 1, "period": 1, "periodUnit": "mo" }
      },
      "extension": [{                          // tolerance days
        "url": "http://openphc.org/fhir/StructureDefinition/tolerance-days",
        "valueInteger": 7
      }]
    }
  ]
}
```

### deviation — `metadata`

Content varies by deviation type:

**OVERDUE deviation:**
```json
{
  "due_date": "2026-03-12T00:00:00Z",
  "tolerance_days": 3
}
```

**MISSED deviation:**
```json
{
  "due_date": "2026-03-12T00:00:00Z",
  "overdue_date": "2026-03-15T00:00:00Z",
  "days_overdue": 14
}
```

**AMBIGUOUS deviation:**
```json
{
  "matchCount": 2,
  "eventId": "evt-a1b2c3d4-2222-4000-8000-000000000002"
}
```

### event_log — `data`

Contains the full CloudEvent `data` payload. For FHIR-based events, this is a FHIR resource:

```json
{
  "resourceType": "Encounter",
  "id": "9a8e5398-aaaa-4111-84a0-9e1e6e0a0001",
  "status": "finished",
  "class": { "system": "...", "code": "AMB" },
  "type": [{ "coding": [{ "system": "...", "code": "anc-visit" }] }],
  "subject": { "reference": "Patient/260115-0001-7823" },
  "period": { "start": "...", "end": "..." }
}
```

For non-FHIR events (e.g., CHW home visits):

```json
{
  "visit_type": "anc-home-visit",
  "status": "completed",
  "chw_id": "CHW-MUSANZE-042",
  "blood_pressure": { "systolic": 135, "diastolic": 85 }
}
```

### audit_log — `details`

Content varies by audit event type:

**STEP_COMPLETED:**
```json
{
  "protocolInstanceId": "pi-uuid-...",
  "actionId": "anc-visit-1",
  "completionStatus": "ON_TIME",
  "eventId": "evt-..."
}
```

**PROTOCOL_LOADED:**
```json
{
  "url": "http://openphc.org/fhir/PlanDefinition/anc-high-risk",
  "version": "2.1",
  "actionCount": 9,
  "triggerIndexEntries": 24
}
```
