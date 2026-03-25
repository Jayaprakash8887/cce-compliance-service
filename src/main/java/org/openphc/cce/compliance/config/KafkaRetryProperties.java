package org.openphc.cce.compliance.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cce.kafka.retry")
@Getter
@Setter
public class KafkaRetryProperties {

    private long maxAttempts = 3;
    private long backoffIntervalMs = 1000;
}
