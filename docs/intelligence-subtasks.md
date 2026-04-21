# CCE Compliance Service — Intelligence Feature Subtasks (Sequential Execution)

**Epic:** CCE Intelligence Actions & Action Definitions  
**Component:** `cce-compliance-service`  
**Sprint Target:** Release 1.1.0  
**Branch:** `demo` (from `release-1.0.0`)  
**Total Subtasks:** 12  
**Depends On:** Release 1.0.0 (all subtasks from `compliance-service-subtasks.md` complete)

> Each subtask is a single PR-able unit. Execute in listed order — each depends on the prior one being merged.

---

## Context & Scope

Release 1.0.0 delivered full compliance tracking: event matching, enrollment, step management, and deviation detection. Intelligence trigger publishing was deferred as "future phase." This development cycle implements the intelligence pipeline end-to-end:

1. **Action Definitions** — FHIR `ActivityDefinition` resources defining what CCE does when a rule fires
2. **Intelligence Action Evaluation** — Evaluating PlanDefinition intelligence actions (nested `action[].action[]`) on deviation detection and step completion
3. **Intelligence Trigger Publishing** — Publishing events to `cce.intelligence.triggers` Kafka topic
4. **Action Run Tracking** — Recording each intelligence action execution with status and output

### Pipeline Flow (Post-Implementation)

```
Deviation Detected / Step Completed
  → Parse PlanDefinition from protocol
  → For each action matching step.actionId:
      → For each intelligence action (action.action[]):
          → Evaluate condition (JSONLogic/FHIRPath) against step runtime context
          → If condition matches:
              → Resolve definitionCanonical → ActionDefinition
              → Create ActionRun record (status=TRIGGERED)
              → Build & publish IntelligenceTriggerEvent to cce.intelligence.triggers
              → Update ActionRun (status=PUBLISHED)
              → Update Deviation.intelligenceEventId
```

### Key Design Decisions

- Intelligence actions are nested actions within PlanDefinition steps (FHIR `action.action[]`)
- Each intelligence action has a `condition` (JSONLogic/FHIRPath) evaluated against step runtime state
- Each intelligence action references an `ActivityDefinition` via `definitionCanonical`
- Intelligence evaluation is triggered on:
  - **Deviation detection** — when a step transitions to OVERDUE or MISSED
  - **Step completion** — when a step is completed (for actions like "notify on late completion")
- The Kafka message key for intelligence events is `protocolInstanceId` (partition locality)
- `ActionRun` records track each intelligence action execution for auditability and idempotency

---

## Subtask I-0: Documentation & Design Review

**Type:** Task  
**Priority:** Highest  
**Story Points:** 3  
**Labels:** `documentation`, `design`

**Description:**  
Update all existing documentation to include intelligence feature design before implementation begins. This PR is for design review only — no code changes.

**Acceptance Criteria:**
- [ ] `docs/architecture-overview.md` — Add §6.3 Intelligence Action Evaluation, update system context diagram, update package structure
- [ ] `docs/data-dictionary.md` — Add `action_definition` and `action_run` table schemas, new enums (`ActionDefinitionStatus`, `ActionType`, `IntelligenceSeverity`, `IntelligenceTarget`, `ActionRunStatus`), updated ER diagram, JSONB column schemas
- [ ] `docs/kafka-events.md` — Activate §5.3 IntelligenceTriggerEvent, add §7.1 IntelligenceTriggerProducer implementation, update topic reference table
- [ ] `docs/api-reference.md` — Add §7 Action Definitions (POST, GET list, GET by ID, PUT, DELETE, retire), §8 Action Runs (GET list, GET by ID) endpoints with DTOs
- [ ] `docs/flow-diagrams.md` — Add intelligence action evaluation sequence diagram, intelligence trigger publishing flow
- [ ] `docs/intelligence-subtasks.md` — This file (development plan)

**Files:**
- `docs/architecture-overview.md`
- `docs/data-dictionary.md`
- `docs/kafka-events.md`
- `docs/api-reference.md`
- `docs/flow-diagrams.md`
- `docs/intelligence-subtasks.md`

---

## Subtask I-1: Flyway V2 Migration — Intelligence Tables

**Type:** Task  
**Priority:** Highest  
**Story Points:** 3  
**Labels:** `database`, `migration`

**Description:**  
Create the Flyway V2 migration adding `action_definition` and `action_run` tables.

**Acceptance Criteria:**
- [ ] `V2__intelligence_tables.sql`:
  - `action_definition` table — UUID PK, `canonical_url` + `version` unique, `status` (ACTIVE/RETIRED), `action_type` (CommunicationRequest/Task/ServiceRequest — FHIR `ActivityDefinition.kind`), `severity`, `target`, `definition` (JSONB), timestamps
  - `action_run` table — UUID PK, FKs to `action_definition`, `protocol_instance`, `step_instance` (nullable), `status` (TRIGGERED/PUBLISHED/FAILED/CANCELLED), `intelligence_event_id`, `output_metadata` (JSONB), timestamps
  - `action_run_context` table — UUID PK, FK to `action_run` (unique, 1:1), FK to `deviation` (nullable), `trigger_reason`, `step_action_id`, `evaluation_expression`, `evaluation_context` (JSONB), `created_at`
  - All check constraints, foreign keys, and indexes
- [ ] Flyway migration applies cleanly on existing V1 schema
- [ ] `./gradlew build` succeeds with H2 integration tests

**Files:**
- `src/main/resources/db/migration/V2__intelligence_tables.sql`

---

## Subtask I-2: New Enums & Domain Entities

**Type:** Task  
**Priority:** Highest  
**Story Points:** 5  
**Labels:** `domain`, `model`

**Description:**  
Implement new enums and JPA entities for `ActionDefinition` and `ActionRun`.

**Acceptance Criteria:**
- [ ] **New Enums (5):**
  - `ActionDefinitionStatus` — `ACTIVE`, `RETIRED`
  - `ActionType` — `CommunicationRequest`, `Task`, `ServiceRequest` (FHIR `ActivityDefinition.kind` values)
  - `IntelligenceSeverity` — `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`
  - `IntelligenceTarget` — `PATIENT`, `ASSIGNED_WORKER`, `SUPERVISOR`, `FACILITY`
  - `ActionRunStatus` — `TRIGGERED`, `PUBLISHED`, `FAILED`, `CANCELLED`
- [ ] **ActionDefinition entity:**
  - `id` (UUID), `canonicalUrl`, `version`, `name`, `title`, `status` (enum), `actionType` (enum), `severity` (enum, nullable), `target` (enum, nullable), `definition` (JSONB → `JsonNode`), `createdAt`, `updatedAt`
  - `getCanonical()` → `canonicalUrl|version`
- [ ] **ActionRun entity:**
  - `id` (UUID), FK `actionDefinition`, FK `protocolInstance`, FK `stepInstance` (nullable), `status` (enum), `intelligenceEventId` (UUID), `outputMetadata` (JSONB), `createdAt`, `updatedAt`
- [ ] **ActionRunContext entity:**
  - `id` (UUID), FK `actionRun` (1:1 unique), FK `deviation` (nullable), `triggerReason` (String), `stepActionId` (String), `evaluationExpression` (String), `evaluationContext` (JSONB), `createdAt`
- [ ] **Repositories:**
  - `ActionDefinitionRepository` — `findByCanonicalUrlAndVersion()`, `findByStatus()`
  - `ActionRunRepository` — `findByProtocolInstanceId()`, `findByActionDefinitionId()`, `findByStepInstanceId()`, `findByDeviationId()`
- [ ] Enum values test updated with new enum assertions

**Files:**
- `src/main/java/.../domain/enums/` — 5 new enum files
- `src/main/java/.../domain/entity/ActionDefinition.java`
- `src/main/java/.../domain/entity/ActionRun.java`
- `src/main/java/.../domain/repository/ActionDefinitionRepository.java`
- `src/main/java/.../domain/repository/ActionRunRepository.java`
- `src/test/java/.../domain/enums/EnumValuesTest.java` — updated

---

## Subtask I-3: PlanDefinitionParser — Extract Intelligence Actions

**Type:** Task  
**Priority:** High  
**Story Points:** 5  
**Labels:** `fhir`, `core`

**Description:**  
Extend `PlanDefinitionParser` to extract intelligence actions from nested actions (`action.action[]`). Each intelligence action has a condition, a `definitionCanonical`, severity and target extensions.

**Acceptance Criteria:**
- [x] New record type: `IntelligenceActionInfo` — `actionId`, `conditionLanguage`, `conditionExpression`, `definitionCanonical`, `severity`, `target`
- [x] `extractIntelligenceActions(PlanDefinitionActionComponent action)` → `List<IntelligenceActionInfo>`
  - Iterates over nested `action.getAction()` intelligence actions
  - Extracts `condition[kind=applicability].expression` → language + expression
  - Extracts `definitionCanonical` → canonical URL reference
  - Extracts `intelligence-severity` extension → severity code
  - Extracts `intelligence-target` extension → target code
- [x] Update `ActionMetadata` record to include `List<IntelligenceActionInfo> intelligenceActions`
- [x] Unit tests:
  - PlanDefinition with nested intelligence actions → correct extraction
  - Action with no intelligence actions → empty actions list
  - Intelligence action missing condition → skipped (requires condition)
  - Intelligence action missing definitionCanonical → skipped
  - Extension extraction for severity and target

**Files:**
- `src/main/java/.../fhir/PlanDefinitionParser.java` — updated
- `src/test/java/.../fhir/PlanDefinitionParserTest.java` — updated
- `src/test/resources/fhir/plan-definition-with-intelligence-actions.json` — new test fixture

---

## Subtask I-4: ActionDefinitionService — CRUD Operations

**Type:** Task  
**Priority:** High  
**Story Points:** 3  
**Labels:** `service`

**Description:**  
Implement the service for managing ActivityDefinition resources as action definitions.

**Acceptance Criteria:**
- [ ] `ActionDefinitionService.java` — `@Service`, `@Transactional`
  - `createActionDefinition(JsonNode definition)` → `ActionDefinition`
    - Extracts `url`, `version`, `name`, `title`, `status` from FHIR ActivityDefinition JSON
    - Extracts `action_type` from FHIR `ActivityDefinition.kind` field, and CCE extensions for `severity`, `target`
    - Checks for duplicate `(canonicalUrl, version)` → rejects if exists
    - Persists with `status = ACTIVE`
    - Audits: `ACTION_DEFINITION_CREATED`
  - `updateActionDefinition(UUID id, JsonNode definition)` → `ActionDefinition`
  - `retireActionDefinition(UUID id)` → `ActionDefinition`
  - `deleteActionDefinition(UUID id)` → void (fails if action runs reference it)
  - `findById(UUID id)`, `findAll()`, `findByCanonicalUrl(String url)`
  - `resolveByCanonical(String canonical)` → `ActionDefinition` — parses `url|version` and resolves
- [ ] Unit tests:
  - Create: valid definition → persisted
  - Create duplicate → rejected
  - Resolve by canonical: `url|version` → found
  - Delete with action runs → `IllegalStateException`

**Files:**
- `src/main/java/.../service/ActionDefinitionService.java`
- `src/test/java/.../service/ActionDefinitionServiceTest.java`

---

## Subtask I-5: IntelligenceTriggerProducer — Kafka Publishing

**Type:** Task  
**Priority:** High  
**Story Points:** 3  
**Labels:** `kafka`, `producer`

**Description:**  
Implement the Kafka producer for publishing intelligence trigger events to `cce.intelligence.triggers`.

**Acceptance Criteria:**
- [ ] `IntelligenceTriggerProducer.java` — `@Component`
  - `publish(IntelligenceTriggerEvent event)` → `CompletableFuture<SendResult>`
  - Uses `KafkaTemplate<String, Object>` (already configured in `KafkaConfig`)
  - Kafka key: `event.getProtocolInstanceId().toString()` (partition locality)
  - Topic: `${cce.kafka.topics.intelligence-triggers}`
  - Logs success/failure; increments `cce.events.intelligence.published` counter on success
  - On failure: logs error, does NOT throw (fire-and-forget for intelligence, main transaction not affected)
- [ ] Unit tests:
  - Successful publish → counter incremented, future resolved
  - Publish failure → logged, counter not incremented, no exception thrown

**Files:**
- `src/main/java/.../kafka/producer/IntelligenceTriggerProducer.java`
- `src/test/java/.../kafka/producer/IntelligenceTriggerProducerTest.java`

---

## Subtask I-6: IntelligenceActionEvaluator — Core Intelligence Engine

**Type:** Task  
**Priority:** Highest  
**Story Points:** 8  
**Labels:** `service`, `core`

**Description:**  
Implement the intelligence action evaluation engine. Iterates PlanDefinition protocol steps matching the step, then evaluates each intelligence action: condition check → definition resolution → record & publish.

**Acceptance Criteria:**
- [x] `IntelligenceActionEvaluator.java` — `@Service`
  - `evaluateOnDeviation(StepInstance step, Deviation deviation)` → `List<ActionRun>`
    - Parses PlanDefinition from step's protocol instance
    - Nested loop: for each protocol step matching `step.actionId` → for each intelligence action
    - Evaluates each intelligence action's condition (JSONLogic/FHIRPath) against step runtime context
    - For each match: resolves `definitionCanonical` → `ActionDefinition`, creates `ActionRun`, publishes `IntelligenceTriggerEvent`, updates `deviation.intelligenceEventId`
  - `evaluateOnCompletion(StepInstance step)` → `List<ActionRun>`
    - Same nested loop structure for step completion events
    - Builds context with `stepState=completed`, `completionStatus`, `completedAt`
  - Private pipeline methods (each intelligence action):
    - `conditionMatches(action, context)` — evaluates expression, returns false on error
    - `resolveActionDefinition(action)` — resolves canonical, returns null on error
    - `recordAndPublish(action, step, deviation, definition, triggerReason)` — creates ActionRun (TRIGGERED → PUBLISHED), publishes event, updates deviation
  - Context variables:
    - `stepState` — current step state (e.g., `overdue`, `missed`, `completed`)
    - `deviationType` — `overdue` or `missed` (null for completion)
    - `daysOverdue` — days past due date
    - `daysPastMissedDate` — days past missed date
    - `actionId` — step definition action ID
    - `completionStatus` — `early`, `on_time`, `late` (for completion only)
- [x] Unit tests (13 tests across 3 nested classes):
  - Deviation with matching action → ActionRun created, event published
  - Deviation with non-matching action → no ActionRun
  - Multiple actions — some match, some don't → correct ActionRuns created
  - Missing ActionDefinition (unresolvable canonical) → action skipped, logged
  - Completion with matching action → ActionRun created
  - Context variable verification for both deviation and completion
  - Condition eval error → action skipped
  - Action not found in PlanDefinition → empty result
  - Deviation already has intelligenceEventId → not overwritten

**Files:**
- `src/main/java/.../service/IntelligenceActionEvaluator.java`
- `src/test/java/.../service/IntelligenceActionEvaluatorTest.java`

---

## Subtask I-7: Wire Intelligence into ComplianceEngine & StepInstanceService

**Type:** Task  
**Priority:** Highest  
**Story Points:** 5  
**Labels:** `service`, `core`, `integration`

**Description:**  
Wire the intelligence action evaluator into the existing compliance pipeline. Intelligence actions are evaluated at two trigger points: (1) when a deviation is detected and (2) when a step is completed.

**Acceptance Criteria:**
- [ ] `StepInstanceService` updates:
  - Inject `IntelligenceActionEvaluator`
  - After `createDeviation()` in `applySchedulerTransition()` → call `intelligenceActionEvaluator.evaluateOnDeviation(step, deviation)`
- [ ] `ComplianceEngine` updates:
  - Inject `IntelligenceActionEvaluator`
  - After `stepInstanceService.completeStep()` in `processMatch()` → call `intelligenceActionEvaluator.evaluateOnCompletion(step)`
  - After `stepInstanceService.completeStep()` in `processExplicitMatch()` → call `intelligenceActionEvaluator.evaluateOnCompletion(step)`
- [ ] Existing unit tests updated to account for new dependency injection
- [ ] New tests:
  - End-to-end: event match → step completion → intelligence actions evaluated
  - Scheduler trigger → deviation → intelligence actions evaluated
  - No intelligence actions on step → evaluator called, returns empty

**Files:**
- `src/main/java/.../service/StepInstanceService.java` — updated
- `src/main/java/.../service/ComplianceEngine.java` — updated
- `src/test/java/.../service/StepInstanceServiceTest.java` — updated
- `src/test/java/.../service/ComplianceEngineTest.java` — updated

---

## Subtask I-8: DTOs, DtoMapper & REST Controllers for Action Definitions & Action Runs

**Type:** Task  
**Priority:** High  
**Story Points:** 5  
**Labels:** `web`, `api`

**Description:**  
Implement DTOs, mapper extensions, and REST controllers for managing action definitions and viewing action runs.

**Acceptance Criteria:**
- [ ] **DTOs:**
  - `ActionDefinitionDto` — `id`, `canonicalUrl`, `version`, `canonical`, `name`, `title`, `status`, `actionType`, `severity`, `target`, `definition`, `createdAt`, `updatedAt`
  - `ActionRunDto` — `id`, `actionDefinitionId`, `protocolInstanceId`, `stepInstanceId`, `status`, `intelligenceEventId`, `outputMetadata`, `createdAt`, `updatedAt`
  - `ActionRunContextDto` — `id`, `actionRunId`, `deviationId`, `triggerReason`, `stepActionId`, `evaluationExpression`, `evaluationContext`, `createdAt`
  - `CreateActionDefinitionRequest` — `definitionJson` (String, @NotBlank)
  - `UpdateActionDefinitionRequest` — `definitionJson` (String, @NotBlank)
- [ ] **DtoMapper updates:**
  - `toDto(ActionDefinition)`, `toDto(ActionRun)`, list mapper variants
- [ ] **ActionDefinitionController** — `@RestController`, `@RequestMapping("/v1/compliance/action-definitions")`
  - `POST /` — create action definition
  - `GET /` — list all (filterable by status)
  - `GET /{id}` — get by ID
  - `PUT /{id}` — update definition
  - `POST /{id}/retire` — retire
  - `DELETE /{id}` — delete (fails if action runs exist)
- [ ] **ActionRunController** — `@RestController`, `@RequestMapping("/v1/compliance/action-runs")`
  - `GET /` — list all (filterable by protocolInstanceId, actionDefinitionId, status)
  - `GET /{id}` — get by ID
- [ ] Unit tests (MockMvc):
  - ActionDefinition CRUD operations
  - ActionRun list/get operations
  - Error cases (404, 400, 409)

**Files:**
- `src/main/java/.../web/dto/ActionDefinitionDto.java`
- `src/main/java/.../web/dto/ActionRunDto.java`
- `src/main/java/.../web/dto/CreateActionDefinitionRequest.java`
- `src/main/java/.../web/dto/UpdateActionDefinitionRequest.java`
- `src/main/java/.../web/DtoMapper.java` — updated
- `src/main/java/.../web/controller/ActionDefinitionController.java`
- `src/main/java/.../web/controller/ActionRunController.java`
- `src/test/java/.../web/controller/ActionDefinitionControllerTest.java`
- `src/test/java/.../web/controller/ActionRunControllerTest.java`
- `src/test/java/.../web/DtoMapperTest.java` — updated

---

## Subtask I-9: Observability Updates

**Type:** Task  
**Priority:** Medium  
**Story Points:** 2  
**Labels:** `observability`

**Description:**  
Update metrics and logging for intelligence features.

**Acceptance Criteria:**
- [ ] `ObservabilityConfig` updates:
  - `cce.events.intelligence.published` counter — now actively incremented (remove "future" placeholder)
  - `cce.intelligence.actions.evaluated` counter — total action conditions evaluated
  - `cce.intelligence.actions.fired` counter — actions that matched and triggered
  - `cce.intelligence.publish.duration` timer — time to publish to Kafka
  - `cce.action.definitions.active` gauge — active action definitions count
- [ ] Structured log messages for intelligence pipeline steps
- [ ] MDC context includes `intelligenceEventId` when publishing

**Files:**
- `src/main/java/.../config/ObservabilityConfig.java` — updated
- `src/test/java/.../config/ObservabilityConfigTest.java` — updated

---

## Subtask I-10: Integration Tests

**Type:** Task  
**Priority:** High  
**Story Points:** 5  
**Labels:** `testing`, `integration`

**Description:**  
End-to-end integration tests covering the intelligence pipeline with EmbeddedKafka and H2.

**Acceptance Criteria:**
- [ ] Intelligence action evaluation integration test:
  - Load PlanDefinition with intelligence actions
  - Load corresponding ActionDefinition
  - Send event → match → enroll → complete step
  - Trigger scheduler transition → deviation detected
  - Verify: ActionRun created, IntelligenceTriggerEvent published to Kafka, deviation.intelligenceEventId populated
- [ ] Action Definition API integration test:
  - POST → 201
  - GET list → includes created
  - GET by ID → correct
  - PUT → updated
  - POST retire → status RETIRED
  - DELETE → 204
- [ ] Action Run API integration test:
  - GET list with filters
  - GET by ID
- [ ] Test fixture: PlanDefinition with intelligence actions + matching ActivityDefinition

**Files:**
- `src/integrationTest/java/.../IntelligencePipelineIntegrationTest.java`
- `src/integrationTest/java/.../ActionDefinitionApiIntegrationTest.java`
- `src/integrationTest/resources/fhir/plan-definition-with-intelligence.json`
- `src/integrationTest/resources/fhir/activity-definition-escalation.json`

---

## Subtask I-11: Documentation Finalization & CHANGELOG

**Type:** Task  
**Priority:** High  
**Story Points:** 2  
**Labels:** `documentation`

**Description:**  
Finalize documentation updates with implementation details, update CHANGELOG and copilot-instructions.

**Acceptance Criteria:**
- [ ] CHANGELOG.md — Add `[1.1.0]` section with all intelligence features
- [ ] RELEASE-NOTES.md — Update for v1.1.0
- [ ] `.github/copilot-instructions.md` — Update entity count (9 → 11), enum count, package count, key design patterns with intelligence actions, scope exclusions updated
- [ ] All docs updated with actual class names, method signatures, and test counts

**Files:**
- `CHANGELOG.md`
- `RELEASE-NOTES.md`
- `.github/copilot-instructions.md`
- `docs/*.md` — final pass

---

## Execution Summary

| Subtask | Description | Points | Dependencies |
|---------|-------------|--------|-------------|
| I-0 | Documentation & Design Review | 3 | — |
| I-1 | Flyway V2 Migration | 3 | I-0 |
| I-2 | New Enums & Entities | 5 | I-1 |
| I-3 | PlanDefinitionParser Intelligence Actions | 5 | I-2 |
| I-4 | ActionDefinitionService | 3 | I-2 |
| I-5 | IntelligenceTriggerProducer | 3 | I-2 |
| I-6 | IntelligenceActionEvaluator | 8 | I-3, I-4, I-5 |
| I-7 | Wire into Engine & StepService | 5 | I-6 |
| I-8 | DTOs, Mapper & Controllers | 5 | I-4 |
| I-9 | Observability Updates | 2 | I-5 |
| I-10 | Integration Tests | 5 | I-7, I-8 |
| I-11 | Docs Finalization & CHANGELOG | 2 | I-10 |
| **Total** | | **49** | |
