# Release Notes — v1.1.0

**Release Date:** 2026  
**Component:** `cce-compliance-service`  
**Java:** 21 LTS | **Spring Boot:** 3.4.2 | **PostgreSQL:** 16 | **Kafka:** 3.x KRaft

---

## Overview

Release 1.1.0 adds the Intelligence Pipeline — end-to-end evaluation, recording, and publishing of intelligence actions defined within FHIR `PlanDefinition` protocols. When a step deviation is detected (OVERDUE/MISSED) or a step is completed, nested intelligence actions are evaluated against runtime context using JSONLogic/FHIRPath conditions. Matching actions resolve to `ActivityDefinition`-backed action definitions, create an `IntelligenceEventLog` record, and publish trigger events to Kafka for downstream processing by the CCE Intelligence Service.

Additionally, this release adds `ORDER_VIOLATION` as a new deviation type (V3 migration) to detect steps completed out of sequence.

---

## Feature Summary

### Intelligence Action Evaluation
- Nested `PlanDefinition.action.action[]` intelligence actions extracted during protocol loading
- Each intelligence action has a condition (JSONLogic/FHIRPath), `definitionCanonical`, severity, and intelligence channel extensions
- `IntelligenceActionEvaluator` evaluates actions at two trigger points:
  - **Deviation detection** — when a step transitions to OVERDUE or MISSED
  - **Step completion** — when a step is completed (for actions like "notify on late completion")
- Context variables available to conditions: `stepState`, `deviationType`, `daysOverdue`, `daysPastMissedDate`, `actionId`, `repeatIndex`, `completionStatus`, `completedAt`
- Bounded PlanDefinition parse cache (`ConcurrentHashMap`) eliminates redundant FHIR parsing per protocol

### Action Definitions (FHIR ActivityDefinition)
- CRUD operations for `ActivityDefinition` resources stored as `ActionDefinition` entities
- Canonical URL + version uniqueness enforced
- Status lifecycle: ACTIVE → RETIRED
- Referenced by intelligence actions via `definitionCanonical` (format: `url|version`)
- Action types: `CommunicationRequest`, `Task`, `ServiceRequest` (from FHIR `ActivityDefinition.kind`)
- Severity levels: LOW, MEDIUM, HIGH, CRITICAL
- Intelligence channel: free-form routing identifier (e.g., `supervisor`, `patient`, `high_hospital_alert`)

### Intelligence Event Logging
- `IntelligenceEventLog` entity records each intelligence action execution in a single flat row
- Stores the complete Kafka event payload (`event_payload` JSONB), evaluation context, trigger reason, and publish status
- `published` boolean tracks whether the event was successfully sent to Kafka
- No FK constraints — plain UUID columns for full decoupling from core compliance tables

### Intelligence Trigger Publishing
- `IntelligenceTriggerProducer` publishes `IntelligenceTriggerEvent` to `cce.intelligence.triggers` topic
- Kafka key: `protocolInstanceId` (partition locality for per-patient ordering)
- Fire-and-forget model — publishing failures are logged but don't fail the main transaction
- Event payload includes: protocolInstanceId, stepInstanceId, deviationId, deviationType, stepState, actionId, protocolCanonical

### Order Violation Detection
- New `ORDER_VIOLATION` deviation type (V3 migration)
- Detects when protocol steps are completed out of defined sequence (`relatedAction` ordering)

### REST API Additions
- **Action Definitions** — 6 endpoints at `/v1/compliance/action-definitions`:
  - `POST /` — create from ActivityDefinition JSON
  - `GET /` — list all (filter by status)
  - `GET /{id}` — get by ID
  - `PUT /{id}` — update definition
  - `POST /{id}/retire` — retire
  - `DELETE /{id}` — delete (fails if action runs reference it)
- **Intelligence Events** — 2 endpoints at `/v1/compliance/intelligence-events`:
  - `GET /` — list all (filter by protocolInstanceId, actionDefinitionId, published)
  - `GET /{id}` — get by ID

### Observability
- New Micrometer metrics: `cce.intelligence.actions.evaluated`, `cce.intelligence.actions.fired`, `cce.intelligence.publish.duration`, `cce.action.definitions.active` (gauge)
- MDC `intelligenceEventId` context during intelligence event publishing
- Structured log messages for intelligence pipeline steps

### Performance
- Bounded `ConcurrentHashMap` cache for parsed PlanDefinition objects in `IntelligenceActionEvaluator`

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
                                    │   └── Intelligence Evaluation (IntelligenceActionEvaluator)
                                    ├── Deviation Detection (DeviationService)
                                    │   └── Intelligence Evaluation (IntelligenceActionEvaluator)
                                    ├── Intelligence Publishing (IntelligenceTriggerProducer)
                                    └── Audit Logging (AuditService)
```

---

## Database Schema Changes

3 Flyway migrations:
- `V1__initial_schema.sql` — Initial 7 tables (protocol_definition, protocol_instance, step_instance, deviation, trigger_index, event_log, audit_log)
- `V2__intelligence_tables.sql` — 2 new tables: `action_definition`, `intelligence_event_log` (9 total)
- `V3__add_order_violation_deviation_type.sql` — Adds `ORDER_VIOLATION` to deviation_type CHECK constraint

Total tables: 9

---

## Migration from v1.0.0

1. Apply Flyway V2 migration (automatic on startup)
2. No breaking changes to existing REST API endpoints
3. No changes to existing Kafka message schemas
4. New intelligence triggers will only fire for protocols with nested intelligence actions in their PlanDefinition

---

## Known Limitations (v1.1.0)

- **Intelligence event delivery/routing:** This service publishes trigger events to `cce.intelligence.triggers` — actual delivery to Receiver Adaptors is handled by the CCE Intelligence Service
- **No CQL support:** Only JSONLogic and FHIRPath expression languages are supported
- **`cce.protocol.control` topic reserved:** Not implemented
- **No multi-tenancy:** Single-tenant deployment assumed
- **No authentication at service level:** Security handled by CCE API Gateway

---

## Test Coverage

- **351 unit tests** covering all services, controllers, mappers, and intelligence pipeline
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
