package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.domain.entity.EventLog;
import org.openphc.cce.compliance.domain.enums.ProcessingStatus;
import org.openphc.cce.compliance.domain.repository.EventLogRepository;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventLogServiceTest {

    @Mock
    private EventLogRepository eventLogRepository;

    private EventLogService eventLogService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        eventLogService = new EventLogService(eventLogRepository);
    }

    @Test
    void isDuplicate_existingEvent_returnsTrue() {
        when(eventLogRepository.existsByCloudeventsIdAndSource("evt-001", "ebuzima"))
                .thenReturn(true);

        assertTrue(eventLogService.isDuplicate("evt-001", "ebuzima"));
        verify(eventLogRepository).existsByCloudeventsIdAndSource("evt-001", "ebuzima");
    }

    @Test
    void isDuplicate_newEvent_returnsFalse() {
        when(eventLogRepository.existsByCloudeventsIdAndSource("evt-new", "rhie"))
                .thenReturn(false);

        assertFalse(eventLogService.isDuplicate("evt-new", "rhie"));
    }

    @Test
    void recordEvent_mapsAllFieldsCorrectly() {
        CloudEventMessage message = CloudEventMessage.builder()
                .id("evt-002")
                .source("ebuzima")
                .type("Observation")
                .specversion("1.0")
                .subject("patient-123")
                .time(OffsetDateTime.of(2026, 3, 15, 10, 30, 0, 0, ZoneOffset.UTC))
                .datacontenttype("application/fhir+json")
                .correlationid("corr-abc-123")
                .sourceeventid("lab-evt-789")
                .facilityid("0002")
                .data(objectMapper.valueToTree(Map.of("resourceType", "Observation", "status", "final")))
                .build();

        when(eventLogRepository.save(any(EventLog.class))).thenAnswer(inv -> inv.getArgument(0));

        eventLogService.recordEvent(message, ProcessingStatus.ZERO_MATCH);

        ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventLogRepository).save(captor.capture());

        EventLog saved = captor.getValue();
        assertEquals("evt-002", saved.getCloudeventsId());
        assertEquals("ebuzima", saved.getSource());
        assertEquals("lab-evt-789", saved.getSourceEventId());
        assertEquals("patient-123", saved.getSubject());
        assertEquals("Observation", saved.getType());
        assertEquals(message.getTime(), saved.getEventTime());
        assertNotNull(saved.getReceivedAt());
        assertEquals("corr-abc-123", saved.getCorrelationId());
        assertEquals("0002", saved.getFacilityId());
        assertEquals(ProcessingStatus.ZERO_MATCH, saved.getProcessingStatus());

        // Verify data is converted to JsonNode
        JsonNode data = saved.getData();
        assertNotNull(data);
        assertEquals("Observation", data.get("resourceType").asText());
        assertEquals("final", data.get("status").asText());
    }

    @Test
    void recordEvent_setsReceivedAtToCurrentUtcTime() {
        CloudEventMessage message = CloudEventMessage.builder()
                .id("evt-003")
                .source("test")
                .type("Encounter")
                .subject("patient-456")
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .correlationid("corr-test")
                .data(objectMapper.valueToTree(Map.of("resourceType", "Encounter")))
                .build();

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

        when(eventLogRepository.save(any(EventLog.class))).thenAnswer(inv -> inv.getArgument(0));
        eventLogService.recordEvent(message, ProcessingStatus.MATCHED);

        ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventLogRepository).save(captor.capture());

        OffsetDateTime receivedAt = captor.getValue().getReceivedAt();
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

        assertFalse(receivedAt.isBefore(before));
        assertFalse(receivedAt.isAfter(after));
    }

    @Test
    void recordEvent_nullableFieldsHandled() {
        CloudEventMessage message = CloudEventMessage.builder()
                .id("evt-004")
                .source("src")
                .type("Observation")
                .subject("patient-789")
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .correlationid("corr-null-test")
                .data(objectMapper.valueToTree(Map.of("resourceType", "Observation")))
                .build();
        // sourceeventid, facilityid are null

        when(eventLogRepository.save(any(EventLog.class))).thenAnswer(inv -> inv.getArgument(0));

        eventLogService.recordEvent(message, ProcessingStatus.ZERO_MATCH);

        ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventLogRepository).save(captor.capture());

        assertNull(captor.getValue().getSourceEventId());
        assertNull(captor.getValue().getFacilityId());
        assertNull(captor.getValue().getProtocolInstanceId());
        assertNull(captor.getValue().getProtocolDefinitionId());
        assertNull(captor.getValue().getActionId());
        assertNull(captor.getValue().getMatchedStepInstanceId());
    }

    @Test
    void updateStatus_updatesAndSaves() {
        EventLog eventLog = EventLog.builder()
                .processingStatus(ProcessingStatus.ZERO_MATCH)
                .build();

        when(eventLogRepository.save(any(EventLog.class))).thenAnswer(inv -> inv.getArgument(0));

        eventLogService.updateStatus(eventLog, ProcessingStatus.MATCHED);

        assertEquals(ProcessingStatus.MATCHED, eventLog.getProcessingStatus());
        verify(eventLogRepository).save(eventLog);
    }
}
