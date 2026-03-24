package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.compliance.domain.entity.EventLog;
import org.openphc.cce.compliance.domain.enums.ProcessingStatus;
import org.openphc.cce.compliance.domain.repository.EventLogRepository;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@Transactional
public class EventLogService {

    private static final Logger log = LoggerFactory.getLogger(EventLogService.class);

    private final EventLogRepository eventLogRepository;
    private final ObjectMapper objectMapper;

    public EventLogService(EventLogRepository eventLogRepository, ObjectMapper objectMapper) {
        this.eventLogRepository = eventLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
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

        eventLog = eventLogRepository.save(eventLog);

        log.debug("Recorded event: cloudeventsId={}, source={}, subject={}, status={}",
                message.getId(), message.getSource(), message.getSubject(), status);

        return eventLog;
    }

    public void updateStatus(EventLog eventLog, ProcessingStatus status) {
        eventLog.setProcessingStatus(status);
        eventLogRepository.save(eventLog);

        log.debug("Updated event status: eventLogId={}, status={}",
                eventLog.getId(), status);
    }
}
