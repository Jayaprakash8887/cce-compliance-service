# Release Notes — v1.2.0

**Release Date:** 2026-05  
**Component:** `cce-compliance-service`  
**Java:** 21 LTS | **Spring Boot:** 3.4.2 | **PostgreSQL:** 16 | **Kafka:** 3.x KRaft

---

## Overview

Release 1.2.0 adds **step-inside-step support with multi-level nesting** — the ability to nest independently-triggerable child steps within a parent step. Sub-steps are modeled as nested `PlanDefinition.action.action[]` entries with `type.coding[0]` system `http://openphc.org/fhir/CodeSystem/action-type` + code `"step"`, indexed in `trigger_index` with their plain action IDs (parent derived from PlanDefinition tree at runtime), and tracked in `step_instance` with parent references. Parent steps can have **both** triggers and sub-steps — sub-steps are created after the parent step completes.

Additionally, this release includes core schema optimizations (V4 migration) for production workloads.

---

## Feature Summary

### Sub-Steps (Step-Inside-Step Nesting)
- `PlanDefinition.action.action[]` with `type = "step"` (system: `http://openphc.org/fhir/CodeSystem/action-type`) creates child step instances within a parent step
- **Multi-level nesting:** Sub-steps can themselves contain nested sub-steps — recursive to arbitrary depth
- Parent steps can have BOTH triggers AND sub-steps (sub-steps are created after parent completes)
- Sub-step triggers indexed in `trigger_index` with their plain action ID (parent derived from PD tree via `findAncestryPath()`)
- Entry-point sub-steps (no `relatedAction` dependency on siblings) created immediately on parent completion
- Progressive instantiation within sub-steps: sub-steps with `relatedAction` pointing to siblings are created when the sibling completes
- Sub-steps can have their own intelligence actions (nested fire-event)
- `ComplianceEngine.processSubStepMatch()` handles sub-step routing (derives parent from PD tree, finds completed parent, resolves sub-step)
- Tier 2 condition evaluation extended for sub-step triggers
- Duplicate creation guard in progressive sub-step instantiation

### PlanDefinition Parser Enhancements
- `classifyNestedActions()` — recursively routes nested actions to either `StepMetadata` (sub-steps) or `IntelligenceActionInfo` at every nesting level
- Classification by explicit `type.coding[0]` (system + code): `"step"` requires system `http://openphc.org/fhir/CodeSystem/action-type`, `"fire-event"` requires system `http://terminology.hl7.org/CodeSystem/action-type` (enforced by `PlanDefinitionActionType` enum with `getSystem()`)
- `StepMetadata` record (self-referencing): id, title, triggers, relatedSteps, timing, toleranceDays, requiredBehavior, intelligenceActions, subSteps — reused for both top-level actions and nested sub-steps
- `buildTriggerIndexEntries()` uses recursive `indexNestedSubStepTriggers()` for plain sub-step ID indexing
- `validateTriggers()` uses recursive `validateActionTriggers()` for nested validation

### Step Instance Lifecycle
- `StepInstance` entity: new `parent_step_id` (UUID FK) column
- `StepInstanceService.createEntryPointSubStepsForAction()` — creates entry-point sub-steps after parent step completion
- `StepInstanceService.createDependentSubSteps()` — progressive sibling instantiation via `relatedAction`
- `StepInstanceService.findStepByProtocolAndActionId()` — looks up steps by protocol and action ID (any state, prefers COMPLETED)
- `createStep()` overload with `parentStepId` parameter for explicit parent reference
- On parent completion: top-level progressive instantiation, deviation detection, and protocol completion check run normally

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
                                    │   ├── Sub-Steps (step-inside-step nesting)
                                    │   │   ├── createEntryPointSubStepsForAction (on parent completion)
                                    │   │   ├── createDependentSubSteps (progressive siblings)
                                    │   │   └── findStepByProtocolAndActionId (parent lookup)
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
5. Sub-step functionality activates only for PlanDefinitions with nested `action.action[]` typed as `"step"`

---

## Known Limitations (v1.2.0)

- **Sub-step scheduler transitions:** The Scheduler Service is not yet aware of sub-step parent relationships. Sub-step OVERDUE→MISSED transitions work independently of the parent step lifecycle.
- **No REST API for sub-step queries:** No dedicated endpoint to list sub-steps for a given parent step (use existing step list filtered by `parentStepId`).
- **Multi-level nesting depth:** While the code supports arbitrary depth recursively, only 2-level nesting has been integration-tested. Extremely deep nesting (>5 levels) should be validated for performance.
- All limitations from v1.1.0 still apply.

---

## Test Coverage

- **370 unit tests** covering all services including multi-level sub-step lifecycle and recursive parsing
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
