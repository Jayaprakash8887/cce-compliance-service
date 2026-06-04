# Release Notes — v1.2.0

**Release Date:** 2026-05  
**Component:** `cce-compliance-service`  
**Java:** 21 LTS | **Spring Boot:** 3.4.2 | **PostgreSQL:** 16 | **Kafka:** 3.x KRaft

---

## Overview

Release 1.2.0 adds **flat-model sub-step support** — nested `PlanDefinition.action.action[]` entries of type `"step"` are flattened into peer-level steps at parse time, connected via `relatedSteps` references. No parent-child hierarchy is stored in the database. This approach simplifies the domain model while preserving full support for multi-level nested action structures in FHIR PlanDefinitions.

Additionally, this release includes core schema optimizations (folded into base V1/V2 migrations) for production workloads and mandatory unique `actionId` validation.

---

## Feature Summary

### Flat Sub-Step Model
- `PlanDefinition.action.action[]` with `type = "step"` (system: `http://openphc.org/fhir/CodeSystem/action-type`) flattened into peer-level steps by `extractSteps()`
- **No parent-child column** — all steps stored uniformly in `step_instance` without `parent_step_id`
- Entry-point sub-steps (no `relatedAction` dependency on siblings) automatically get a `relatedStep` to their parent action (relationship: `after-end`, no offset) added by the parser
- Sub-steps with `relatedAction` pointing to siblings flattened as-is — progressive instantiation via standard `createDependentSteps()`
- All trigger indexing uses the step's **plain action ID** (e.g., `"anc-visit-1-referral"`)
- Intelligence actions found via flat lookup by `actionId` — no tree traversal

### actionId Validation
- All action IDs validated at protocol load time via `validateActionIds()`
- **Mandatory:** Every action at every nesting level must have a non-blank `id`
- **Unique:** No two actions (at any level) may share the same `id`
- Violations rejected with `IllegalArgumentException`

### PlanDefinition Parser Enhancements
- `extractSteps()` — flattens all nested step-type actions into a single `List<StepMetadata>`
- `flattenAction()` — recursive helper that classifies nested actions and builds flat step entries
- `validateActionIds()` + `collectActionIds()` — recursive validation of mandatory + unique IDs
- `StepMetadata` record (8 fields): `id`, `title`, `triggers`, `relatedSteps`, `timing`, `toleranceDays`, `requiredBehavior`, `intelligenceActions`
- `buildTriggerIndexEntries()` indexes all flattened steps with their plain action IDs
- `validateTriggers()` validates all steps uniformly (no recursive tree traversal needed)

### Step Instance Lifecycle
- `StepInstanceService.createDependentSteps(step, steps)` — creates dependent steps when any step completes (including flattened sub-steps)
- `StepInstanceService.detectOrderViolations(step, steps)` — order violation detection across all peer steps
- `StepInstanceService.autoSkipPrecedingOptionalSteps(step, steps)` — auto-skip preceding `could` steps
- `completeStep()` parses PlanDefinition **once** and passes `List<StepMetadata>` to all helper methods (eliminated redundant re-parses)

### Core Schema Optimization
- Performance indexes and constraints for production workloads
- `event_log` renamed to `compliance_event_log` with lean schema (idempotency + processing status only)
- `step_instance.matched_event_id` renamed to `completed_by_event_id`
- `audit_log.ip_address` removed (never populated)

---

## Database Schema Changes

All schema optimizations (performance indexes, constraints, ORDER_VIOLATION deviation type) are included in the base V1/V2 migrations for fresh deployment.

**Removed from schema:**
- No `parent_step_id` column on `step_instance` (flat model)
- No `matched_step_instance_id` column on `compliance_event_log` (dead field removed from entity)

---

## Architecture Update

```
Kafka ─→ InboundEventConsumer ─→ ComplianceEngine
                                    ├── Idempotency (ComplianceEventLogService)
                                    ├── Resource Extraction (ResourceInfoExtractor)
                                    ├── Tier 1 Matching (TriggerMatchingService)
                                    ├── Tier 2 Evaluation (ExpressionEvaluationService)
                                    ├── Enrollment (ProtocolInstanceService)
                                    ├── Step Management (StepInstanceService)
                                    │   ├── Flat step model (all sub-steps flattened to peers)
                                    │   ├── Progressive instantiation via relatedSteps
                                    │   └── Intelligence Evaluation (IntelligenceActionEvaluator)
                                    ├── Deviation Detection (DeviationService)
                                    │   └── Intelligence Evaluation (IntelligenceActionEvaluator)
                                    ├── Intelligence Publishing (IntelligenceTriggerProducer)
                                    └── Audit Logging (AuditService)
```

---

## Migration from v1.1.0

1. Flyway migrations run automatically on startup (all optimizations folded into V1/V2)
2. No breaking changes to existing REST API endpoints (list endpoints now return paginated responses)
3. No changes to existing Kafka message schemas
4. Existing PlanDefinitions without nested actions continue to work unchanged
5. PlanDefinitions with nested `action.action[]` typed as `"step"` are now flattened at parse time — no schema change needed

---

## Known Limitations (v1.2.0)

- **Nesting depth:** While the parser supports arbitrary depth recursively, only 2-level nesting has been integration-tested.
- **actionId uniqueness:** Enforced at load time — existing protocols with duplicate action IDs must be fixed before reloading.
- All limitations from v1.1.0 still apply.

---

## Test Coverage

- **374 unit tests** covering all services including multi-level sub-step lifecycle and recursive parsing
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
- Idempotency via `(cloudeventsId, source)` uniqueness on `compliance_event_log`
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
                                    ├── Idempotency (ComplianceEventLogService)
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
- `compliance_event_log` — Inbound event log with idempotency constraint
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
