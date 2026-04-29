# CCE Compliance Service

![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.2-green)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)
![Kafka](https://img.shields.io/badge/Kafka-3.x%20KRaft-orange)
![License](https://img.shields.io/badge/license-proprietary-lightgrey)

> **Release 1.1.0** — [Release Notes](RELEASE-NOTES.md) | [Changelog](CHANGELOG.md)

A core microservice within the Clinical Compliance Engine (CCE) platform. It tracks patient adherence to clinical protocols defined as FHIR R4 `PlanDefinition` resources — consuming clinical events, matching them against protocol steps, detecting deviations, evaluating intelligence actions, and publishing intelligence triggers for downstream processing.

## Quick Start

```bash
# Start shared infrastructure (from collector service)
cd /path/to/cce-collector-service && docker compose up -d

# Build
cd /path/to/cce-compliance-service
./gradlew build

# Run (Flyway applies migrations to shared cce_collector database)
./gradlew bootRun

# Health check
curl localhost:8080/actuator/health
```

## Documentation

| Document | Description |
|---|---|
| [Architecture & Design](docs/architecture-overview.md) | System context, core pipeline, matching algorithm, state machines, and design decisions |
| [API Reference](docs/api-reference.md) | RESTful API endpoints, request/response schemas, and authentication |
| [Data Dictionary](docs/data-dictionary.md) | ER diagram, database schema, columns, indexes, enums, JSONB schemas, and JPA mapping |
| [Kafka Events](docs/kafka-events.md) | Kafka topics, CloudEvents message formats, consumers, and producers |
| [Flow Diagrams](docs/flow-diagrams.md) | Sequence and flow diagrams for all major workflows |
| [Developer Setup](docs/developer-setup.md) | Prerequisites, build instructions, and local development configuration |
| [Deployment Guide](docs/deployment-guide.md) | Production deployment, environment variables, Docker/K8s, monitoring |
| [Release Notes](RELEASE-NOTES.md) | Version 1.0.0 features, known limitations |
| [Changelog](CHANGELOG.md) | Full changelog with categorized changes |

## Architecture

```
Kafka → InboundEventConsumer → ComplianceEngine
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

## Testing

```bash
# Unit tests (351 tests)
./gradlew test

# Integration tests (39 tests)
./gradlew integrationTest

# Full build with unit tests
./gradlew build

# Coverage report
./gradlew test jacocoTestReport
```
