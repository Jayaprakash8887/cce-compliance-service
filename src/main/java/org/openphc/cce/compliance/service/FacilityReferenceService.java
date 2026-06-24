package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.compliance.domain.entity.FacilityReference;
import org.openphc.cce.compliance.domain.repository.FacilityReferenceRepository;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FacilityReferenceService {

    private final FacilityReferenceRepository facilityReferenceRepository;

    @Transactional
    public void registerFacilityIfAbsent(CloudEventMessage event) {
        FacilityDetails details = extractFacilityDetails(event);
        if (details == null) return;
        if (facilityReferenceRepository.existsByFacilityId(details.id())) return;

        facilityReferenceRepository.save(
                FacilityReference.builder()
                        .facilityId(details.id())
                        .facilityName(details.name())
                        .build()
        );
        log.info("Captured new facility reference: facilityId={}, name={}", details.id(), details.name());
    }

    /**
     * Single-pass extraction of facility ID and display name from the FHIR payload.
     * Both values sit on the same Reference node, so one walk covers both.
     *
     * ID: CloudEvent envelope facilityid (preferred) → reference string (strip prefix) → identifier.value
     * Name: display field on the same Reference node
     *
     * Resource paths (mirrors FacilityIdExtractor in openhim-cce-emitter-adaptor):
     *   ServiceRequest : locationReference[0]
     *   Encounter      : location[0].location
     *   Procedure /
     *   Immunization   : location  (direct Reference, not array)
     */
    private FacilityDetails extractFacilityDetails(CloudEventMessage event) {
        String envelopeId = event.getFacilityid();
        JsonNode data = event.getData();
        if (data == null && (envelopeId == null || envelopeId.isBlank())) return null;

        // ServiceRequest: locationReference[0]
        JsonNode locationRef = data != null ? data.get("locationReference") : null;
        if (locationRef != null && locationRef.isArray()) {
            for (JsonNode loc : locationRef) {
                FacilityDetails facilityDetails = fromRefNode(loc, envelopeId);
                if (facilityDetails != null) return facilityDetails;
            }
        }

        JsonNode locationNode = data != null ? data.get("location") : null;
        if (locationNode != null) {
            if (locationNode.isArray()) {
                // Encounter: location[0].location
                for (JsonNode loc : locationNode) {
                    JsonNode inner = loc.get("location");
                    if (inner != null) {
                        FacilityDetails facilityDetails = fromRefNode(inner, envelopeId);
                        if (facilityDetails != null) return facilityDetails;
                    }
                }
            } else {
                // Procedure / Immunization: location (direct Reference)
                return fromRefNode(locationNode, envelopeId);
            }
        }

        return null;
    }

    /**
     * Extracts id and display name from a single FHIR Reference node in one pass.
     * Returns null if either value is missing — we don't persist incomplete records.
     */
    private FacilityDetails fromRefNode(JsonNode refNode, String envelopeId) {
        // ID: envelope first, then reference string (strip prefix), then identifier.value
        String id = envelopeId;
        if (id == null || id.isBlank()) {
            String ref = textOrNull(refNode.get("reference"));
            if (ref != null) {
                id = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;
            }
        }
        if (id == null || id.isBlank()) {
            JsonNode identifier = refNode.get("identifier");
            if (identifier != null) id = textOrNull(identifier.get("value"));
        }

        String name = textOrNull(refNode.get("display"));

        return (id != null && name != null) ? new FacilityDetails(id, name) : null;
    }

    private record FacilityDetails(String id, String name) {}

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String text = node.asText().strip();
        return text.isBlank() ? null : text;
    }
}
