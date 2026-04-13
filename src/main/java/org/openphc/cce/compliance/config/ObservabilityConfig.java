package org.openphc.cce.compliance.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.openphc.cce.compliance.domain.enums.ActionDefinitionStatus;
import org.openphc.cce.compliance.domain.enums.ProtocolInstanceStatus;
import org.openphc.cce.compliance.domain.repository.ActionDefinitionRepository;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {

    @Bean
    public MeterBinder cceMetrics(ProtocolInstanceRepository protocolInstanceRepository,
                                  ActionDefinitionRepository actionDefinitionRepository) {
        return registry -> {
            Counter.builder("cce.events.processed")
                    .description("Total inbound events processed")
                    .register(registry);

            Counter.builder("cce.events.matched")
                    .tag("status", "matched")
                    .description("Events that matched at least one trigger")
                    .register(registry);

            Counter.builder("cce.events.matched")
                    .tag("status", "zero_match")
                    .description("Events that matched no triggers")
                    .register(registry);

            Counter.builder("cce.events.duplicate")
                    .description("Duplicate events skipped via idempotency check")
                    .register(registry);

            Counter.builder("cce.events.intelligence.published")
                    .description("Intelligence trigger events published to Kafka")
                    .register(registry);

            Counter.builder("cce.intelligence.actions.evaluated")
                    .description("Total intelligence action conditions evaluated")
                    .register(registry);

            Counter.builder("cce.intelligence.actions.fired")
                    .description("Intelligence actions that matched and triggered")
                    .register(registry);

            Timer.builder("cce.step.matching.duration")
                    .description("Time taken for trigger matching pipeline")
                    .register(registry);

            Timer.builder("cce.events.processing.duration")
                    .description("Total time taken to process an inbound event end-to-end")
                    .register(registry);

            Timer.builder("cce.intelligence.publish.duration")
                    .description("Time to publish intelligence trigger event to Kafka")
                    .register(registry);

            Gauge.builder("cce.protocol.instances.active",
                            protocolInstanceRepository,
                            repo -> repo.countByStatus(ProtocolInstanceStatus.ACTIVE))
                    .description("Number of currently active protocol instances")
                    .register(registry);

            Gauge.builder("cce.action.definitions.active",
                            actionDefinitionRepository,
                            repo -> repo.countByStatus(ActionDefinitionStatus.ACTIVE))
                    .description("Number of currently active action definitions")
                    .register(registry);
        };
    }
}
