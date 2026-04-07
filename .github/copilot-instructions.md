# CCE Compliance Service — AI Agent Instructions

## Architecture

Spring Boot 3.4.2 / Java 21 microservice that tracks patient adherence to FHIR R4 `PlanDefinition` clinical protocols. Layered architecture: `web/` → `service/` → `domain/` + `fhir/` + `kafka/`. The central orchestrator is `ComplianceEngine` (~307 lines) — all inbound event processing flows through it.

**Core pipeline:** Kafka event → idempotency check → extract resource info from payload → Tier 1 structural match (trigger_index GROUP BY + HAVING) + condition-only triggers (in-memory) → Tier 2 condition eval (JSONLogic/FHIRPath) → for each match: enroll patient → create/complete step → detect deviations → evaluate intelligence rules → publish intelligence trigger events.

## Key Conventions

- **Package:** `org.openphc.cce.compliance` — ~81 source files across 18 packages
- **Entities:** 9 JPA entities in `domain/entity/`, using `JsonNode` for JSONB columns (mapped via Hibernate 6 `@JdbcTypeCode(SqlTypes.JSON)`)
- **Enums:** 12 value-based enums in `domain/enums/` — always use enum values, not ordinals
- **DTOs:** Separate DTOs in `web/dto/`, mapped via `DtoMapper` — never expose entities in REST responses
- **All timestamps:** `OffsetDateTime` in UTC (`hibernate.jdbc.time_zone=UTC`)
- **IDs:** `UUID` for all entity primary keys except `TriggerIndex` (composite PK via `TriggerIndexId`)
- **Kafka messages:** CloudEvents v1.0 spec with CCE extension attributes (`correlationid`, `actionid`, `facilityid`)
- **Schema migrations:** Flyway only (`spring.jpa.hibernate.ddl-auto=validate`) — never let Hibernate modify schema

## Critical Design Patterns

- **Five exclusive matching scenarios:** Each trigger falls into exactly one: (F1) resource type only, (F1,F2) type + codeFilters, (F1,F3) type + condition, (F1,F2,F3) type + codeFilters + condition, (F3) condition only. See `docs/architecture-overview.md` §5.4.
- **Two-tier matching:** Tier 1 uses `trigger_index` with `GROUP BY + HAVING` to enforce AND semantics across multiple `codeFilter` entries (each codeFilter is indexed with its `path`). Tier 2 evaluates JSONLogic/FHIRPath expressions. Tier 1 result is reused for Scenario 4 (F1,F2,F3) — no re-query. Resource type is extracted from `data.resourceType` (payload), never from CloudEvents envelope `type`.
- **Condition-only triggers:** Triggers with no `data[]` (only `condition`) are held in-memory and evaluated via Tier 2 for every inbound event. A trigger with no `data[]` and no `condition` is rejected at load time.
- **All matches create steps:** When multiple triggers match, each match creates its own step instance — there is no AMBIGUOUS state.
- **Explicit matching:** When a CloudEvent carries a non-null `actionId` extension, bypass Tier 1/2 entirely via `processExplicitMatch()`.
- **Progressive step instantiation:** When a step completes, dependent PENDING steps are created automatically using `relatedAction` offsets. The `relationship` field determines the base time: `after-end` uses `completedAt`, `after-start` uses `dueDate`. When `TimingInfo.count > 1`, multiple recurring instances are created with staggered due dates.
- **Intelligence rules:** Nested sub-actions within PlanDefinition steps, evaluated on deviation detection and step completion. Conditions use JSONLogic/FHIRPath against step runtime state. When a rule fires, `IntelligenceTriggerProducer` publishes to `cce.intelligence.triggers` and an `ActionRun` record is created.
- **Action Definitions:** `ActivityDefinition` resources stored in `action_definition` table, referenced by intelligence rules via `definitionCanonical`. Define action type, message template, severity, target, and routing.
- **Idempotency:** `(cloudeventsId, source)` uniqueness on `event_log` — essential for at-least-once Kafka delivery.
- **Audit:** `AuditService` is `@Async` with `@Transactional(REQUIRES_NEW)` — audit writes never block or fail with the main transaction.
- **Kafka consumers:** `AckMode.RECORD` — offsets committed automatically per record on success; on failure, exceptions propagate to `DefaultErrorHandler` which retries with configurable backoff then routes to DLQ (`<topic>.dlq`).
- **Kafka topics:** 5 `NewTopic` beans (3 primary + 2 DLQ) declared in `KafkaConfig` with 25 partitions each (configurable via `cce.kafka.topics.default-partitions`). Topics are auto-created by `KafkaAdmin` on startup.

## Step State Machine

`PENDING → DUE → OVERDUE → MISSED` (scheduler-driven transitions)
`PENDING/DUE/OVERDUE → COMPLETED` (event-driven via `completeStep()`)
`OVERDUE → SKIPPED` (scheduler-driven for `could` steps, or auto-skip when subsequent step completes)
Terminal states: `COMPLETED`, `MISSED`, `SKIPPED`.

## Build & Run

```bash
./gradlew build -x test                # Fast build
./gradlew build                         # Build + all tests
cd /path/to/cce-collector-service && docker compose up -d  # Start shared PostgreSQL + Kafka
./gradlew bootRun                       # Run app (Flyway migrates shared cce_collector DB)
curl localhost:8080/actuator/health     # Health check
```

## Testing

- Unit tests (258): mocked dependencies — `src/test/java`
- Integration tests (24): EmbeddedKafka + H2 in-memory (PostgreSQL mode) — `src/integrationTest/java`
- API tests: MockMvc
- Run unit tests: `./gradlew test`
- Run integration tests: `./gradlew integrationTest`
- Run specific: `./gradlew test --tests ComplianceEngineTest`
- Coverage: `./gradlew test jacocoTestReport`

## Key Files to Read First

- `docs/architecture-overview.md` — core pipeline, matching algorithm, state machines
- `docs/kafka-events.md` — message schemas, consumer/producer patterns
- `docs/data-dictionary.md` — complete DB schema with JSONB column schemas
- `docs/api-reference.md` — all REST endpoints with request/response examples
- `docs/deployment-guide.md` — production deployment, env vars, Docker/K8s, monitoring
- `RELEASE-NOTES.md` — v1.0.0 feature summary and known limitations

## What's NOT in Scope

- CQL expression evaluation — removed, only JSONLogic and FHIRPath supported
- Intelligence event delivery/routing to Receiver Adaptors — handled by the CCE Intelligence Service (this service publishes trigger events to Kafka; see `docs/architecture-overview.md` §1.2)
- `cce.protocol.control` topic — reserved for future use
