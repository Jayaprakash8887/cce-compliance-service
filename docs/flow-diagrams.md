# Flow Diagrams

This document provides detailed sequence and flow diagrams for all major workflows in the CCE Compliance Service.

---

## 1. Inbound Clinical Event Processing (End-to-End)

This is the primary workflow — processing a clinical event from Kafka through the entire compliance engine pipeline.

```mermaid
sequenceDiagram
    autonumber
    participant EHR as EHR System
    participant Kafka as Apache Kafka
    participant Consumer as InboundEventConsumer
    participant Engine as ComplianceEngine
    participant EventLog as EventLogService
    participant TriggerMatch as TriggerMatchingService
    participant Parser as PlanDefinitionParser
    participant ExprEval as ExpressionEvaluationService<br/>(JSONLogic + CQL + FHIRPath)
    participant ProtoInst as ProtocolInstanceService
    participant StepInst as StepInstanceService
    participant Audit as AuditService
    participant DB as PostgreSQL

    EHR->>Kafka: Publish clinical event<br/>(CloudEvents v1.0)
    Kafka->>Consumer: Poll cce.events.inbound
    Consumer->>Consumer: Set MDC correlationId
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
            EventLog->>DB: INSERT INTO event_log
            DB-->>EventLog: EventLog entity
            EventLog-->>Engine: eventLog
        end

        rect rgb(255, 248, 240)
            Note over Engine: Step 3 — Extract Resource Info
            Engine->>Engine: extractResourceType(data)
            Engine->>Engine: extractAllCodes(data)
            Note over Engine: Extracts codes from code, type,<br/>category, clinicalStatus fields
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
                    Engine->>StepInst: createStep(protocol, depActionId, dueDate)
                    StepInst->>DB: INSERT INTO step_instance (state=PENDING)
                end

                Note over Engine,ExprEval: Evaluate Intelligence Rules
                loop For each sub-action (intelligence rules)
                    Engine->>Parser: isIntelligenceRule(subAction)
                    alt Is Intelligence Rule
                        Engine->>ExprEval: evaluate(language, expression, vars)
                        ExprEval-->>Engine: boolean
                    end
                end

                Engine->>EventLog: updateMatchResult(MATCHED)
                Engine->>Audit: auditSystem("event.processing", "matched", ...)
            else Multiple Matches (Ambiguous)
                Engine->>EventLog: updateMatchResult(AMBIGUOUS)
                loop For each match
                    Engine->>Engine: recordDeviation(AMBIGUOUS)
                end
            else No Matches
                Engine->>EventLog: updateMatchResult(ZERO_MATCH)
            end
        end
    end

    Consumer->>Kafka: Acknowledge offset
```

## 2. PlanDefinition Loading Flow

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
    Controller->>Service: loadPlanDefinition(json)

    Service->>Parser: parse(json)
    Parser->>Parser: FhirContext.parseResource()
    Parser-->>Service: PlanDefinition

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

    Service->>DB: Save PlanDefinitionEntity<br/>(status=ACTIVE, definition=JSONB)
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
                    Service->>DB: Save TriggerIndex entry<br/>(resourceType, system, code, planDefId, actionId)
                end
            end
        end
    end

    Service->>Audit: auditSystem("protocol.definition", "loaded", ...)
    Service-->>Controller: PlanDefinitionEntity
    Controller-->>Client: 201 Created + PlanDefinitionDto
```

## 3. Scheduler-Driven State Transitions

```mermaid
sequenceDiagram
    autonumber
    participant Scheduler as CCE Scheduler Service
    participant Kafka as Apache Kafka
    participant Consumer as SchedulerTriggerConsumer
    participant StepSvc as StepInstanceService
    participant DevSvc as DeviationService
    participant Producer as IntelligenceTriggerProducer
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
        DevSvc->>Producer: publishTrigger(IntelligenceTriggerEvent)
        Producer->>Kafka: Send to cce.intelligence.triggers
    else OVERDUE_TO_MISSED
        StepSvc->>StepSvc: Verify state == OVERDUE
        StepSvc->>DB: UPDATE state = MISSED
        StepSvc->>DevSvc: recordDeviation(MISSED)
        DevSvc->>DB: INSERT INTO deviation
        DevSvc->>Producer: publishTrigger(IntelligenceTriggerEvent)
        Producer->>Kafka: Send to cce.intelligence.triggers
    end

    Consumer->>Kafka: Acknowledge offset
```

## 4. Protocol Enrollment Flow

```mermaid
flowchart TD
    A["Inbound Event<br/>Matched to PlanDefinition Action"] --> B{"Patient has<br/>active protocol?"}

    B -->|"Yes"| C["Return existing<br/>ProtocolInstance"]
    B -->|"No"| D["Create new<br/>ProtocolInstance"]

    D --> E["Set status = ACTIVE"]
    E --> F["Set enrolledAt = now()"]
    F --> G["Link to PlanDefinitionEntity"]
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

    V --> W["Set matchedEventId"]
    W --> X["Update Event Log<br/>matchedStepInstanceId"]
```

## 5. Deviation Detection & Intelligence Publishing

```mermaid
flowchart TD
    subgraph "Deviation Triggers"
        T1["Scheduler: DUE → OVERDUE"]
        T2["Scheduler: OVERDUE → MISSED"]
        T3["Engine: Ambiguous Match"]
    end

    T1 -->|"type=OVERDUE"| RD
    T2 -->|"type=MISSED"| RD
    T3 -->|"type=AMBIGUOUS"| RD

    RD["DeviationService.recordDeviation()"]
    RD --> D1["Create Deviation entity"]
    D1 --> D2["Set deviationType"]
    D2 --> D3["Set detectedAt = now()"]
    D3 --> D4["Link to ProtocolInstance + StepInstance"]
    D4 --> D5["Persist to DB"]

    D5 --> PUB["publishIntelligenceTrigger()"]
    PUB --> E1["Build IntelligenceTriggerEvent"]
    E1 --> E2["Set type = cce.compliance.deviation.{type}"]
    E2 --> E3["Include context:<br/>protocolInstanceId, stepInstanceId,<br/>deviationId, actionId, patientId,<br/>facilityId, protocolCanonical"]
    E3 --> E4["IntelligenceTriggerProducer.publishTrigger()"]
    E4 --> KAFKA["Kafka: cce.intelligence.triggers<br/>key = protocolInstanceId"]
```

## 6. Dead Letter Processing Flow

```mermaid
flowchart TD
    A["Event Processing Error"] --> B["Catch Exception in ComplianceEngine"]
    B --> C["DeadLetterProducer.publishDeadLetter()"]

    C --> D["Build dead-letter envelope"]
    D --> E["Set failureReason = exception message"]
    E --> F["Set failureStage = PROCESSING"]
    F --> G["Set correlationId"]
    G --> H["Kafka: cce.deadletter<br/>key = correlationId"]

    subgraph "Downstream (Collector Service)"
        H --> I["Collector consumes dead-letter event"]
        I --> J["Persist to dead_letter_event table"]
        J --> K["Retry / Manual Resolution"]
    end
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

## 8. PlanDefinition Retirement Flow

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Controller as ProtocolDefinitionController
    participant Service as ProtocolDefinitionService
    participant DB as PostgreSQL
    participant Audit as AuditService

    Client->>Controller: POST /v1/protocol-definitions/{id}/retire
    Controller->>Service: retirePlanDefinition(url, version)
    Service->>DB: findByUrlAndVersion(url, version)

    alt Not Found
        DB-->>Service: empty
        Service-->>Controller: throw NoSuchElementException
        Controller-->>Client: 404 Not Found
    end

    DB-->>Service: PlanDefinitionEntity
    Service->>Service: Set status = RETIRED
    Service->>DB: Save entity
    Service->>DB: DELETE FROM trigger_index WHERE plan_definition_id = :id
    Service->>Audit: auditSystem("protocol.definition", "retired", ...)
    Service-->>Controller: updated entity
    Controller-->>Client: 200 OK + PlanDefinitionDto
```

## 9. Patient Protocol Tracking Query

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Controller as ProtocolTrackingController
    participant ProtoSvc as ProtocolInstanceService
    participant StepRepo as StepInstanceRepository
    participant DevRepo as DeviationRepository
    participant Mapper as DtoMapper
    participant DB as PostgreSQL

    Client->>Controller: GET /v1/patients/{patientId}/protocol-tracking/active
    Controller->>ProtoSvc: findActiveByPatientId(patientId)
    ProtoSvc->>DB: JPQL: WHERE patientId AND status=ACTIVE
    DB-->>ProtoSvc: List<ProtocolInstance>
    ProtoSvc-->>Controller: protocols

    Controller->>Mapper: toProtocolInstanceDtoList(protocols)
    Note over Mapper: Children (steps, deviations) excluded in list view
    Mapper-->>Controller: List<ProtocolInstanceDto>
    Controller-->>Client: 200 OK

    Note over Client: Client drills into specific protocol

    Client->>Controller: GET /v1/patients/{patientId}/protocol-tracking/{protocolId}
    Controller->>ProtoSvc: findById(protocolId)
    ProtoSvc->>DB: SELECT by ID
    DB-->>ProtoSvc: ProtocolInstance
    Controller->>Mapper: toProtocolInstanceDto(protocol, includeChildren=true)
    Note over Mapper: Includes steps + deviations
    Mapper-->>Controller: ProtocolInstanceDto
    Controller-->>Client: 200 OK
```

## 10. Kafka Consumer Error Handling

```mermaid
flowchart TD
    A["Kafka delivers message"] --> B["Consumer receives message"]
    B --> C{"Deserialization OK?"}
    C -->|"No"| D["ErrorHandlingDeserializer<br/>wraps error"]
    D --> E["Log error + skip"]

    C -->|"Yes"| F["Set MDC correlationId"]
    F --> G["Delegate to service"]
    G --> H{"Processing OK?"}
    H -->|"Yes"| I["Acknowledge offset"]
    H -->|"No"| J["Log error"]
    J --> K["Increment error counter"]
    K --> L["DO NOT Acknowledge"]
    L --> M["Kafka redelivers<br/>(at next poll)"]
```
