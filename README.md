# CCE Compliance Service

> **Release 1.0.0**

A core microservice within the Clinical Compliance Engine (CCE) platform. It tracks patient adherence to clinical protocols defined as FHIR R4 `PlanDefinition` resources — consuming clinical events, matching them against protocol steps, detecting deviations, and publishing intelligence triggers for downstream analytics.

## Documentation

| Document | Description |
|---|---|
| [Architecture & Design](docs/architecture-overview.md) | System context, core pipeline, matching algorithm, state machines, and design decisions |
| [API Reference](docs/api-reference.md) | RESTful API endpoints, request/response schemas, and authentication |
| [Data Dictionary](docs/data-dictionary.md) | ER diagram, database schema, columns, indexes, enums, JSONB schemas, and JPA mapping |
| [Kafka Events](docs/kafka-events.md) | Kafka topics, CloudEvents message formats, consumers, and producers |
| [Flow Diagrams](docs/flow-diagrams.md) | Sequence and flow diagrams for all major workflows |
| [Developer Setup](docs/developer-setup.md) | Prerequisites, build instructions, and local development configuration |
