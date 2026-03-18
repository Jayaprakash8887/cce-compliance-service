package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.compliance.domain.entity.AuditLog;
import org.openphc.cce.compliance.domain.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void audit(String category, String type, String actor,
                      String resourceType, String resourceId,
                      Map<String, Object> details) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .eventCategory(category)
                    .eventType(type)
                    .actor(actor)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .details(details != null ? objectMapper.valueToTree(details) : null)
                    .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to write audit log: category={}, type={}, resourceId={}",
                    category, type, resourceId, e);
        }
    }
}
