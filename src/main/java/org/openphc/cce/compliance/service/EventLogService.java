package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.compliance.domain.entity.EventLog;
import org.openphc.cce.compliance.domain.enums.ProcessingStatus;
import org.openphc.cce.compliance.domain.repository.EventLogRepository;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class EventLogService {

    private final EventLogRepository eventLogRepository;
    private final ObjectMapper objectMapper;

    public EventLogService(EventLogRepository eventLogRepository, ObjectMapper objectMapper) {
        this.eventLogRepository = eventLogRepository;
        this.objectMapper = objectMapper;
    }

    public boolean isDuplicate(String cloudeventsId, String source) {
        return eventLogRepository.existsByCloudeventsIdAndSource(cloudeventsId, source);
    }

    public EventLog recordEvent(CloudEventMessage message, ProcessingStatus status) {
        EventLog eventLog = EventLog.builder()
                .cloudeventsId(message.getId())
                .source(message.getSource())
                .sourceEventId(message.getSourceeventid())
                .subject(message.getSubject())
                .type(message.getType())
                .eventTime(message.getTime())
                .receivedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .correlationId(message.getCorrelationid())
                .data(objectMapper.valueToTree(message.getData()))
                .facilityId(message.getFacilityid())
                .processingStatus(status)
                .build();

        return eventLogRepository.save(eventLog);
    }

    public void updateStatus(EventLog eventLog, ProcessingStatus status) {
        eventLog.setProcessingStatus(status);
        eventLogRepository.save(eventLog);
    }
}
