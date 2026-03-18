package org.openphc.cce.compliance.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * CloudEvents v1.0 message envelope with CCE extension attributes.
 * All field names are lowercase per CloudEvents spec — no camelCase translation.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudEventMessage {

    // ── CloudEvents v1.0 required fields ──
    private String id;
    private String source;
    private String type;
    private String specversion;
    private String subject;
    private OffsetDateTime time;
    private String datacontenttype;

    // ── CCE extension attributes (lowercase per CloudEvents spec) ──
    private String correlationid;
    private String sourceeventid;
    private String protocolinstanceid;
    private String protocoldefinitionid;
    private String actionid;
    private String facilityid;

    // ── Payload ──
    private Map<String, Object> data;
}
