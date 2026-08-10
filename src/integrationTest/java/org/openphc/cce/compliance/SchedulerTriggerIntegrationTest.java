package org.openphc.cce.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.entity.Deviation;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.DeviationType;
import org.openphc.cce.compliance.domain.enums.StepState;
import org.openphc.cce.compliance.domain.repository.DeviationRepository;
import org.openphc.cce.compliance.domain.repository.ComplianceEventLogRepository;
import org.openphc.cce.compliance.domain.repository.ProtocolInstanceRepository;
import org.openphc.cce.compliance.domain.repository.StepInstanceRepository;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.openphc.cce.compliance.kafka.model.SchedulerTriggerMessage;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for scheduler-driven step state transitions via Kafka.
 * Verifies PENDING→DUE and DUE→MISSED (with deviation) / DUE→SKIPPED for optional steps,
 * plus the legacy handling that keeps rows and triggers from before the removal of
 * DUE→OVERDUE from being stranded.
 */
class SchedulerTriggerIntegrationTest extends IntegrationTestBase {

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

    @Value("${cce.kafka.topics.inbound-events}")
    private String inboundTopic;

    @Value("${cce.kafka.topics.scheduler-triggers}")
    private String schedulerTopic;

    /**
     * Loads the protocol once and enrolls a patient by sending an Encounter event.
     * Returns the step instance ID for subsequent scheduler trigger tests.
     */
    private StepInstance enrollAndGetStep(String patientId) throws Exception {
        // Load protocol
        String planDefJson = Files.readString(Path.of("src/test/resources/fhir/plan-definition-anc-high-risk.json"));
        String modifiedJson = planDefJson.replace("\"version\": \"1.0.0\"",
                "\"version\": \"20.0.0-sched-" + UUID.randomUUID().toString().substring(0, 8) + "\"");
        String requestBody = objectMapper.writeValueAsString(
                Map.of("planDefinitionJson", modifiedJson));

        mockMvc.perform(post("/v1/compliance/protocol-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        // Send Encounter event to enroll patient via "any-encounter-log" match
        String eventId = "sched-enroll-" + UUID.randomUUID();
        ObjectNode data = objectMapper.createObjectNode();
        data.put("resourceType", "Encounter");
        data.put("status", "in-progress");

        CloudEventMessage event = CloudEventMessage.builder()
                .id(eventId)
                .source("integration-test-scheduler")
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

        // Wait for enrollment + step creation
        await().atMost(30, SECONDS).untilAsserted(() -> {
            List<ProtocolInstance> instances = protocolInstanceRepository.findAll().stream()
                    .filter(p -> patientId.equals(p.getPatientId())).toList();
            assertThat(instances).isNotEmpty();
            List<StepInstance> steps = stepInstanceRepository.findByProtocolInstanceId(instances.get(0).getId());
            assertThat(steps).isNotEmpty();
        });

        List<ProtocolInstance> instances = protocolInstanceRepository.findAll().stream()
                .filter(p -> patientId.equals(p.getPatientId())).toList();
        List<StepInstance> steps = stepInstanceRepository.findByProtocolInstanceId(instances.get(0).getId());
        // Return the first step (any-encounter-log) in PENDING or COMPLETED state
        return steps.get(0);
    }

    @Test
    void pendingToDue_transitionsStep() throws Exception {
        String patientId = "patient-sched-due-" + UUID.randomUUID();
        StepInstance step = enrollAndGetStep(patientId);

        // The step from matchingEvent completion is COMPLETED, so we need one in PENDING.
        // Since "any-encounter-log" is F1-only with no related actions / recurring, the step is auto-completed.
        // Instead, we directly create a PENDING step for test via the service.
        // But in integration test mode we can't easily inject... let's work around this.

        // Actually, the step was created AND completed in one flow (completeStep is called after createInitialStep).
        // Check if the blood-pressure-check dependent step was created (relatedAction: after-end, 7d).
        // The "any-encounter-log" action has NO relatedActions, so no dependent step exists.

        // For a proper test, let's use the initial-enrollment path which creates dependent steps.
        // But initial-enrollment requires type+serviceType matching which won't work due to extractor limitations.

        // Alternative: manually insert a PENDING step using repositories.
        List<ProtocolInstance> instances = protocolInstanceRepository.findAll().stream()
                .filter(p -> patientId.equals(p.getPatientId())).toList();
        StepInstance pendingStep = StepInstance.builder()
                .protocolInstance(instances.get(0))
                .actionId("test-scheduler-action")
                .repeatIndex(0)
                .state(StepState.PENDING)
                .dueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))
                .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(3))
                .missedDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                .requiredBehavior("must")
                .build();
        pendingStep = stepInstanceRepository.save(pendingStep);

        UUID stepId = pendingStep.getId();

        // Send PENDING_TO_DUE scheduler trigger
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
    }

    @Test
    void dueToMissed_createsDeviation() throws Exception {
        String patientId = "patient-sched-missed-" + UUID.randomUUID();
        enrollAndGetStep(patientId);

        List<ProtocolInstance> instances = protocolInstanceRepository.findAll().stream()
                .filter(p -> patientId.equals(p.getPatientId())).toList();
        StepInstance dueStep = StepInstance.builder()
                .protocolInstance(instances.get(0))
                .actionId("test-missed-action")
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

        // Verify deviation
        List<Deviation> deviations = deviationRepository.findAll().stream()
                .filter(d -> d.getProtocolInstance().getId().equals(instances.get(0).getId())).toList();
        assertThat(deviations).anyMatch(d ->
                d.getStepInstance().getId().equals(stepId) &&
                d.getDeviationType() == DeviationType.MISSED);
    }

    @Test
    void dueToMissed_legacyOverdueStep_stillTerminalizes() throws Exception {
        // An adopted environment can still hold rows in the retired OVERDUE state. The
        // V8 reconciliation moves them back to DUE so the Scheduler scans them again, and
        // OVERDUE is accepted as a source state here so one it did not catch — written by an
        // old Compliance instance mid rolling-deploy — terminalizes rather than being stranded.
        String patientId = "patient-sched-legacy-overdue-" + UUID.randomUUID();
        enrollAndGetStep(patientId);

        List<ProtocolInstance> instances = protocolInstanceRepository.findAll().stream()
                .filter(p -> patientId.equals(p.getPatientId())).toList();
        StepInstance overdueStep = StepInstance.builder()
                .protocolInstance(instances.get(0))
                .actionId("test-legacy-overdue-action")
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

        List<Deviation> deviations = deviationRepository.findAll().stream()
                .filter(d -> d.getProtocolInstance().getId().equals(instances.get(0).getId())).toList();
        assertThat(deviations).anyMatch(d ->
                d.getStepInstance().getId().equals(stepId) &&
                d.getDeviationType() == DeviationType.MISSED);
    }

    @Test
    void legacyDueToOverdue_isIgnored_leavingStepDue() throws Exception {
        // The DUE -> OVERDUE transition is removed. A trigger a pre-upgrade Scheduler left in
        // flight must be dropped: the step stays DUE, awaiting its own DUE_TO_MISSED.
        String patientId = "patient-sched-legacy-trigger-" + UUID.randomUUID();
        enrollAndGetStep(patientId);

        List<ProtocolInstance> instances = protocolInstanceRepository.findAll().stream()
                .filter(p -> patientId.equals(p.getPatientId())).toList();
        StepInstance dueStep = StepInstance.builder()
                .protocolInstance(instances.get(0))
                .actionId("test-legacy-trigger-action")
                .repeatIndex(0)
                .state(StepState.DUE)
                .dueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(5))
                .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))
                .missedDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(3))
                .requiredBehavior("must")
                .build();
        dueStep = stepInstanceRepository.save(dueStep);

        UUID stepId = dueStep.getId();

        SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                .stepInstanceId(stepId)
                .transitionType("DUE_TO_OVERDUE")
                .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                .correlationid(UUID.randomUUID().toString())
                .build();

        kafkaTemplate.send(schedulerTopic, trigger);

        // Nothing to await on — assert the trigger stayed inert after the consumer has had
        // time to process it.
        Thread.sleep(5000);

        StepInstance updated = stepInstanceRepository.findById(stepId).orElseThrow();
        assertThat(updated.getState()).isEqualTo(StepState.DUE);
        assertThat(deviationRepository.findAll())
                .noneMatch(d -> d.getStepInstance().getId().equals(stepId));
    }

    @Test
    void dueToMissed_optionalStep_becomesSkipped() throws Exception {
        String patientId = "patient-sched-skip-" + UUID.randomUUID();
        enrollAndGetStep(patientId);

        List<ProtocolInstance> instances = protocolInstanceRepository.findAll().stream()
                .filter(p -> patientId.equals(p.getPatientId())).toList();
        StepInstance optionalStep = StepInstance.builder()
                .protocolInstance(instances.get(0))
                .actionId("test-optional-action")
                .repeatIndex(0)
                .state(StepState.DUE)
                .dueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(10))
                .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(5))
                .missedDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))
                .requiredBehavior("could")
                .build();
        optionalStep = stepInstanceRepository.save(optionalStep);

        UUID stepId = optionalStep.getId();

        SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                .stepInstanceId(stepId)
                .transitionType("DUE_TO_MISSED")
                .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                .correlationid(UUID.randomUUID().toString())
                .build();

        kafkaTemplate.send(schedulerTopic, trigger);

        await().atMost(30, SECONDS).untilAsserted(() -> {
            StepInstance updated = stepInstanceRepository.findById(stepId).orElseThrow();
            // Optional (could) steps get SKIPPED instead of MISSED
            assertThat(updated.getState()).isEqualTo(StepState.SKIPPED);
        });
    }
}
