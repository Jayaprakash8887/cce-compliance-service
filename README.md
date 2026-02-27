# CCE Compliance Service

> **Release 1.0.0**

A core microservice within the Clinical Compliance Engine (CCE) platform. It tracks patient adherence to clinical protocols defined as FHIR R4 `PlanDefinition` resources — consuming clinical events, matching them against protocol steps, detecting deviations, and publishing intelligence triggers for downstream analytics.

## Documentation

| Document | Description |
|---|---|
| [Architecture Overview](docs/architecture-overview.md) | System context, component interactions, and infrastructure layout |
| [High-Level Design](docs/high-level-design.md) | Service responsibilities, system interactions, and design decisions |
| [Low-Level Design](docs/low-level-design.md) | Package structure, class-level design, and implementation details |
| [API Reference](docs/api-reference.md) | RESTful API endpoints, request/response schemas, and authentication |
| [Data Model](docs/data-model.md) | Entity relationships, JPA entities, and database schema diagrams |
| [Data Dictionary](docs/data-dictionary.md) | Complete database schema reference — tables, columns, indexes, and JSONB schemas |
| [Kafka Events](docs/kafka-events.md) | Kafka topics, CloudEvents message formats, consumers, and producers |
| [Flow Diagrams](docs/flow-diagrams.md) | Sequence and flow diagrams for all major workflows |
| [Developer Setup](docs/developer-setup.md) | Prerequisites, build instructions, and local development configuration |
