# Release Notes — v1.2.0

**Release Date:** 2026-05  
**Component:** `cce-compliance-service`  
**Java:** 21 LTS | **Spring Boot:** 3.4.2 | **PostgreSQL:** 16 | **Kafka:** 3.x KRaft

---

## Overview

Release 1.2.0 adds **sub-step group support with multi-level nesting** — the ability to decompose a protocol step into independently-triggerable child steps, which can themselves be groups containing their own sub-steps, recursively to arbitrary depth. Sub-steps are modeled as nested `PlanDefinition.action.action[]` entries with `type.coding[0].code = "sub-step"`, indexed in `trigger_index` with composite actionIds (encoding the full ancestor path), and tracked in `step_instance` with parent references. Group completion is controlled by FHIR `selectionBehavior` and bubbles up recursively through all nesting levels.

Additionally, this release includes core schema optimizations (V4 migration) for production workloads.

---

## Feature Summary

### Sub-Step Groups (Multi-Level Nesting)
- `PlanDefinition.action.action[]` with `type = "sub-step"` creates child step instances within a group
- **Multi-level nesting:** Sub-steps can themselves be groups containing nested sub-steps — recursive to arbitrary depth
- Group steps have NO trigger (validated at load time — mutually exclusive with sub-steps)
- Sub-step triggers indexed in `trigger_index` with composite actionId format: `"ancestor/.../parent/subStepId"` (arbitrary depth)
- `selectionBehavior` controls group completion: `all` (default), `any`, `exactly-one`, `at-most-one`, `one-or-more`, `all-or-none`
- **Recursive group completion:** When a sub-step group completes, its parent group is re-evaluated, bubbling up to the top-level
- Progressive instantiation within groups: sub-steps with `relatedAction` pointing to siblings are created when the sibling completes
- Sub-steps can have their own intelligence actions (nested fire-event)
- `ComplianceEngine.processSubStepMatch()` handles N-level composite actionId routing (creates intermediate ancestor steps as needed)
- Tier 2 condition evaluation extended for multi-level sub-step triggers
- Duplicate creation guard in progressive sub-step instantiation

### PlanDefinition Parser Enhancements
- `classifyNestedActions()` — recursively routes nested actions to either `SubStepActionInfo` or `IntelligenceActionInfo` at every nesting level
- Classification by explicit `type.coding[0].code` with backward-compatible fallback heuristics
- `ActionMetadata` record extended with `groupingBehavior`, `selectionBehavior`, `subSteps` fields
- New `SubStepActionInfo` record (self-referencing): id, title, triggers, relatedActions, timing, toleranceDays, requiredBehavior, groupingBehavior, selectionBehavior, intelligenceActions, subSteps
- `buildTriggerIndexEntries()` uses recursive `indexNestedSubStepTriggers()` for arbitrary-depth composite actionId construction
- `validateTriggers()` uses recursive `validateActionTriggers()` for nested group validation
- Validation: rejects actions with both triggers AND sub-steps

### Step Instance Lifecycle
- `StepInstance` entity: new `parent_step_id` (UUID FK) column
- `StepInstanceService.createSubSteps()` — recursively creates entry-point sub-steps for nested groups at all levels
- `StepInstanceService.createDependentSubSteps()` — progressive sibling instantiation via `relatedAction`, with recursive child creation for nested groups
- `StepInstanceService.evaluateGroupCompletion()` — auto-completes parent when `selectionBehavior` is satisfied, then **recursively evaluates grandparent** completion
- `StepInstanceService.resolveSelectionBehavior()` / `resolveSubSteps()` — recursive tree traversal helpers for multi-level lookup
- `createStep()` overload with `parentStepId` parameter for explicit parent reference
- On parent auto-completion: top-level progressive instantiation, deviation detection, and protocol completion check run normally

### Core Schema Optimization (V4)
- Performance indexes and constraints for production workloads

---

## Database Schema Changes

5 Flyway migrations (2 new since v1.1.0):
- `V4__core_schema_optimization.sql` — Performance indexes and constraints
- `V5__add_sub_step_support.sql` — Adds `parent_step_id` (UUID FK → step_instance) to `step_instance` + index

---

## Architecture Update

```
Kafka ─→ InboundEventConsumer ─→ ComplianceEngine
                                    ├── Idempotency (EventLogService)
                                    ├── Resource Extraction (ResourceInfoExtractor)
                                    ├── Tier 1 Matching (TriggerMatchingService)
                                    ├── Tier 2 Evaluation (ExpressionEvaluationService)
                                    ├── Enrollment (ProtocolInstanceService)
                                    ├── Step Management (StepInstanceService)
                                    │   ├── Sub-Step Groups (multi-level recursive nesting)
                                    │   │   ├── createSubSteps (recursive for nested groups)
                                    │   │   ├── evaluateGroupCompletion (recursive bubble-up)
                                    │   │   └── resolveSubSteps / resolveSelectionBehavior (tree traversal)
                                    │   └── Intelligence Evaluation (IntelligenceActionEvaluator)
                                    ├── Deviation Detection (DeviationService)
                                    │   └── Intelligence Evaluation (IntelligenceActionEvaluator)
                                    ├── Intelligence Publishing (IntelligenceTriggerProducer)
                                    └── Audit Logging (AuditService)
```

---

## Migration from v1.1.0

1. Apply Flyway V4 + V5 migrations (automatic on startup)
2. No breaking changes to existing REST API endpoints
3. No changes to existing Kafka message schemas
4. Existing PlanDefinitions without sub-steps continue to work unchanged
5. Sub-step functionality activates only for PlanDefinitions with nested `action.action[]` typed as `sub-step`

---

## Known Limitations (v1.2.0)

- **Sub-step scheduler transitions:** The Scheduler Service is not yet aware of sub-step parent relationships. Sub-step OVERDUE→MISSED transitions may trigger without evaluating group-level semantics. This will be addressed in a future release.
- **No REST API for sub-step queries:** No dedicated endpoint to list sub-steps for a given parent step (use existing step list filtered by `parentStepId`).
- **Multi-level nesting depth:** While the code supports arbitrary depth recursively, only 2-level nesting has been integration-tested. Extremely deep nesting (>5 levels) should be validated for performance.
- All limitations from v1.1.0 still apply.

---

## Test Coverage

- **368 unit tests** covering all services including multi-level sub-step lifecycle and recursive parsing
- **39 integration tests** covering end-to-end workflows with EmbeddedKafka + H2
- JaCoCo coverage reports via `./gradlew test jacocoTestReport`

---
---

# Release Notes — v1.0.0 (Previous)

**Release Date:** 2025  
**Component:** `cce-compliance-service`  
**Java:** 21 LTS | **Spring Boot:** 3.4.2 | **PostgreSQL:** 16 | **Kafka:** 3.x KRaft

---

## Overview

Initial release of the CCE Compliance Service — a clinical protocol compliance engine that tracks patient adherence to FHIR R4 `PlanDefinition` protocols. The service consumes clinical events via Kafka, matches them against loaded protocol definitions using a two-tier matching algorithm, enrolls patients, tracks step completion, and detects deviations.

---

## Feature Summary

### Protocol Management
- Load FHIR R4 `PlanDefinition` JSON resources as protocol definitions
- Parse and validate triggers, actions, timing, and conditions
- Build and maintain a `trigger_index` for fast structural matching
- Retire, rebuild-index, and delete protocol definitions
- Automatic registration of condition-only triggers in an in-memory cache

### Two-Tier Event Matching
- **Tier 1 — Structural matching:** GROUP BY + HAVING query against `trigger_index` enforces AND semantics across multiple codeFilter entries per action
- **Tier 2 — Condition evaluation:** JSONLogic (`text/jsonlogic`) and FHIRPath (`text/fhirpath`) expression evaluation against event payloads
- **Five exclusive matching scenarios:** (F1) resource type only, (F1,F2) type + codeFilters, (F1,F3) type + condition, (F1,F2,F3) type + codeFilters + condition, (F3) condition only
- **Explicit matching:** CloudEvents with `actionId` extension bypass Tier 1/2 entirely

### Patient Enrollment & Step Tracking
- Idempotent patient enrollment — existing active enrollments are reused
- Step state machine: `PENDING → DUE → OVERDUE → MISSED` (scheduler-driven) and `PENDING/DUE/OVERDUE → COMPLETED` (event-driven)
- Progressive step instantiation via `relatedAction` with offset-based due date calculation
- Recurring step support via `TimingInfo.count` with staggered due dates
- Auto-skip of optional (`could`) steps when subsequent steps complete
- Automatic protocol completion when all steps reach terminal states

### Deviation Detection
- Automatic deviation recording for OVERDUE and MISSED step transitions
- Deviation metadata includes timing details (days overdue, days past missed)
- Intelligence trigger event schema documented for future publishing phase

### Event Processing
- CloudEvents v1.0 spec with CCE extension attributes (`correlationid`, `actionid`, `facilityid`, `protocolinstanceid`)
- Idempotency via `(cloudeventsId, source)` uniqueness on `event_log`
- Comprehensive event logging with processing status tracking

### Kafka Infrastructure
- **3 primary topics:** `cce.events.inbound`, `cce.scheduler.triggers`, `cce.intelligence.triggers`
- **2 DLQ topics:** `cce.events.inbound.dlq`, `cce.scheduler.triggers.dlq`
- 25 partitions per topic (configurable)
- `AckMode.RECORD` with `DefaultErrorHandler` + `FixedBackOff` retry + `DeadLetterPublishingRecoverer`
- `ErrorHandlingDeserializer` wrapping for poison pill protection

### REST API
- **16 endpoints** across 3 controllers:
  - Protocol Definitions (8): load, list, get by ID, get by URL, get by URL+version, retire, rebuild-index, delete
  - Protocol Instances (2): get by ID, withdraw
  - Patient Tracking (6): list instances, active instances, instance detail, steps, deviations, events
- Structured error responses via `GlobalExceptionHandler`
- DTOs mapped via `DtoMapper` — entities never exposed in responses

### Observability
- **Micrometer metrics:** `cce.events.processed`, `cce.events.matched`, `cce.events.duplicate`, `cce.events.zero_match`, `cce.step.matching.duration`, `cce.events.processing.duration`, `cce.protocol.instances.active` (gauge)
- Prometheus endpoint at `/actuator/prometheus`
- Health probes enabled for Kubernetes liveness/readiness
- Structured logging with SLF4J/Logback

### Infrastructure
- Multi-stage Docker build with Eclipse Temurin 21 JRE Alpine
- Non-root container execution
- Docker Compose for local development (PostgreSQL 16 + Kafka KRaft)
- Flyway schema migrations (`ddl-auto=validate`)
- HikariCP connection pool tuning
- Production profile (`application-prod.yml`) with optimized settings

---

## Architecture

```
Kafka ─→ InboundEventConsumer ─→ ComplianceEngine
                                    ├── Idempotency (EventLogService)
                                    ├── Resource Extraction (ResourceInfoExtractor)
                                    ├── Tier 1 Matching (TriggerMatchingService)
                                    ├── Tier 2 Evaluation (ExpressionEvaluationService)
                                    ├── Enrollment (ProtocolInstanceService)
                                    ├── Step Management (StepInstanceService)
                                    ├── Deviation Detection (DeviationService)
                                    └── Audit Logging (AuditService)
```

---

## Database Schema

7 tables managed via Flyway:
- `protocol_definition` — FHIR PlanDefinition storage with JSONB definition column
- `protocol_instance` — Patient enrollment tracking
- `step_instance` — Individual step state tracking
- `deviation` — Deviation records with metadata
- `trigger_index` — Decomposed codeFilter entries for Tier 1 matching (composite PK)
- `event_log` — Inbound event log with idempotency constraint
- `audit_log` — Audit trail for all operations

---

## Known Limitations (v1.0.0)

- **No CQL support:** Only JSONLogic (`text/jsonlogic`) and FHIRPath (`text/fhirpath`) expression languages are supported. CQL evaluation was removed from scope.
- **Intelligence trigger publishing deferred:** `IntelligenceTriggerEvent` model exists for schema documentation, but no Kafka producer or publishing logic is implemented. This is deferred to a future phase, driven by PlanDefinition-level configuration.
- **`cce.protocol.control` topic reserved:** Reserved for future use — not implemented in 1.0.0.
- **No multi-tenancy:** Single-tenant deployment assumed.
- **No authentication at service level:** Security is handled by the CCE API Gateway.

---

## Breaking Changes

N/A — initial release.

---

## Upgrade Instructions

N/A — initial release.

---

## Test Coverage

- **254 unit tests** covering all services, controllers, and mappers
- **24 integration tests** covering end-to-end workflows with EmbeddedKafka + H2
- JaCoCo coverage reports via `./gradlew test jacocoTestReport`
