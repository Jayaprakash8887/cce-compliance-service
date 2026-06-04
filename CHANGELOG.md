# Changelog

All notable changes to the CCE Compliance Service will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-05

### Added

#### Flat Sub-Step Model
- `PlanDefinition.action.action[]` with `type.coding[0]` system `http://openphc.org/fhir/CodeSystem/action-type` + code `"step"` are flattened into peer-level steps by `extractSteps()`
- Entry-point sub-steps (no sibling `relatedAction`) automatically get a `relatedStep` to their parent action (relationship: `after-end`, no offset)
- Sub-steps with `relatedAction` pointing to siblings are flattened as-is — progressive instantiation via standard `createDependentSteps()`
- All trigger indexing uses the step's plain action ID (no composite path encoding)
- Intelligence actions found via flat lookup by `actionId` — no tree traversal needed

#### actionId Validation
- `PlanDefinitionParser.validateActionIds()` — validates mandatory and unique action IDs across all nesting levels
- `collectActionIds()` — recursive helper collecting all IDs for uniqueness check
- Violations rejected with `IllegalArgumentException` at protocol load time

#### Schema Optimization
- Performance indexes, constraints, and `compliance_event_log` lean schema (all folded into V1/V2 for fresh deployment)
- `event_log` renamed to `compliance_event_log` — lean idempotency log (id, cloudeventsId, source, correlationId, processingStatus, data, receivedAt)
- `step_instance.matched_event_id` renamed to `completed_by_event_id`
- `audit_log.ip_address` column removed
- List endpoints (protocol-definitions, action-definitions, intelligence-events) now return paginated `Page<T>` responses

### Changed

#### Parser & Metadata
- `StepMetadata` record (8 fields): `id`, `title`, `triggers`, `relatedSteps`, `timing`, `toleranceDays`, `requiredBehavior`, `intelligenceActions` — flat, no self-referencing `subSteps` field
- `extractSteps()` replaces `extractAllActions()` — returns a flat `List<StepMetadata>` for all steps at all nesting levels
- `flattenAction()` — recursive helper that classifies nested actions and flattens step-type actions
- `buildTriggerIndexEntries()` indexes all flattened steps uniformly with their plain action IDs
- `validateTriggers()` validates all steps in the flat list — no recursive tree traversal needed
- Only two valid action types: `"step"` (system: `http://openphc.org/fhir/CodeSystem/action-type`) and `"fire-event"` (system: `http://terminology.hl7.org/CodeSystem/action-type`) — parser validates both system URI and code via `hasTypeCoding()`

#### Engine Flow
- `ComplianceEngine` — no sub-step-specific routing; all matched steps (top-level or nested) are processed uniformly
- `StepInstanceService.completeStep()` — parses PlanDefinition **once** and passes `List<StepMetadata>` to `detectOrderViolations()`, `createDependentSteps()`, `autoSkipPrecedingOptionalSteps()`
- `IntelligenceActionEvaluator.findIntelligenceActions()` — flat lookup by actionId (no tree traversal or `parentStepId` usage)

#### Removed
- `StepInstance.parentStepId` field — no parent-child hierarchy in domain model
- `EventLog.matchedStepInstanceId` field — dead field never written to
- `StepInstanceService.findStepByProtocolAndActionId()` — removed (was sub-step-specific)
- `StepInstanceService.createEntryPointSubStepsForAction()` — replaced by flat `createDependentSteps()`
- `StepInstanceService.createDependentSubSteps()` — replaced by flat `createDependentSteps()`
- `ComplianceEngine.processSubStepMatch()` — removed (flat model processes all steps uniformly)
- `ComplianceEngine.findAncestryPath()` — removed (no parent derivation needed)
- `ComplianceEngine.findSubStepInTree()` — removed (flat list replaces tree)
- `ComplianceEngine.resolveSubStepInfo()` — removed
- `StepInstanceRepository.findByParentStepId()` — removed
- `StepInstanceRepository.findByProtocolInstanceIdAndActionId()` — removed (dead code)
- Flyway V5 migration (`parent_step_id` column) — never applied, deleted

---

## [1.1.0] - 2026

### Added

#### Intelligence Pipeline
- `IntelligenceActionEvaluator` — core engine evaluating PlanDefinition intelligence actions on deviation detection and step completion
- Intelligence action extraction from nested `PlanDefinition.action.action[]` with condition (JSONLogic/FHIRPath), `definitionCanonical`, severity, and intelligence destination extensions
- `IntelligenceTriggerProducer` — publishes `IntelligenceTriggerEvent` to `cce.intelligence.triggers` Kafka topic (fire-and-forget, keyed by protocolInstanceId)
- `IntelligenceEventLog` entity recording each intelligence action execution with evaluation context and Kafka event payload
- `ActionDefinition` entity for FHIR `ActivityDefinition` resources — CRUD operations via `ActionDefinitionService`
- Flyway V2 migration: `action_definition`, `intelligence_event_log` tables with indexes
- `ORDER_VIOLATION` deviation type for detecting out-of-sequence step completions (included in V1 CHECK constraint)

#### New Enums
- `ActionDefinitionStatus` (ACTIVE, RETIRED)
- `ActionDefinitionKind` (CommunicationRequest, Task, ServiceRequest)
- `PlanDefinitionActionType` (STEP, FIRE_EVENT) — PlanDefinition action type codings with system URI awareness (`http://openphc.org/fhir/CodeSystem/action-type` for step, `http://terminology.hl7.org/CodeSystem/action-type` for fire-event)
- `IntelligenceSeverity` (LOW, MEDIUM, HIGH, CRITICAL)

#### REST API
- `ActionDefinitionController` — 6 endpoints: POST create, GET list (filter by status), GET by ID, PUT update, POST retire, DELETE
- `IntelligenceEventLogController` — 2 endpoints: GET list (filter by protocolInstanceId, actionDefinitionId, published), GET by ID
- DTOs: `ActionDefinitionDto`, `IntelligenceEventLogDto`

#### Observability
- `cce.intelligence.actions.evaluated` counter — total intelligence action conditions evaluated
- `cce.intelligence.actions.fired` counter — actions that matched and triggered
- `cce.intelligence.publish.duration` timer — Kafka publish latency
- `cce.action.definitions.active` gauge — active action definitions count
- `cce.events.intelligence.published` counter — now actively incremented
- MDC `intelligenceEventId` context during intelligence event publishing

#### Wiring
- `StepInstanceService.applySchedulerTransition()` — calls `evaluateOnDeviation()` after OVERDUE/MISSED deviation creation
- `ComplianceEngine.processMatch()` / `processExplicitMatch()` — calls `evaluateOnCompletion()` after step completion

### Changed

#### Performance Optimizations
- `IntelligenceActionEvaluator` — bounded `ConcurrentHashMap` cache for parsed PlanDefinition objects, avoiding FHIR re-parsing on every deviation/completion evaluation
- `PlanDefinitionParser.StepMetadata` record — extended with `List<IntelligenceActionInfo> intelligenceActions` field

### Testing
- 370 unit tests (was 351 in v1.1.0) — 19 new tests for sub-step parsing and multi-level nesting
- 39 integration tests (unchanged from v1.1.0)

---

## [1.1.0] - 2026

### Testing
- 351 unit tests (was 254 in v1.0.0) — 97 new tests for intelligence pipeline
- 39 integration tests (was 24 in v1.0.0) — 15 new: `ActionDefinitionApiIntegrationTest` (9), `IntelligencePipelineIntegrationTest` (6)

---

## [1.0.0] - 2025

### Added

#### Core Engine
- `ComplianceEngine` central orchestrator for inbound clinical event processing
- Two-tier matching pipeline: Tier 1 structural (GROUP BY + HAVING) + Tier 2 condition evaluation (JSONLogic/FHIRPath)
- Five exclusive matching scenarios: (F1), (F1,F2), (F1,F3), (F1,F2,F3), (F3)
- Explicit match processing via CloudEvent `actionId` extension
- Per-event action metadata cache to eliminate redundant PlanDefinition parsing in hot path
- JSONLogic rule cache for compiled expression reuse across events

#### Protocol Management
- FHIR R4 `PlanDefinition` loading, parsing, validation, and storage
- Trigger index construction from codeFilter decomposition
- In-memory condition-only trigger cache (ConcurrentHashMap)
- Protocol retire, rebuild-index, and delete operations

#### Patient Tracking
- Idempotent patient enrollment to protocol instances
- Step state machine: PENDING → DUE → OVERDUE → MISSED (scheduler) / COMPLETED (event)
- Progressive step instantiation via `relatedAction` offsets
- Recurring step support with `TimingInfo.count`
- Auto-skip of optional (`could`) steps
- Automatic protocol completion detection

#### Deviation Detection
- Automatic deviation recording for OVERDUE and MISSED transitions
- Deviation metadata with timing details

#### Kafka Integration
- `InboundEventConsumer` for clinical events (CloudEvents v1.0)
- `SchedulerTriggerConsumer` for scheduler-driven state transitions
- 5 topic declarations (3 primary + 2 DLQ) with 25 partitions each
- `DefaultErrorHandler` with `FixedBackOff` retry and `DeadLetterPublishingRecoverer`
- `ErrorHandlingDeserializer` wrapping for poison pill protection
- `AckMode.RECORD` for per-record offset commits

#### REST API
- 16 endpoints across 3 controllers (Protocol Definitions, Protocol Instances, Patient Tracking)
- DTOs with entity-to-DTO mapping via `DtoMapper`
- `GlobalExceptionHandler` with structured error responses

#### Domain Model
- 7 JPA entities with UUID primary keys (except `TriggerIndex` composite PK)
- 7 enums for type-safe status values
- JSONB column support via Hibernate 6 `@JdbcTypeCode(SqlTypes.JSON)`
- Flyway schema migrations with `ddl-auto=validate`
- Fetch-join query for protocol instance details (steps + deviations)

#### Observability
- Micrometer counters: `cce.events.processed`, `cce.events.matched`, `cce.events.duplicate`, `cce.events.zero_match`
- Micrometer timers: `cce.step.matching.duration`, `cce.events.processing.duration`
- Micrometer gauge: `cce.protocol.instances.active`
- Prometheus endpoint, health probes, structured logging

#### Infrastructure
- Spring Boot 3.4.2 / Java 21 with Gradle build
- Multi-stage Dockerfile (Temurin 21 JRE Alpine, non-root)
- Docker Compose (PostgreSQL 16 + Kafka KRaft)
- Production profile (`application-prod.yml`)
- HikariCP connection pool tuning
- JaCoCo code coverage reporting

#### Testing
- 254 unit tests (MockMvc, mocked services)
- 24 integration tests (EmbeddedKafka + H2)
- Integration test source set with separate configuration

#### Documentation
- Architecture & design overview
- API reference with all endpoints
- Data dictionary with ER diagram
- Kafka event architecture
- Developer setup guide
- Flow diagrams (Mermaid)
- Deployment guide
- Release notes

### Removed
- CQL expression evaluation (out of scope for v1.0.0)
- Intelligence trigger Kafka publishing (deferred to future phase)
- `cce.protocol.control` topic (reserved for future use)
