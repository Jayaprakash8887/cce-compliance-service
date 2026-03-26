package org.openphc.cce.compliance;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for all integration tests. Uses:
 * - H2 in-memory database (PostgreSQL compatibility mode) — configured in application-integrationtest.yml
 * - EmbeddedKafka (in-process broker from spring-kafka-test) — no Docker required
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integrationtest")
@EmbeddedKafka(
        partitions = 1,
        topics = {
                "cce.events.inbound",
                "cce.scheduler.triggers",
                "cce.intelligence.triggers",
                "cce.events.inbound.dlq",
                "cce.scheduler.triggers.dlq"
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
public abstract class IntegrationTestBase {
}
