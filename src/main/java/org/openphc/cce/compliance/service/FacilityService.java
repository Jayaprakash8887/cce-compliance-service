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
     *   Encounter      : hospitalization.origin → source-facility extension → location[0].location
     *   Procedure /
     *   Immunization   : location (direct Reference) → source-facility extension
     *   any other type : source-facility extension (e.g. Observation, Condition, MedicationRequest,
     *                     which carry no FHIR location at all)
     *
     * For a transfer Encounter, location[0].location reflects where the patient ended up
     * (the destination), not where the encounter/referral originated. hospitalization.origin is
     * the correct source facility; the source-facility extension is the source system's own
     * unambiguous facility declaration and is checked before location[0] for the same reason —
     * it is present on both plain and transfer encounters and matches hospitalization.origin's id
     * for transfers, so it catches the case above missing (origin absent/malformed) without ever
     * resolving to the destination the way location[0] can. Mirrors the
     * openhim-cce-emitter-adaptor fix (PR #30) and its FacilityIdExtractor.
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

        // Encounter (transfer): hospitalization.origin takes priority over location[0].location
        JsonNode hospitalization = data != null ? data.get("hospitalization") : null;
        JsonNode originRef = hospitalization != null ? hospitalization.get("origin") : null;
        if (originRef != null) {
            FacilityDetails facilityDetails = fromRefNode(originRef, facilityId);
            if (facilityDetails != null) return facilityDetails;
        }

        JsonNode locationNode = data != null ? data.get("location") : null;
        String extensionFacilityId = extractSourceFacilityExtension(data);

        // Encounter: source-facility extension, checked ahead of location[0] so a transfer
        // Encounter without hospitalization.origin still resolves to the true source facility
        // rather than falling through to the destination in location[0].
        if (locationNode != null && locationNode.isArray() && extensionFacilityId != null) {
            String name = findDisplayForFacilityId(locationNode, extensionFacilityId);
            String resolvedId = facilityId != null && !facilityId.isBlank() ? facilityId : extensionFacilityId;
            return new FacilityDetails(resolvedId, name);
        }

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
                FacilityDetails facilityDetails = fromRefNode(locationNode, facilityId);
                if (facilityDetails != null) return facilityDetails;
            }
        }

        // Fallback: envelope facilityId, or else the source-facility extension — covers resource
        // types with no FHIR location at all (Observation, Condition, MedicationRequest, ...),
        // and any resource whose location node didn't resolve to a usable id above. No display
        // name is available at this point.
        String resolvedId = facilityId != null && !facilityId.isBlank() ? facilityId : extensionFacilityId;
        return resolvedId != null ? new FacilityDetails(resolvedId, null) : null;
    }

    /**
     * Extracts the facility ID from the source system's {@code source-facility} extension
     * (matched by URL suffix so it survives base-URL changes), e.g.:
     * {@code {"url": ".../source-facility", "valueString": "1651"}} → {@code "1651"}.
     */
    private String extractSourceFacilityExtension(JsonNode data) {
        JsonNode extensions = data != null ? data.get("extension") : null;
        if (extensions == null || !extensions.isArray()) return null;
        for (JsonNode extension : extensions) {
            String url = textOrNull(extension.get("url"));
            if (url != null && url.endsWith("source-facility")) {
                String value = textOrNull(extension.get("valueString"));
                if (value != null) return value;
            }
        }
        return null;
    }

    /**
     * Scans an Encounter's location[] array for an entry whose resolved id matches
     * targetFacilityId, returning its display name. Used to recover a display name for a
     * source-facility-extension-resolved id without ever trusting a non-matching (destination) entry.
     */
    private String findDisplayForFacilityId(JsonNode locationArray, String targetFacilityId) {
        for (JsonNode locationEntry : locationArray) {
            JsonNode nestedLocationRef = locationEntry.get("location");
            if (nestedLocationRef == null) continue;
            FacilityDetails details = fromRefNode(nestedLocationRef, null);
            if (details != null && targetFacilityId.equals(details.id())) {
                return details.name();
            }
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
