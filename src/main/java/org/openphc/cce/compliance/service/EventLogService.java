package org.openphc.cce.compliance.service;

import org.openphc.cce.compliance.domain.entity.EventLog;
import org.openphc.cce.compliance.domain.enums.ProcessingStatus;
import org.openphc.cce.compliance.domain.repository.EventLogRepository;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@Transactional
public class EventLogService {

    private static final Logger log = LoggerFactory.getLogger(EventLogService.class);

    private final EventLogRepository eventLogRepository;

    public EventLogService(EventLogRepository eventLogRepository) {
        this.eventLogRepository = eventLogRepository;
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
                .data(message.getData())
                .facilityId(message.getFacilityid())
                .processingStatus(status)
                .build();

        eventLog = eventLogRepository.save(eventLog);

        log.debug("Recorded event: cloudeventsId={}, source={}, subject={}, status={}",
                message.getId(), message.getSource(), message.getSubject(), status);

        return eventLog;
    }

    @Transactional(readOnly = true)
    public Page<EventLog> findByPatientId(String patientId, Pageable pageable) {
        return eventLogRepository.findBySubject(patientId, pageable);
    }

    public void updateStatus(EventLog eventLog, ProcessingStatus status) {
        eventLog.setProcessingStatus(status);
        eventLogRepository.save(eventLog);

        log.debug("Updated event status: eventLogId={}, status={}",
                eventLog.getId(), status);
    }
}
