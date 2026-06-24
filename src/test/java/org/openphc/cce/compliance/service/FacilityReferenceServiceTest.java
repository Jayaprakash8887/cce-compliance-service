package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.domain.entity.FacilityReference;
import org.openphc.cce.compliance.domain.repository.FacilityReferenceRepository;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FacilityReferenceServiceTest {

    @Mock
    private FacilityReferenceRepository facilityReferenceRepository;

    private FacilityReferenceService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new FacilityReferenceService(facilityReferenceRepository);
    }

    // ── ServiceRequest ─────────────────────────────────────────────────────────

    @Test
    void serviceRequest_envelopeFacilityId_savesRecord() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Location/1302", "display": "NCD Upazila" }]
                }
                """;
        CloudEventMessage event = eventWith("1302", payload);
        when(facilityReferenceRepository.existsByFacilityId("1302")).thenReturn(false);
        when(facilityReferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.registerFacilityIfAbsent(event);

        ArgumentCaptor<FacilityReference> captor = ArgumentCaptor.forClass(FacilityReference.class);
        verify(facilityReferenceRepository).save(captor.capture());
        assertEquals("1302", captor.getValue().getFacilityId());
        assertEquals("NCD Upazila", captor.getValue().getFacilityName());
        assertNull(captor.getValue().getExpectedPatientsPerDay());
    }

    @Test
    void serviceRequest_noEnvelopeFacilityId_extractsIdFromReference() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Location/1302", "display": "NCD Upazila" }]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityReferenceRepository.existsByFacilityId("1302")).thenReturn(false);
        when(facilityReferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.registerFacilityIfAbsent(event);

        ArgumentCaptor<FacilityReference> captor = ArgumentCaptor.forClass(FacilityReference.class);
        verify(facilityReferenceRepository).save(captor.capture());
        assertEquals("1302", captor.getValue().getFacilityId());
        assertEquals("NCD Upazila", captor.getValue().getFacilityName());
    }

    @Test
    void serviceRequest_referenceWithOrgPrefix_stripsPrefix() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Organization/999", "display": "District Hospital" }]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityReferenceRepository.existsByFacilityId("999")).thenReturn(false);
        when(facilityReferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.registerFacilityIfAbsent(event);

        ArgumentCaptor<FacilityReference> captor = ArgumentCaptor.forClass(FacilityReference.class);
        verify(facilityReferenceRepository).save(captor.capture());
        assertEquals("999", captor.getValue().getFacilityId());
    }

    @Test
    void serviceRequest_identifierFallback_whenReferenceAbsent() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "identifier": { "value": "FAC-007" }, "display": "Health Post" }]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityReferenceRepository.existsByFacilityId("FAC-007")).thenReturn(false);
        when(facilityReferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.registerFacilityIfAbsent(event);

        ArgumentCaptor<FacilityReference> captor = ArgumentCaptor.forClass(FacilityReference.class);
        verify(facilityReferenceRepository).save(captor.capture());
        assertEquals("FAC-007", captor.getValue().getFacilityId());
        assertEquals("Health Post", captor.getValue().getFacilityName());
    }

    // ── Encounter ──────────────────────────────────────────────────────────────

    @Test
    void encounter_extractsIdAndNameFromNestedLocation() throws Exception {
        String payload = """
                {
                  "resourceType": "Encounter",
                  "location": [{
                    "location": { "reference": "Location/0030", "display": "Kacyiru Health Center" }
                  }]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityReferenceRepository.existsByFacilityId("0030")).thenReturn(false);
        when(facilityReferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.registerFacilityIfAbsent(event);

        ArgumentCaptor<FacilityReference> captor = ArgumentCaptor.forClass(FacilityReference.class);
        verify(facilityReferenceRepository).save(captor.capture());
        assertEquals("0030", captor.getValue().getFacilityId());
        assertEquals("Kacyiru Health Center", captor.getValue().getFacilityName());
    }

    @Test
    void encounter_envelopeIdTakesPrecedenceOverPayloadReference() throws Exception {
        String payload = """
                {
                  "resourceType": "Encounter",
                  "location": [{
                    "location": { "reference": "Location/0030", "display": "Kacyiru Health Center" }
                  }]
                }
                """;
        // Envelope says "ENVELOPE-ID", payload reference says "0030" — envelope wins
        CloudEventMessage event = eventWith("ENVELOPE-ID", payload);
        when(facilityReferenceRepository.existsByFacilityId("ENVELOPE-ID")).thenReturn(false);
        when(facilityReferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.registerFacilityIfAbsent(event);

        ArgumentCaptor<FacilityReference> captor = ArgumentCaptor.forClass(FacilityReference.class);
        verify(facilityReferenceRepository).save(captor.capture());
        assertEquals("ENVELOPE-ID", captor.getValue().getFacilityId());
        assertEquals("Kacyiru Health Center", captor.getValue().getFacilityName());
    }

    // ── Procedure / Immunization (direct Reference) ────────────────────────────

    @Test
    void procedure_directLocationReference_extractsIdAndName() throws Exception {
        String payload = """
                {
                  "resourceType": "Procedure",
                  "location": { "reference": "Location/0030", "display": "Outpatient Clinic" }
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityReferenceRepository.existsByFacilityId("0030")).thenReturn(false);
        when(facilityReferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.registerFacilityIfAbsent(event);

        ArgumentCaptor<FacilityReference> captor = ArgumentCaptor.forClass(FacilityReference.class);
        verify(facilityReferenceRepository).save(captor.capture());
        assertEquals("0030", captor.getValue().getFacilityId());
        assertEquals("Outpatient Clinic", captor.getValue().getFacilityName());
    }

    @Test
    void immunization_directLocationReference_extractsIdAndName() throws Exception {
        String payload = """
                {
                  "resourceType": "Immunization",
                  "location": { "reference": "Location/0055", "display": "Vaccination Centre" }
                }
                """;
        CloudEventMessage event = eventWith(null, payload);
        when(facilityReferenceRepository.existsByFacilityId("0055")).thenReturn(false);
        when(facilityReferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.registerFacilityIfAbsent(event);

        ArgumentCaptor<FacilityReference> captor = ArgumentCaptor.forClass(FacilityReference.class);
        verify(facilityReferenceRepository).save(captor.capture());
        assertEquals("0055", captor.getValue().getFacilityId());
        assertEquals("Vaccination Centre", captor.getValue().getFacilityName());
    }

    // ── Idempotency ────────────────────────────────────────────────────────────

    @Test
    void alreadyExists_doesNotSaveAgain() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Location/1302", "display": "NCD Upazila" }]
                }
                """;
        CloudEventMessage event = eventWith("1302", payload);
        when(facilityReferenceRepository.existsByFacilityId("1302")).thenReturn(true);

        service.registerFacilityIfAbsent(event);

        verify(facilityReferenceRepository, never()).save(any());
    }

    // ── Skip conditions ────────────────────────────────────────────────────────

    @Test
    void noFacilityIdAndNoLocationInPayload_skips() throws Exception {
        String payload = """
                { "resourceType": "Observation", "status": "final" }
                """;
        CloudEventMessage event = eventWith(null, payload);

        service.registerFacilityIfAbsent(event);

        verify(facilityReferenceRepository, never()).existsByFacilityId(anyString());
        verify(facilityReferenceRepository, never()).save(any());
    }

    @Test
    void facilityIdPresentButNoDisplayName_skips() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Location/1302" }]
                }
                """;
        // No display field → can't determine name → skip
        CloudEventMessage event = eventWith(null, payload);

        service.registerFacilityIfAbsent(event);

        verify(facilityReferenceRepository, never()).save(any());
    }

    @Test
    void blankDisplayName_skips() throws Exception {
        String payload = """
                {
                  "resourceType": "ServiceRequest",
                  "locationReference": [{ "reference": "Location/1302", "display": "   " }]
                }
                """;
        CloudEventMessage event = eventWith(null, payload);

        service.registerFacilityIfAbsent(event);

        verify(facilityReferenceRepository, never()).save(any());
    }

    @Test
    void nullData_skips() {
        CloudEventMessage event = CloudEventMessage.builder()
                .facilityid(null)
                .data(null)
                .build();

        service.registerFacilityIfAbsent(event);

        verify(facilityReferenceRepository, never()).save(any());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private CloudEventMessage eventWith(String facilityId, String json) throws Exception {
        return CloudEventMessage.builder()
                .facilityid(facilityId)
                .data(mapper.readTree(json))
                .build();
    }
}
