# Flow Diagrams

This document provides detailed sequence and flow diagrams for all major workflows in the CCE Compliance Service.

---

## 1. Inbound Clinical Event Processing (End-to-End)

This is the primary workflow — processing a clinical event from Kafka through the entire compliance engine pipeline.

```mermaid
sequenceDiagram
    autonumber
    participant EHR as CCE Collector Service
    participant Kafka as Apache Kafka
    participant Consumer as InboundEventConsumer
    participant FacilityRef as FacilityService
    participant Engine as ComplianceEngine
    participant EventLog as ComplianceEventLogService
    participant TriggerMatch as TriggerMatchingService
    participant Parser as PlanDefinitionParser
    participant ExprEval as ExpressionEvaluationService<br/>(JSONLogic + FHIRPath)
    participant ProtoInst as ProtocolInstanceService
    participant StepInst as StepInstanceService
    participant Intel as IntelligenceActionEvaluator
    participant Audit as AuditService
    participant DB as PostgreSQL

    EHR->>Kafka: Publish clinical event<br/>(CloudEvents v1.0)
    Kafka->>Consumer: Poll cce.events.inbound
    Consumer->>Consumer: Set MDC correlationId

    rect rgb(235, 245, 235)
        Note over Consumer,DB: Facility Registration (best-effort, non-fatal)
        Consumer->>FacilityRef: upsertFacility(cloudEvent)
        FacilityRef->>DB: SELECT facility by facility_id
        alt Facility not yet known
            FacilityRef->>DB: INSERT INTO facility
        else Facility name changed
            FacilityRef->>DB: UPDATE facility SET facility_name
        end
        Note over Consumer,FacilityRef: Failure is swallowed — compliance<br/>processing continues regardless
    end

    Consumer->>Engine: processInboundEvent(cloudEvent)

    rect rgb(240, 248, 255)
        Note over Engine,DB: Step 1 — Idempotency Check
        Engine->>EventLog: isDuplicate(cloudeventsId, source)
        EventLog->>DB: SELECT EXISTS(cloudeventsId, source)
        DB-->>EventLog: true/false
        EventLog-->>Engine: isDuplicate result
    end

    alt Duplicate Event
        Engine-->>Consumer: Skip (increment duplicate counter)
    else New Event
        rect rgb(245, 255, 245)
            Note over Engine,DB: Step 2 — Record Event
            Engine->>EventLog: recordEvent(cloudEvent, ZERO_MATCH)
            EventLog->>DB: INSERT INTO compliance_event_log
            DB-->>EventLog: ComplianceEventLog entity
            EventLog-->>Engine: eventLog
        end

        rect rgb(255, 248, 240)
            Note over Engine: Step 3 — Extract Resource Info
            Engine->>Engine: extractResourceType(data)
            Engine->>Engine: extractCodes(data)
            Note over Engine: Extracts codes from code, type,<br/>category, clinicalStatus, identifier fields
        end

        rect rgb(248, 240, 255)
            Note over Engine,DB: Step 4 — Tier 1 Structural Match
            Engine->>TriggerMatch: findStructuralMatches(resourceType, codes)
            TriggerMatch->>DB: SELECT FROM trigger_index<br/>WHERE resource_type AND code (GROUP BY + HAVING)
            DB-->>TriggerMatch: List<MatchedStep>
            TriggerMatch-->>Engine: structural matches
        end

        rect rgb(255, 245, 245)
            Note over Engine,ExprEval: Step 5 — Tier 2 Condition Evaluation
            loop For each structural match
                Engine->>Parser: extractSteps(planDefinition)<br/>(cached per protocolDefinitionId for this event)
                Parser-->>Engine: List<StepMetadata>
                alt Action's trigger has no condition
                    Engine->>Engine: Accept match as-is
                else Trigger has a condition
                    Engine->>ExprEval: evaluate(language, expression, eventData)
                    ExprEval-->>Engine: boolean
                end
            end
            Note over Engine,ExprEval: Condition-only triggers (no data[], scenario F3)<br/>are also evaluated here for every inbound event
            Engine->>TriggerMatch: getConditionOnlyTriggers()
            TriggerMatch-->>Engine: List<ConditionOnlyTrigger>
            loop For each condition-only trigger
                Engine->>ExprEval: evaluate(language, expression, eventData)
                ExprEval-->>Engine: boolean
            end
        end

        rect rgb(240, 255, 240)
            Note over Engine,Audit: Step 6 — Process Result
            alt One or more matches
                loop For each matched (protocolDefinitionId, actionId)
                    Engine->>ProtoInst: enrollPatient(patientId, protocolDef, occurredAt)
                    Note over ProtoInst: Idempotent — returns the existing ACTIVE<br/>instance if the patient is already enrolled
                    ProtoInst->>DB: Find or create ProtocolInstance
                    DB-->>ProtoInst: ProtocolInstance
                    ProtoInst-->>Engine: protocolInstance

                    Engine->>Engine: resolveOccurredAt(event)<br/>(clinical time from payload → envelope time → now)
                    Engine->>StepInst: findActionableStep(protocolInstanceId, actionId)
                    alt No actionable step exists yet
                        Engine->>StepInst: createStep(protocol, actionId, 0, now, ...)
                        StepInst->>DB: INSERT INTO step_instance (state=PENDING)
                    end

                    Engine->>StepInst: completeStep(step, eventLogId, source, occurredAt)
                    Note over StepInst: Single call — internally sets completed_at (clinical time,<br/>clamped to now), detects order violations, runs progressive<br/>instantiation of mandatory dependents (see §9), auto-skips<br/>preceding optional (could) steps, backfills unrecorded<br/>mandatory predecessors as PENDING (see §9), then calls<br/>checkAndCompleteProtocol (see §9 "Protocol Completion Check")
                    StepInst->>DB: UPDATE step_instance SET state=COMPLETED

                    Engine->>Intel: evaluateOnCompletion(step, eventPayload)

                    Engine->>Audit: auditSystem("event.processing", "matched", ...)
                end
                Engine->>EventLog: updateStatus(eventLog, MATCHED)
            else No Matches
                Note over Engine,EventLog: eventLog was already recorded with ZERO_MATCH<br/>in Step 2 — no further status update needed
            end
        end
    end

    Consumer->>Kafka: Acknowledge offset
```

## 2. Protocol Definition Loading Flow

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Controller as ProtocolDefinitionController
    participant Service as ProtocolDefinitionService
    participant Parser as PlanDefinitionParser
    participant TriggerMatch as TriggerMatchingService
    participant DB as PostgreSQL
    participant Audit as AuditService

    Client->>Controller: POST /v1/compliance/protocol-definitions<br/>{ planDefinitionJson: "..." }
    Controller->>Service: loadProtocol(planDefinitionJson)

    Service->>Parser: parse(json)
    Parser->>Parser: FhirContext.parseResource()<br/>(StrictErrorHandler)
    alt Malformed FHIR JSON
        Parser-->>Service: throw DataFormatException
        Service-->>Controller: propagate exception
        Controller-->>Client: 422 Unprocessable Entity
    end
    Parser-->>Service: PlanDefinition

    Service->>Parser: validateActionIds(planDefinition)
    Note over Service,Parser: Validates all actionIds (incl. nested sub-steps)<br/>are non-blank and unique
    Service->>Parser: validateActionTypes(planDefinition)
    Note over Service,Parser: Validates every action declares an explicit<br/>type coding: 'step' or 'fire-event'
    Service->>Parser: validateTriggers(planDefinition)
    Note over Service,Parser: Validates every trigger has data[] and/or a condition
    alt Any validation fails
        Parser-->>Service: throw IllegalArgumentException
        Service-->>Controller: propagate exception
        Controller-->>Client: 400 Bad Request
    end

    Service->>Service: Extract url & version
    Service->>DB: findByUrlAndVersion(url, version)
    alt Already Exists
        DB-->>Service: present
        Service-->>Controller: throw IllegalArgumentException
        Controller-->>Client: 400 Bad Request
    end

    Service->>DB: Save ProtocolDefinitionEntity<br/>(status=ACTIVE, definition=JSONB)
    DB-->>Service: saved entity

    rect rgb(245, 255, 245)
        Note over Service,DB: Build Trigger Index
        Service->>Parser: buildTriggerIndexEntries(planDefinition, protocolDefId)
        Note over Parser: Decomposes each action's trigger data[].codeFilter[]<br/>into individual rows, recursing into nested sub-step actions
        Parser-->>Service: List<TriggerIndex>
        Service->>DB: saveAll(indexEntries)
    end

    rect rgb(255, 248, 240)
        Note over Service,TriggerMatch: Register Condition-Only Triggers
        Service->>Parser: extractConditionOnlyTriggers(planDefinition)
        Note over Parser: Triggers with no data[], only a condition —<br/>held in-memory, evaluated via Tier 2 for every inbound event
        Parser-->>Service: List<ConditionOnlyTriggerInfo>
        opt Any found
            Service->>TriggerMatch: registerConditionOnlyTriggers(protocolDefId, triggers)
        end
    end

    Service->>Audit: auditSystem("protocol.definition", "loaded", ...)
    Service-->>Controller: ProtocolDefinitionEntity
    Controller-->>Client: 201 Created + ProtocolDefinitionDto
```

## 3. Scheduler-Driven State Transitions

> The Scheduler Service polls `step_instance` for time-threshold crossings and publishes trigger messages to Kafka. See [Architecture Overview §1.1](architecture-overview.md#11-scheduler-service-contract) for the polling query, lease mechanism, and ownership boundaries. The diagram below shows the Compliance Service side — receiving and processing those triggers.

```mermaid
sequenceDiagram
    autonumber
    participant Scheduler as CCE Scheduler Service
    participant Kafka as Apache Kafka
    participant Consumer as SchedulerTriggerConsumer
    participant StepSvc as StepInstanceService
    participant DevSvc as DeviationService
    participant ProtoInst as ProtocolInstanceService
    participant DB as PostgreSQL

    Scheduler->>Kafka: Publish SchedulerTriggerMessage
    Kafka->>Consumer: Poll cce.scheduler.triggers
    Consumer->>StepSvc: applySchedulerTransition(message)

    StepSvc->>DB: findById(stepInstanceId)
    DB-->>StepSvc: StepInstance

    Note over StepSvc: applyTransition returns true only if the step is<br/>in the expected source state. A redelivered trigger finds<br/>the step already transitioned → returns false → deviation skipped.

    alt PENDING_TO_DUE
        StepSvc->>StepSvc: applyTransition(PENDING → DUE)
        StepSvc->>DB: UPDATE state = DUE (if applied)
    else DUE_TO_OVERDUE
        StepSvc->>StepSvc: applyTransition(DUE → OVERDUE)
        opt transition applied
            StepSvc->>DB: UPDATE state = OVERDUE
            StepSvc->>DevSvc: createDeviation(OVERDUE)
            DevSvc->>DB: SELECT existing (step, OVERDUE)
            Note right of DevSvc: Insert only if none exists —<br/>unique constraint (step_instance_id, deviation_type)<br/>is the backstop against concurrent inserts
            DevSvc->>DB: INSERT INTO deviation
        end
    else OVERDUE_TO_MISSED
        alt requiredBehavior == could
            StepSvc->>StepSvc: applyTransition(OVERDUE → SKIPPED)
            Note right of StepSvc: No deviation for optional steps
        else requiredBehavior == must (or null)
            StepSvc->>StepSvc: applyTransition(OVERDUE → MISSED)
            opt transition applied
                StepSvc->>DB: UPDATE state = MISSED
                StepSvc->>DevSvc: createDeviation(MISSED)
                DevSvc->>DB: INSERT INTO deviation (idempotent)
            end
        end
        Note over StepSvc,ProtoInst: MISSED and SKIPPED are both terminal, so completion is<br/>checked unconditionally here — even if the transition above<br/>was a no-op (redelivered trigger, step already terminal)
        StepSvc->>ProtoInst: checkAndCompleteProtocol(protocolInstanceId)
    end

    Consumer->>Kafka: Acknowledge offset
```

## 4. Protocol Enrollment Flow

```mermaid
flowchart TD
    A["Inbound Event<br/>Matched to Protocol Definition Action"] --> B{"Patient has<br/>active protocol?"}

    B -->|"Yes"| C["Return existing<br/>ProtocolInstance"]
    B -->|"No"| D["Create new<br/>ProtocolInstance"]

    D --> E["Set status = ACTIVE"]
    E --> F["Set enrolledAt = clinical occurrence time<br/>(resolveOccurredAt: payload → envelope → now)"]
    F --> G["Link to ProtocolDefinitionEntity"]
    G --> H["Set protocolCanonical = url|version"]

    C --> I["Check for existing<br/>active step"]
    H --> I

    I -->|"Active step exists<br/>for this actionId"| J["Use existing<br/>StepInstance"]
    I -->|"No active step"| K["Create new<br/>StepInstance"]

    K --> L["Calculate repeatIndex"]
    L --> N["Create StepInstance — state = PENDING<br/>(createStep always starts PENDING; the<br/>PENDING → DUE transition is scheduler-driven, see §3)"]

    J --> P["completeStep()"]
    N --> P

    P --> Q{"Determine CompletionStatus<br/>(completedAt = clinical occurrence time)"}
    Q -->|"completedAt < dueDate"| R["EARLY"]
    Q -->|"dueDate ≤ completedAt ≤ overdueDate"| S["ON_TIME"]
    Q -->|"completedAt > overdueDate"| T["LATE"]
    Q -->|"No dueDate"| U["ON_TIME (default)"]

    R --> V["Set state = COMPLETED"]
    S --> V
    T --> V
    U --> V

    V --> W["Set completedByEventId"]
    W --> X["Update Compliance Event Log"]
```

## 5. Deviation Detection & Recording

> **Intelligence action evaluation** is triggered after each deviation is recorded. See §6 for the full intelligence pipeline flow.

```mermaid
flowchart TD
    subgraph "Deviation Triggers"
        T1["Scheduler: DUE → OVERDUE"]
        T2["Scheduler: OVERDUE → MISSED"]
    end

    T1 -->|"type=OVERDUE"| RD
    T2 -->|"type=MISSED"| RD

    RD["DeviationService.recordDeviation()"]
    RD --> DX{"Deviation of this type<br/>already exists for step?"}
    DX -->|"Yes (redelivery / concurrent)"| DXR["Return DeviationResult(existing, created=false)<br/>— no insert, no audit, caller skips intelligence eval"]
    DX -->|"No"| D1["Create Deviation entity"]
    D1 --> D2["Set deviationType"]
    D2 --> D3["Set detectedAt = now()"]
    D3 --> D4["Build metadata:<br/>daysOverdue/daysPastMissedDate"]
    D4 --> D5["Link to ProtocolInstance + StepInstance"]
    D5 --> D6["Persist to DB"]
    D6 --> D7["Audit: DEVIATION_DETECTED"]
    D7 --> D8["Evaluate intelligence actions<br/>(IntelligenceActionEvaluator)"]
```

## 6. Intelligence Action Evaluation & Trigger Publishing

This flow is triggered after a deviation is detected (OVERDUE/MISSED) or after a step is completed. The `IntelligenceActionEvaluator` evaluates PlanDefinition intelligence action conditions and publishes intelligence events.

```mermaid
sequenceDiagram
    autonumber
    participant Trigger as Deviation Detection /<br/>Step Completion
    participant Evaluator as IntelligenceActionEvaluator
    participant Parser as PlanDefinitionParser
    participant ExprEval as ExpressionEvaluationService
    participant ActionDefSvc as ActionDefinitionService
    participant Producer as IntelligenceTriggerProducer
    participant Kafka as Apache Kafka
    participant DB as PostgreSQL

    Trigger->>Evaluator: evaluateOnDeviation(step, deviation)<br/>or evaluateOnCompletion(step)

    rect rgb(240, 248, 255)
        Note over Evaluator,Parser: Step 1 — Resolve plan definition & find intelligence actions
        Evaluator->>Evaluator: getCachedPlanDefinition(protocolDef)<br/>(in-memory cache keyed by protocolDefinitionId)
        alt Cache miss
            Evaluator->>Parser: parse(definition)
            Parser-->>Evaluator: PlanDefinition
        end
        Evaluator->>Parser: extractSteps(planDefinition)
        Parser-->>Evaluator: List<StepMetadata>
        Evaluator->>Evaluator: Match StepMetadata by step.actionId,<br/>read its intelligenceActions()
    end

    rect rgb(245, 255, 245)
        Note over Evaluator,ExprEval: Step 2 — Build context & evaluate conditions
        Evaluator->>Evaluator: Build runtime context<br/>(stepState, deviationType, daysOverdue,<br/>completionStatus, actionId, repeatIndex)

        loop For each intelligence action
            Evaluator->>ExprEval: evaluate(action.language,<br/>action.expression, context)
            ExprEval-->>Evaluator: boolean

            alt Condition is true
                rect rgb(255, 248, 240)
                    Note over Evaluator,Kafka: Step 3 — Resolve, record, publish
                    Evaluator->>ActionDefSvc: resolveByCanonical(action.definitionCanonical)
                    ActionDefSvc->>DB: SELECT FROM action_definition
                    DB-->>ActionDefSvc: ActionDefinition

                    alt ActionDefinition not found
                        ActionDefSvc-->>Evaluator: null
                        Evaluator->>Evaluator: Log warning, skip action
                    else ActionDefinition found
                        ActionDefSvc-->>Evaluator: ActionDefinition
                        Evaluator->>DB: INSERT IntelligenceEventLog<br/>(published=false,<br/>eventPayload, triggerReason,<br/>stepActionId, evaluationExpression,<br/>evaluationContext)
                        DB-->>Evaluator: IntelligenceEventLog

                        Evaluator->>Evaluator: Build IntelligenceTriggerEvent
                        Evaluator->>Producer: publish(event)
                        Producer->>Kafka: Send to cce.intelligence.triggers<br/>(key: protocolInstanceId)
                        Kafka-->>Producer: Ack

                        Evaluator->>DB: UPDATE IntelligenceEventLog<br/>(published=true,<br/>publishedAt=now())
                        Evaluator->>DB: UPDATE deviation<br/>(intelligenceEventId=UUID)
                    end
                end
            else Condition is false
                Note over Evaluator: Skip action
            end
        end
    end

    Evaluator-->>Trigger: List<IntelligenceEventLog>
```

### Intelligence Event Content Assembly

```mermaid
flowchart TD
    subgraph "Input Sources"
        STEP["StepInstance<br/>(state, actionId, dueDate, completedAt)"]
        DEV["Deviation<br/>(deviationType, detectedAt, metadata)"]
        PI["ProtocolInstance<br/>(patientId, protocolCanonical, facilityId)"]
        RULE["IntelligenceActionInfo<br/>(actionId, definitionCanonical, severity, intelligenceDestination)"]
        ACTDEF["ActionDefinition<br/>(actionType, title)"]
    end

    subgraph "IntelligenceTriggerEvent"
        E_ID["id: UUID (new)"]
        E_TYPE["type: cce.compliance.deviation.overdue"]
        E_SUBJECT["subject: patientId"]
        E_PI["protocolInstanceId"]
        E_SI["stepInstanceId"]
        E_DI["deviationId"]
        E_DT["deviationType: overdue"]
        E_SS["stepState: overdue"]
        E_AID["actionId: anc-visit-2"]
        E_PC["protocolCanonical: url|version"]
        E_FID["facilityId: 0002"]
        E_DAT["detectedAt: timestamp"]
        E_META["metadata: {...}"]
    end

    STEP --> E_SS
    STEP --> E_AID
    STEP --> E_SI
    DEV --> E_DI
    DEV --> E_DT
    DEV --> E_DAT
    DEV --> E_META
    PI --> E_SUBJECT
    PI --> E_PI
    PI --> E_PC
    PI --> E_FID
    RULE --> E_TYPE
    ACTDEF --> E_META
```

## 7. REST API Request Flow

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Security as SecurityConfig<br/>(JWT Filter)
    participant Controller as REST Controller
    participant Service as Service Layer
    participant DB as PostgreSQL
    participant Mapper as DtoMapper
    participant ExHandler as GlobalExceptionHandler

    Client->>Security: HTTP Request + Bearer JWT
    Security->>Security: Validate JWT signature<br/>Extract scopes
    alt Invalid Token
        Security-->>Client: 401 Unauthorized
    end
    alt Insufficient Scope
        Security-->>Client: 403 Forbidden
    end

    Security->>Controller: Authenticated request

    alt Normal Flow
        Controller->>Service: Business operation
        Service->>DB: Query/Mutate
        DB-->>Service: Result
        Service-->>Controller: Entity/List
        Controller->>Mapper: toDto(entity)
        Mapper-->>Controller: DTO
        Controller-->>Client: 200 OK / 201 Created
    else Error Flow
        Controller->>Service: Business operation
        Service-->>Controller: throw Exception
        Controller->>ExHandler: Exception propagation
        alt EntityNotFoundException
            ExHandler-->>Client: 404 Not Found
        else IllegalArgumentException
            ExHandler-->>Client: 400 Bad Request
        else IllegalStateException
            ExHandler-->>Client: 409 Conflict
        else MethodArgumentNotValidException
            ExHandler-->>Client: 400 + field errors
        else DataFormatException<br/>(malformed FHIR JSON)
            ExHandler-->>Client: 422 + parse error
        else UnsupportedExpressionLanguageException
            ExHandler-->>Client: 422 + expression error
        else Exception
            ExHandler-->>Client: 500 Internal Server Error
        end
    end
```

## 8. Kafka Consumer Error Handling

```mermaid
flowchart TD
    A["Kafka delivers message"] --> B["Consumer receives message"]
    B --> C{"Deserialization OK?"}
    C -->|"No"| D["ErrorHandlingDeserializer<br/>wraps error"]
    D --> D2["Route to DLQ"]

    C -->|"Yes"| F["Set MDC correlationId"]
    F --> G["Delegate to service"]
    G --> H{"Processing OK?"}
    H -->|"Yes"| I["Acknowledge offset"]
    H -->|"No"| J["Increment error counter"]
    J --> K{"Retries remaining?<br/>(default: 3)"}
    K -->|"Yes"| L["Wait backoff (1s)"]
    L --> G
    K -->|"No"| M["Publish to &lt;topic&gt;.dlq"]
    M --> N["Acknowledge original offset"]
    N --> O["Log DLQ routing"]
```

## 9. Flat Sub-Step Processing

All steps (including those originally nested in `action.action[]`) are treated as peers. Nested step-type actions are flattened at parse time with `relatedSteps` linking them to their parent. No separate sub-step routing is needed — the standard matching and completion flow handles them uniformly.

```mermaid
flowchart TD
    MATCH["Tier 1/2 match returns actionId"] --> NORMAL["Standard step processing<br/>(Section 1, Step 6)"]
    NORMAL --> COMPLETE["completeStep(step)"]
    COMPLETE --> DEPS["createDependentSteps()<br/>(find steps with relatedStep pointing to this step id)"]
    DEPS --> CREATED["Create dependent steps (PENDING)"]
    CREATED --> BACKFILL["backfillMissingMandatorySteps()<br/>(see 'Unrecorded Mandatory Predecessor Backfill' below)"]
    BACKFILL --> CHECK["Check protocol completion<br/>(see 'Protocol Completion Check' below)"]
```

### Dependent Step Creation on Completion

When any step completes, `createDependentSteps()` finds all steps whose `relatedSteps` reference the completed step's id and creates them with appropriate due dates.

```mermaid
flowchart TD
    START["createDependentSteps(completedStep, allSteps)"] --> FIND["Find steps with relatedStep → the completed step's id"]
    FIND --> LOOP{"For each dependent step"}
    LOOP --> DEDUP{"An instance for this step<br/>already exists?"}
    DEDUP -->|"Yes"| SKIP["Skip — avoid duplicate<br/>(already created reactively via its own<br/>trigger, or by a redelivered predecessor)"]
    DEDUP -->|"No"| MUST{"target step's<br/>requiredBehavior == must?"}
    MUST -->|"No (could / unspecified)"| SKIP2["Skip pre-creation — a dangling PENDING row could<br/>later go OVERDUE/MISSED even though its event never<br/>arrives; created on the fly if its own trigger fires"]
    MUST -->|"Yes"| CALC["Calculate due date from offset + relationship<br/>(after-end → completedAt [clinical time], after-start → dueDate)"]
    CALC --> RECURRING{"TimingInfo.count > 1?"}

    RECURRING -->|"Yes"| MULTI["Create N recurring instances with staggered due dates"]
    RECURRING -->|"No"| SINGLE["createStep(dependent, dueDate) — state=PENDING"]

    MULTI --> NEXT["Continue to next"]
    SINGLE --> NEXT
    SKIP --> NEXT
    SKIP2 --> NEXT
    NEXT --> LOOP
    LOOP -->|"Done"| END["Return"]
```

### Unrecorded Mandatory Predecessor Backfill (backfillMissingMandatorySteps)

Progressive instantiation only works *forward*, so a step created reactively from its own trigger leaves the mandatory steps that should have preceded it with no `step_instance` row — invisible both in the journey view ("not started") and to the Scheduler. After every completion, mandatory predecessors of the observed progress that have no row are materialized as `PENDING`. See `architecture-overview.md` §6.1 for the full rationale.

```mermaid
flowchart TD
    START["backfillMissingMandatorySteps(completedStep, allSteps)"] --> OBSERVED["Collect observed step ids<br/>(all step_instance rows of this protocol instance)"]
    OBSERVED --> PRED["computeMustPredecessorSteps():<br/>transitive relatedAction ancestors of every observed step,<br/>restricted to requiredBehavior == must"]
    PRED --> MISSING{"Any with no<br/>step_instance row?"}
    MISSING -->|"No"| DONE["Return — nothing to backfill"]
    MISSING -->|"Yes"| LOOP{"For each missing<br/>mandatory step"}
    LOOP --> DATES["due_date = completedStep.completed_at (clinical time)<br/>overdue/missed = due + tolerance-days (null if unset)"]
    DATES --> CREATE["createStep(stepId, repeatIndex 0) — state=PENDING"]
    CREATE --> LOOP
    LOOP -->|"Done"| DONE2["Scheduler now sees the rows:<br/>PENDING → DUE → OVERDUE → MISSED (deviations),<br/>or a late event completes them (LATE)"]
```

> Steps still **ahead** in the chain are deliberately excluded — backfilling them would stamp them with this completion's time and flatten the schedule their own `relatedAction` offsets define. They are left to progressive instantiation; the wider `computeExpectedMustSteps` set still gates protocol completion.

### Protocol Completion Check (checkAndCompleteProtocol)

Called at the end of `completeStep()` and, unconditionally, from the `OVERDUE_TO_MISSED` scheduler transition (§3). A protocol instance only moves to `COMPLETED` once no materialized step is still actionable **and** every mandatory ("must") action that the observed progress implies is satisfied — where "implied" covers three cases: the observed action itself, its transitive `relatedAction` predecessors (ancestors), and any mandatory action nested under the same top-level PlanDefinition action (a "group sibling"), even one whose own trigger never fired and so was never materialized as a step_instance row.

```mermaid
flowchart TD
    START["checkAndCompleteProtocol(instanceId)"] --> ACTIVE{"instance.status == ACTIVE?"}
    ACTIVE -->|"No"| RETURN1["Return — nothing to do"]
    ACTIVE -->|"Yes"| LOAD["Load materialized step_instance rows"]
    LOAD --> EMPTY{"Any steps materialized?"}
    EMPTY -->|"No"| RETURN2["Return"]
    EMPTY -->|"Yes"| ACTIONABLE{"Any step still<br/>PENDING/DUE/OVERDUE?"}
    ACTIONABLE -->|"Yes"| RETURN3["Return — outstanding work"]
    ACTIONABLE -->|"No"| EXPECT["computeExpectedMustSteps:<br/>for every observed stepId, union of<br/>{itself, ancestors, must-group siblings}<br/>restricted to requiredBehavior == must"]
    EXPECT --> CHECK{"Every expected must-action<br/>has a terminal step<br/>(COMPLETED/MISSED/SKIPPED)?"}
    CHECK -->|"No"| RETURN4["Return — log outstanding<br/>mandatory actionIds"]
    CHECK -->|"Yes"| COMPLETE["Set status = COMPLETED<br/>Record state-transition history"]
```
