package org.openphc.cce.compliance.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KafkaTopicPropertiesTest {

    @Test
    void shouldBindTopicProperties() {
        KafkaTopicProperties props = new KafkaTopicProperties();
        props.setInboundEvents("cce.events.inbound");
        props.setSchedulerTriggers("cce.scheduler.triggers");
        props.setIntelligenceTriggers("cce.intelligence.triggers");
        props.setDefaultPartitions(15);

        assertEquals("cce.events.inbound", props.getInboundEvents());
        assertEquals("cce.scheduler.triggers", props.getSchedulerTriggers());
        assertEquals("cce.intelligence.triggers", props.getIntelligenceTriggers());
        assertEquals(25, props.getDefaultPartitions());
    }

    @Test
    void shouldDefaultToNull() {
        KafkaTopicProperties props = new KafkaTopicProperties();

        assertNull(props.getInboundEvents());
        assertNull(props.getSchedulerTriggers());
        assertNull(props.getIntelligenceTriggers());
        assertEquals(25, props.getDefaultPartitions());
    }
}
