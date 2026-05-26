package org.openphc.cce.compliance.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.openphc.cce.compliance.domain.enums.ProcessingStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "event_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "cloudevents_id", nullable = false)
    private String cloudeventsId;

    @Column(nullable = false)
    private String source;

    @Column(name = "source_event_id")
    private String sourceEventId;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String type;

    @Column(name = "event_time", nullable = false)
    private OffsetDateTime eventTime;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode data;

    @Column(name = "protocol_instance_id")
    private UUID protocolInstanceId;

    @Column(name = "protocol_definition_id")
    private UUID protocolDefinitionId;

    @Column(name = "action_id")
    private String actionId;

    @Column(name = "facility_id")
    private String facilityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    private ProcessingStatus processingStatus;
}
