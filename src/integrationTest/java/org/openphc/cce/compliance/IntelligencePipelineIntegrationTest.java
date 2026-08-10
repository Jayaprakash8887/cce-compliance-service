package org.openphc.cce.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.entity.Deviation;
import org.openphc.cce.compliance.domain.entity.IntelligenceEventLog;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.DeviationType;
import org.openphc.cce.compliance.domain.enums.StepState;
import org.openphc.cce.compliance.domain.repository.*;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.openphc.cce.compliance.kafka.model.SchedulerTriggerMessage;
import org.openphc.cce.compliance.service.ActionDefinitionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end integration tests for the intelligence pipeline:
 * Event → Match → Enroll → Scheduler transition → Deviation → Intelligence action evaluation
 * → IntelligenceEventLog created → IntelligenceTriggerEvent published to Kafka
 *
 * Also covers Intelligence Event Log API endpoints (GET list + GET by ID).
 */
class IntelligencePipelineIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ProtocolInstanceRepository protocolInstanceRepository;

    @Autowired
    private StepInstanceRepository stepInstanceRepository;

    @Autowired
    private DeviationRepository deviationRepository;

    @Autowired
    private IntelligenceEventLogRepository intelligenceEventLogRepository;

    @Autowired
    private ActionDefinitionService actionDefinitionService;

    @Value("${cce.kafka.topics.inbound-events}")
    private String inboundTopic;

    @Value("${cce.kafka.topics.scheduler-triggers}")
    private String schedulerTopic;

    private String protocolVersion;

    @BeforeEach
    void loadProtocolAndActionDefinition() throws Exception {
        protocolVersion = "1.0.0-intel-" + UUID.randomUUID().toString().substring(0, 8);

        // Load PlanDefinition with intelligence actions
        String planDefJson = Files.readString(
                Path.of("src/integrationTest/resources/fhir/plan-definition-with-intelligence.json"));
        String modifiedPlanDef = planDefJson.replace("\"version\": \"1.0.0\"",
                "\"version\": \"" + protocolVersion + "\"");
        String protocolRequest = objectMapper.writeValueAsString(
                Map.of("planDefinitionJson", modifiedPlanDef));

        mockMvc.perform(post("/v1/compliance/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(protocolRequest))
                .andExpect(status().isCreated());

        // Create matching ActionDefinition (url|version must match definitionCanonical in PlanDefinition)
        String actDefJson = Files.readString(
                Path.of("src/integrationTest/resources/fhir/activity-definition-escalation.json"));

        // Only create if not already present (shared across tests in this class)
        try {
            actionDefinitionService.resolveByCanonical(
                    "http://openphc.org/ActivityDefinition/escalation-alert|1.0.0");
        } catch (Exception e) {
            actionDefinitionService.createActionDefinition(objectMapper.readTree(actDefJson));
        }
    }

    /**
     * Sends an Encounter event to enroll a patient and waits for enrollment
     * in the intelligence protocol specifically.
     * Returns the protocol instance for the intelligence PlanDefinition.
     */
    private ProtocolInstance enrollAndWait(String patientId) throws Exception {
        String expectedCanonical = "http://openphc.org/PlanDefinition/anc-intelligence-integration|" + protocolVersion;

        ObjectNode data = objectMapper.createObjectNode();
        data.put("resourceType", "Encounter");
        data.put("status", "in-progress");

        CloudEventMessage event = CloudEventMessage.builder()
                .id("intel-enroll-" + UUID.randomUUID())
                .source("integration-test-intelligence")
                .type("org.openphc.fhir.Encounter.create")
                .specversion("1.0")
                .subject(patientId)
                .time(OffsetDateTime.now(ZoneOffset.UTC))
                .datacontenttype("application/json")
                .correlationid(UUID.randomUUID().toString())
                .facilityid("facility-1")
                .data(data)
                .build();

        kafkaTemplate.send(inboundTopic, event);

        await().atMost(30, SECONDS).untilAsserted(() -> {
            List<ProtocolInstance> instances = protocolInstanceRepository.findAll().stream()
                    .filter(p -> patientId.equals(p.getPatientId())).toList();
            assertThat(instances).anyMatch(i -> i.getProtocolCanonical().equals(expectedCanonical));
        });

        return protocolInstanceRepository.findAll().stream()
                .filter(p -> patientId.equals(p.getPatientId()))
                .filter(i -> i.getProtocolCanonical().equals(expectedCanonical))
                .findFirst().orElseThrow();
    }

    // ── Intelligence Pipeline Tests ──

    @Nested
    class IntelligenceEvaluation {

        @Test
        void dueToMissed_evaluatesMissedAction_createsEventLog() throws Exception {
            String patientId = "patient-intel-missed-" + UUID.randomUUID();
            ProtocolInstance protocolInstance = enrollAndWait(patientId);
            UUID protocolInstanceId = protocolInstance.getId();

            // A DUE step whose missedDate has passed — the only deviation-raising transition
            // left now that DUE -> OVERDUE is removed.
            StepInstance dueStep = StepInstance.builder()
                    .protocolInstance(protocolInstance)
                    .actionId("encounter-step")
                    .repeatIndex(0)
                    .state(StepState.DUE)
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(10))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(5))
                    .missedDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))
                    .requiredBehavior("must")
                    .build();
            dueStep = stepInstanceRepository.save(dueStep);

            UUID stepId = dueStep.getId();

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("DUE_TO_MISSED")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .correlationid(UUID.randomUUID().toString())
                    .build();

            kafkaTemplate.send(schedulerTopic, trigger);

            await().atMost(30, SECONDS).untilAsserted(() -> {
                StepInstance updated = stepInstanceRepository.findById(stepId).orElseThrow();
                assertThat(updated.getState()).isEqualTo(StepState.MISSED);
            });

            // Verify deviation was created
            List<Deviation> deviations = deviationRepository.findAll().stream()
                    .filter(d -> d.getProtocolInstance().getId().equals(protocolInstanceId)).toList();
            assertThat(deviations).anyMatch(d ->
                    d.getStepInstance().getId().equals(stepId) &&
                            d.getDeviationType() == DeviationType.MISSED);

            // Verify IntelligenceEventLog was created by intelligence evaluation
            await().atMost(10, SECONDS).untilAsserted(() -> {
                List<IntelligenceEventLog> eventLogs = intelligenceEventLogRepository.findByStepInstanceId(stepId);
                assertThat(eventLogs).isNotEmpty();
            });

            // Exactly one: "missed-critical-alert" matches deviationType == "missed", while the
            // sibling "overdue-escalation" action does not fire.
            List<IntelligenceEventLog> eventLogs = intelligenceEventLogRepository.findByStepInstanceId(stepId);
            assertThat(eventLogs).hasSize(1);

            IntelligenceEventLog eventLog = eventLogs.get(0);
            assertThat(eventLog.isPublished()).isTrue();
            assertThat(eventLog.getPublishedAt()).isNotNull();
            assertThat(eventLog.getTriggerReason()).isEqualTo("missed");
            assertThat(eventLog.getStepActionId()).isEqualTo("missed-critical-alert");

            // Verify full event log details via REST API
            mockMvc.perform(get("/v1/compliance/intelligence-events/{id}", eventLog.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.published").value(true))
                    .andExpect(jsonPath("$.eventPayload").isNotEmpty())
                    .andExpect(jsonPath("$.protocolInstanceId").value(protocolInstanceId.toString()))
                    .andExpect(jsonPath("$.stepInstanceId").value(stepId.toString()))
                    .andExpect(jsonPath("$.triggerReason").value("missed"))
                    .andExpect(jsonPath("$.stepActionId").value("missed-critical-alert"))
                    .andExpect(jsonPath("$.evaluationContext").isNotEmpty());

            // Verify deviation.intelligenceEventId was set
            List<Deviation> updatedDeviations = deviationRepository.findAll().stream()
                    .filter(d -> d.getProtocolInstance().getId().equals(protocolInstanceId)).toList();
            Deviation deviation = updatedDeviations.stream()
                    .filter(d -> d.getStepInstance().getId().equals(stepId))
                    .findFirst().orElseThrow();
            assertThat(deviation.getIntelligenceEventId()).isNotNull();
        }

        @Test
        void dueToMissed_legacyOverdueStep_stillEvaluatesMissedAction() throws Exception {
            // A row an adopted environment left in the retired OVERDUE state must still
            // terminalize and run intelligence evaluation, not be stranded.
            String patientId = "patient-intel-legacy-overdue-" + UUID.randomUUID();
            ProtocolInstance protocolInstance = enrollAndWait(patientId);

            StepInstance overdueStep = StepInstance.builder()
                    .protocolInstance(protocolInstance)
                    .actionId("encounter-step")
                    .repeatIndex(0)
                    .state(StepState.OVERDUE)
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(10))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(5))
                    .missedDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))
                    .requiredBehavior("must")
                    .build();
            overdueStep = stepInstanceRepository.save(overdueStep);

            UUID stepId = overdueStep.getId();

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("DUE_TO_MISSED")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .correlationid(UUID.randomUUID().toString())
                    .build();

            kafkaTemplate.send(schedulerTopic, trigger);

            await().atMost(30, SECONDS).untilAsserted(() -> {
                StepInstance updated = stepInstanceRepository.findById(stepId).orElseThrow();
                assertThat(updated.getState()).isEqualTo(StepState.MISSED);
            });

            await().atMost(10, SECONDS).untilAsserted(() -> {
                List<IntelligenceEventLog> eventLogs = intelligenceEventLogRepository.findByStepInstanceId(stepId);
                assertThat(eventLogs).isNotEmpty();
            });

            IntelligenceEventLog eventLog = intelligenceEventLogRepository.findByStepInstanceId(stepId).get(0);
            assertThat(eventLog.getTriggerReason()).isEqualTo("missed");
            assertThat(eventLog.getStepActionId()).isEqualTo("missed-critical-alert");
        }

        @Test
        void noMatchingIntelligenceAction_noEventLogCreated() throws Exception {
            String patientId = "patient-intel-nomatch-" + UUID.randomUUID();
            ProtocolInstance protocolInstance = enrollAndWait(patientId);

            // Create a PENDING step that transitions to DUE — no deviation, no intelligence eval
            StepInstance pendingStep = StepInstance.builder()
                    .protocolInstance(protocolInstance)
                    .actionId("encounter-step")
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(3))
                    .missedDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                    .requiredBehavior("must")
                    .build();
            pendingStep = stepInstanceRepository.save(pendingStep);

            UUID stepId = pendingStep.getId();

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("PENDING_TO_DUE")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .correlationid(UUID.randomUUID().toString())
                    .build();

            kafkaTemplate.send(schedulerTopic, trigger);

            await().atMost(30, SECONDS).untilAsserted(() -> {
                StepInstance updated = stepInstanceRepository.findById(stepId).orElseThrow();
                assertThat(updated.getState()).isEqualTo(StepState.DUE);
            });

            // PENDING→DUE does not create a deviation, so no intelligence actions should fire
            List<IntelligenceEventLog> eventLogs = intelligenceEventLogRepository.findByStepInstanceId(stepId);
            assertThat(eventLogs).isEmpty();
        }
    }

    // ── Intelligence Event Log API Tests ──

    @Nested
    class IntelligenceEventLogApi {

        @Test
        void getEventLogById_returnsCorrectData() throws Exception {
            String patientId = "patient-intel-api-get-" + UUID.randomUUID();
            ProtocolInstance protocolInstance = enrollAndWait(patientId);
            UUID protocolInstanceId = protocolInstance.getId();

            // Trigger intelligence action via DUE_TO_MISSED
            StepInstance dueStep = StepInstance.builder()
                    .protocolInstance(protocolInstance)
                    .actionId("encounter-step")
                    .repeatIndex(0)
                    .state(StepState.DUE)
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(10))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(5))
                    .missedDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))
                    .requiredBehavior("must")
                    .build();
            dueStep = stepInstanceRepository.save(dueStep);
            UUID stepId = dueStep.getId();

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("DUE_TO_MISSED")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .correlationid(UUID.randomUUID().toString())
                    .build();

            kafkaTemplate.send(schedulerTopic, trigger);

            await().atMost(30, SECONDS).untilAsserted(() -> {
                List<IntelligenceEventLog> eventLogs = intelligenceEventLogRepository.findByStepInstanceId(stepId);
                assertThat(eventLogs).isNotEmpty();
            });

            IntelligenceEventLog eventLog = intelligenceEventLogRepository.findByStepInstanceId(stepId).get(0);

            // GET by ID
            mockMvc.perform(get("/v1/compliance/intelligence-events/{id}", eventLog.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(eventLog.getId().toString()))
                    .andExpect(jsonPath("$.published").value(true))
                    .andExpect(jsonPath("$.eventPayload").isNotEmpty())
                    .andExpect(jsonPath("$.protocolInstanceId").value(protocolInstanceId.toString()))
                    .andExpect(jsonPath("$.stepInstanceId").value(stepId.toString()))
                    .andExpect(jsonPath("$.triggerReason").value("missed"))
                    .andExpect(jsonPath("$.stepActionId").value("missed-critical-alert"));
        }

        @Test
        void listEventLogs_filterByProtocolInstanceId() throws Exception {
            String patientId = "patient-intel-api-list-" + UUID.randomUUID();
            ProtocolInstance protocolInstance = enrollAndWait(patientId);
            UUID protocolInstanceId = protocolInstance.getId();

            StepInstance dueStep = StepInstance.builder()
                    .protocolInstance(protocolInstance)
                    .actionId("encounter-step")
                    .repeatIndex(0)
                    .state(StepState.DUE)
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(10))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(5))
                    .missedDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))
                    .requiredBehavior("must")
                    .build();
            dueStep = stepInstanceRepository.save(dueStep);
            UUID stepId = dueStep.getId();

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("DUE_TO_MISSED")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .correlationid(UUID.randomUUID().toString())
                    .build();

            kafkaTemplate.send(schedulerTopic, trigger);

            await().atMost(30, SECONDS).untilAsserted(() -> {
                List<IntelligenceEventLog> eventLogs = intelligenceEventLogRepository.findByStepInstanceId(stepId);
                assertThat(eventLogs).isNotEmpty();
            });

            // GET list filtered by protocolInstanceId
            mockMvc.perform(get("/v1/compliance/intelligence-events")
                            .param("protocolInstanceId", protocolInstanceId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].protocolInstanceId").value(protocolInstanceId.toString()))
                    .andExpect(jsonPath("$[0].published").value(true));
        }

        @Test
        void getEventLogById_notFound_returns404() throws Exception {
            mockMvc.perform(get("/v1/compliance/intelligence-events/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }
    }
}
