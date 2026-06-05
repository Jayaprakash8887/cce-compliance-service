package org.openphc.cce.compliance.service;

import org.openphc.cce.compliance.domain.entity.ComplianceEventLog;
import org.openphc.cce.compliance.domain.enums.ProcessingStatus;
import org.openphc.cce.compliance.domain.repository.ComplianceEventLogRepository;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@Transactional
public class ComplianceEventLogService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceEventLogService.class);

    private final ComplianceEventLogRepository eventLogRepository;

    public ComplianceEventLogService(ComplianceEventLogRepository eventLogRepository) {
        this.eventLogRepository = eventLogRepository;
    }

    @Transactional(readOnly = true)
    public boolean isDuplicate(String cloudeventsId, String source) {
        return eventLogRepository.existsByCloudeventsIdAndSource(cloudeventsId, source);
    }

    public ComplianceEventLog recordEvent(CloudEventMessage message, ProcessingStatus status) {
        ComplianceEventLog eventLog = ComplianceEventLog.builder()
                .cloudeventsId(message.getId())
                .source(message.getSource())
                .correlationId(message.getCorrelationid())
                .data(message.getData())
                .receivedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .processingStatus(status)
                .build();

        eventLog = eventLogRepository.save(eventLog);

        log.debug("Recorded event: cloudeventsId={}, source={}, status={}",
                message.getId(), message.getSource(), status);

        return eventLog;
    }

    public void updateStatus(ComplianceEventLog eventLog, ProcessingStatus status) {
        eventLog.setProcessingStatus(status);
        eventLogRepository.save(eventLog);

        log.debug("Updated event status: eventLogId={}, status={}",
                eventLog.getId(), status);
    }
}
