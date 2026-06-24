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
    participant FacilityRef as FacilityReferenceService
    participant Engine as ComplianceEngine
    participant EventLog as ComplianceEventLogService
    participant TriggerMatch as TriggerMatchingService
    participant Parser as PlanDefinitionParser
    participant ExprEval as ExpressionEvaluationService<br/>(JSONLogic + FHIRPath)
    participant ProtoInst as ProtocolInstanceService
    participant StepInst as StepInstanceService
    participant Audit as AuditService
    participant DB as PostgreSQL

    EHR->>Kafka: Publish clinical event<br/>(CloudEvents v1.0)
    Kafka->>Consumer: Poll cce.events.inbound
    Consumer->>Consumer: Set MDC correlationId

    rect rgb(235, 245, 235)
        Note over Consumer,DB: Facility Registration (best-effort, non-fatal)
        Consumer->>FacilityRef: registerFacilityIfAbsent(cloudEvent)
        FacilityRef->>DB: SELECT EXISTS(facility_id)
        alt Facility not yet known
            FacilityRef->>DB: INSERT INTO facility_reference
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
            Engine->>Engine: extractAllCodes(data)
            Note over Engine: Extracts codes from code, type,<br/>category, clinicalStatus, identifier fields
        end

        rect rgb(248, 240, 255)
            Note over Engine,DB: Step 4 — Tier 1 Structural Match
            Engine->>TriggerMatch: findStructuralMatches(type, system, code)
            TriggerMatch->>DB: SELECT FROM trigger_index<br/>WHERE resource_type AND code
            DB-->>TriggerMatch: List<TriggerIndex>
            TriggerMatch-->>Engine: structural matches
        end

        rect rgb(255, 245, 245)
            Note over Engine,ExprEval: Step 5 — Tier 2 Condition Evaluation
            loop For each structural match
                Engine->>Parser: parseFromMap(definition)
                Parser-->>Engine: PlanDefinition
                Engine->>TriggerMatch: evaluateCondition(action, variables)
                TriggerMatch->>ExprEval: evaluate(language, expression, vars)
                ExprEval-->>TriggerMatch: boolean
                TriggerMatch-->>Engine: condition result
            end
        end

        rect rgb(240, 255, 240)
            Note over Engine,Audit: Step 6 — Process Result
            alt Single Match
                Engine->>ProtoInst: enrollOrGetActive(patientId, planDef)
                ProtoInst->>DB: Find or create ProtocolInstance
                DB-->>ProtoInst: ProtocolInstance
                ProtoInst-->>Engine: protocolInstance

                Engine->>StepInst: createStep(protocol, actionId, ...)
                StepInst->>DB: INSERT INTO step_instance
                Engine->>StepInst: completeStep(stepId, eventLogId, source)
                StepInst->>DB: UPDATE step_instance SET state=COMPLETED

                Note over Engine,DB: Progressive Step Instantiation
                Engine->>Parser: findDependentActions(allActions, actionId)
                Parser-->>Engine: dependent actions
                loop For each dependent action
                    Engine->>Parser: computeRelatedActionOffset(action, actionId)
                    Note over StepInst: Relationship determines base time:<br/>after-end → completedAt, after-start → dueDate
                    Note over StepInst: If TimingInfo.count > 1 → create N recurring<br/>instances with staggered due dates
                    Engine->>StepInst: createDependentSteps(protocol, depAction, base, offset)
                    StepInst->>DB: INSERT INTO step_instance(s) (state=PENDING)
                end

                Engine->>EventLog: updateMatchResult(MATCHED)
                Engine->>Audit: auditSystem("event.processing", "matched", ...)
            else No Matches
                Engine->>EventLog: updateMatchResult(ZERO_MATCH)
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
    participant Validator as FhirResourceValidator
    participant DB as PostgreSQL
    participant Audit as AuditService

    Client->>Controller: POST /v1/protocol-definitions<br/>{ planDefinitionJson: "..." }
    Controller->>Service: loadProtocolDefinition(json)

    Service->>Parser: parse(json)
    Parser->>Parser: FhirContext.parseResource()
    Parser-->>Service: PlanDefinition

    Service->>Parser: validateActionIds(planDefinition)
    Note over Service,Parser: Validates all actionIds are<br/>mandatory (non-blank) and unique

    Service->>Validator: validateOrThrow(planDefinition, "PlanDefinition")
    alt Validation Fails
        Validator-->>Service: throw FhirValidationException
        Service-->>Controller: propagate exception
        Controller-->>Client: 422 Unprocessable Entity
    end

    Service->>Service: Extract url & version
    Service->>DB: existsByUrlAndVersion(url, version)
    alt Already Exists
        DB-->>Service: true
        Service-->>Controller: throw IllegalArgumentException
        Controller-->>Client: 400 Bad Request
    end

    Service->>DB: Save ProtocolDefinitionEntity<br/>(status=ACTIVE, definition=JSONB)
    DB-->>Service: saved entity

    rect rgb(245, 255, 245)
        Note over Service,DB: Build Trigger Index
        Service->>Parser: extractAllActions(planDefinition)
        Parser-->>Service: List<Action>

        loop For each Action
            Service->>Parser: extractTriggers(action)
            loop For each Trigger
                Service->>Parser: extractResourceType(trigger)
                Service->>Parser: extractCodeFilters(trigger)
                loop For each CodeFilter
                    Service->>DB: Save TriggerIndex entry<br/>(resourceType, system, code, protocolDefId, actionId)
                end
            end
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
    participant DB as PostgreSQL

    Scheduler->>Kafka: Publish SchedulerTriggerMessage
    Kafka->>Consumer: Poll cce.scheduler.triggers
    Consumer->>StepSvc: applySchedulerTransition(message)

    StepSvc->>DB: findById(stepInstanceId)
    DB-->>StepSvc: StepInstance

    alt PENDING_TO_DUE
        StepSvc->>StepSvc: Verify state == PENDING
        StepSvc->>DB: UPDATE state = DUE
    else DUE_TO_OVERDUE
        StepSvc->>StepSvc: Verify state == DUE
        StepSvc->>DB: UPDATE state = OVERDUE
        StepSvc->>DevSvc: recordDeviation(OVERDUE)
        DevSvc->>DB: INSERT INTO deviation
    else OVERDUE_TO_MISSED
        StepSvc->>StepSvc: Verify state == OVERDUE
        alt requiredBehavior == could
            StepSvc->>DB: UPDATE state = SKIPPED
            Note right of StepSvc: No deviation for optional steps
        else requiredBehavior == must (or null)
            StepSvc->>DB: UPDATE state = MISSED
            StepSvc->>DevSvc: recordDeviation(MISSED)
            DevSvc->>DB: INSERT INTO deviation
        end
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
    E --> F["Set enrolledAt = now()"]
    F --> G["Link to ProtocolDefinitionEntity"]
    G --> H["Set protocolCanonical = url|version"]

    C --> I["Check for existing<br/>active step"]
    H --> I

    I -->|"Active step exists<br/>for this actionId"| J["Use existing<br/>StepInstance"]
    I -->|"No active step"| K["Create new<br/>StepInstance"]

    K --> L["Calculate repeatIndex"]
    L --> M{"dueDate<br/>provided?"}
    M -->|"Yes"| N["state = PENDING"]
    M -->|"No"| O["state = DUE"]

    J --> P["completeStep()"]
    N --> P
    O --> P

    P --> Q{"Determine<br/>CompletionStatus"}
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
    RD --> D1["Create Deviation entity"]
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
        Note over Evaluator,Parser: Step 1 — Extract intelligence actions
        Evaluator->>DB: Load PlanDefinition from step's protocol
        DB-->>Evaluator: ProtocolDefinition
        Evaluator->>Parser: extractActions(planDefinition)
        Evaluator->>Parser: action.intelligenceActions()
        Parser-->>Evaluator: List<IntelligenceActionInfo>
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
        alt NoSuchElementException
            ExHandler-->>Client: 404 Not Found
        else IllegalArgumentException
            ExHandler-->>Client: 400 Bad Request
        else IllegalStateException
            ExHandler-->>Client: 409 Conflict
        else MethodArgumentNotValidException
            ExHandler-->>Client: 400 + field errors
        else FhirValidationException
            ExHandler-->>Client: 422 + validation errors
        else ExpressionEvaluationException
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
    COMPLETE --> DEPS["createDependentSteps()<br/>(find steps with relatedStep pointing to this actionId)"]
    DEPS --> CREATED["Create dependent steps (PENDING)"]
    CREATED --> CHECK["Check protocol completion"]
```

### Dependent Step Creation on Completion

When any step completes, `createDependentSteps()` finds all steps whose `relatedSteps` reference the completed step's `actionId` and creates them with appropriate due dates.

```mermaid
flowchart TD
    START["createDependentSteps(completedStep, allSteps)"] --> FIND["Find steps with relatedStep → completedStep.actionId"]
    FIND --> LOOP{"For each dependent step"}
    LOOP --> CALC["Calculate due date from offset + relationship<br/>(after-end → completedAt, after-start → dueDate)"]
    CALC --> RECURRING{"TimingInfo.count > 1?"}

    RECURRING -->|"Yes"| MULTI["Create N recurring instances with staggered due dates"]
    RECURRING -->|"No"| SINGLE["createStep(dependent, dueDate)"]

    MULTI --> NEXT["Continue to next"]
    SINGLE --> NEXT
    NEXT --> LOOP
    LOOP -->|"Done"| END["Return"]
```
