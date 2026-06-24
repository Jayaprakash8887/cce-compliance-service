package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.compliance.domain.entity.Facility;
import org.openphc.cce.compliance.domain.repository.FacilityRepository;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FacilityService {

    private final FacilityRepository facilityRepository;

    @Transactional
    public void upsertFacility(CloudEventMessage event) {
        FacilityDetails facilityDetails = extractFacilityDetails(event);
        if (facilityDetails == null) return;

        Optional<Facility> optExistingFacility = facilityRepository.findByFacilityId(facilityDetails.id());
        if (optExistingFacility.isPresent()) {
            Facility existingFacility = optExistingFacility.get();
            // Update only when incoming name is present AND differs from stored name (covers null→name and name→newName).
            // If incoming name is absent, keep whatever is stored (avoids overwriting a known name with null).
            if (facilityDetails.name() != null && !facilityDetails.name().equals(existingFacility.getFacilityName())) {
                existingFacility.setFacilityName(facilityDetails.name());
                facilityRepository.save(existingFacility);
                log.info("Updated facility name: facilityId={}, name={}", facilityDetails.id(), facilityDetails.name());
            }
            return;
        }

        facilityRepository.save(
                Facility.builder()
                        .facilityId(facilityDetails.id())
                        .facilityName(facilityDetails.name())
                        .build()
        );
        log.info("Captured new facility: facilityId={}, name={}", facilityDetails.id(), facilityDetails.name());
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
        String facilityId = event.getFacilityid();
        JsonNode data = event.getData();
        if (data == null && (facilityId == null || facilityId.isBlank())) return null;

        // ServiceRequest: locationReference[0]
        JsonNode locationRef = data != null ? data.get("locationReference") : null;
        if (locationRef != null && locationRef.isArray()) {
            for (JsonNode locationRefEntry : locationRef) {
                FacilityDetails facilityDetails = fromRefNode(locationRefEntry, facilityId);
                if (facilityDetails != null) return facilityDetails;
            }
        }

        JsonNode locationNode = data != null ? data.get("location") : null;
        if (locationNode != null) {
            if (locationNode.isArray()) {
                // Encounter: location[0].location
                for (JsonNode locationEntry : locationNode) {
                    JsonNode nestedLocationRef = locationEntry.get("location");
                    if (nestedLocationRef != null) {
                        FacilityDetails facilityDetails = fromRefNode(nestedLocationRef, facilityId);
                        if (facilityDetails != null) return facilityDetails;
                    }
                }
            } else {
                // Procedure / Immunization: location (direct Reference)
                return fromRefNode(locationNode, facilityId);
            }
        }

        // Fallback: envelope has facilityId but no FHIR location node was found — insert with null name
        if (facilityId != null && !facilityId.isBlank()) {
            return new FacilityDetails(facilityId, null);
        }
        return null;
    }

    /**
     * Extracts facilityId and facilityName from a single FHIR Reference node in one pass.
     * facilityId resolution order: envelope facilityId → reference string (strip ResourceType/ prefix) → identifier.value
     * facilityName is taken from the display field and may be null — a row with a known id but unknown name is still persisted.
     * Returns null only when facilityId cannot be resolved from any source.
     */
    private FacilityDetails fromRefNode(JsonNode locationRefNode, String facilityId) {
        // ID: envelope first, then reference string (strip prefix), then identifier.value
        if (facilityId == null || facilityId.isBlank()) {
            String reference = textOrNull(locationRefNode.get("reference"));
            if (reference != null) {
                facilityId = reference.contains("/") ? reference.substring(reference.lastIndexOf('/') + 1) : reference;
            }
        }
        if (facilityId == null || facilityId.isBlank()) {
            JsonNode identifier = locationRefNode.get("identifier");
            if (identifier != null) facilityId = textOrNull(identifier.get("value"));
        }

        if (facilityId == null || facilityId.isBlank()) return null;

        String facilityName = textOrNull(locationRefNode.get("display"));
        return new FacilityDetails(facilityId, facilityName);
    }

    private record FacilityDetails(String id, String name) {}

    private String textOrNull(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) return null;
        String text = jsonNode.asText().strip();
        return text.isBlank() ? null : text;
    }
}
