package org.openphc.cce.compliance.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cce.kafka.topics")
@Getter
@Setter
public class KafkaTopicProperties {

    private String inboundEvents;
    private String schedulerTriggers;
    private String intelligenceTriggers;
}
