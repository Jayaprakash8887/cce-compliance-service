package org.openphc.cce.compliance.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {

    @Bean
    public MeterBinder cceMetrics() {
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

            Timer.builder("cce.step.matching.duration")
                    .description("Time taken for trigger matching pipeline")
                    .register(registry);
        };
    }
}
