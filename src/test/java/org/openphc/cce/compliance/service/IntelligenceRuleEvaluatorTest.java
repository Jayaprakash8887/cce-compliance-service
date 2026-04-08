package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityNotFoundException;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.domain.entity.*;
import org.openphc.cce.compliance.domain.enums.*;
import org.openphc.cce.compliance.domain.repository.ActionRunRepository;
import org.openphc.cce.compliance.domain.repository.DeviationRepository;
import org.openphc.cce.compliance.fhir.ExpressionEvaluationService;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;
import org.openphc.cce.compliance.kafka.model.IntelligenceTriggerEvent;
import org.openphc.cce.compliance.kafka.producer.IntelligenceTriggerProducer;
import org.springframework.kafka.support.SendResult;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntelligenceRuleEvaluatorTest {

    @Mock private PlanDefinitionParser planDefinitionParser;
    @Mock private ExpressionEvaluationService expressionEvaluationService;
    @Mock private ActionDefinitionService actionDefinitionService;
    @Mock private IntelligenceTriggerProducer intelligenceTriggerProducer;
    @Mock private ActionRunRepository actionRunRepository;
    @Mock private DeviationRepository deviationRepository;

    private IntelligenceRuleEvaluator evaluator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        evaluator = new IntelligenceRuleEvaluator(
                planDefinitionParser, expressionEvaluationService,
                actionDefinitionService, intelligenceTriggerProducer,
                actionRunRepository, deviationRepository, objectMapper);
    }

    // ── Deviation Tests ──

    @Nested
    class EvaluateOnDeviation {

        @Test
        void matchingRule_createsActionRunAndPublishesEvent() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsRules(step, List.of(
                    buildRule("rule-1", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(expressionEvaluationService.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 1]}"), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical("http://openphc.org/ActivityDefinition/alert|1.0"))
                    .thenReturn(actionDef);
            when(actionRunRepository.save(any(ActionRun.class))).thenAnswer(i -> {
                ActionRun ar = i.getArgument(0);
                if (ar.getId() == null) ar.setId(UUID.randomUUID());
                return ar;
            });
            when(intelligenceTriggerProducer.publish(any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            List<ActionRun> result = evaluator.evaluateOnDeviation(step, deviation);

            assertEquals(1, result.size());
            ActionRun actionRun = result.get(0);
            assertEquals(ActionRunStatus.PUBLISHED, actionRun.getStatus());
            assertEquals("rule-1", actionRun.getRuleId());
            assertEquals("overdue", actionRun.getTriggerReason());
            assertNotNull(actionRun.getIntelligenceEventId());

            // Verify event was published
            ArgumentCaptor<IntelligenceTriggerEvent> eventCaptor =
                    ArgumentCaptor.forClass(IntelligenceTriggerEvent.class);
            verify(intelligenceTriggerProducer).publish(eventCaptor.capture());

            IntelligenceTriggerEvent event = eventCaptor.getValue();
            assertEquals(step.getProtocolInstance().getId(), event.getProtocolInstanceId());
            assertEquals(step.getId(), event.getStepInstanceId());
            assertEquals(deviation.getId(), event.getDeviationId());
            assertEquals("overdue", event.getDeviationType());
            assertEquals("OVERDUE", event.getStepState());
            assertEquals("bp-check", event.getActionId());

            // Verify deviation.intelligenceEventId was set
            verify(deviationRepository).save(deviation);
            assertNotNull(deviation.getIntelligenceEventId());

            // ActionRun saved twice (TRIGGERED → PUBLISHED)
            verify(actionRunRepository, times(2)).save(any(ActionRun.class));
        }

        @Test
        void nonMatchingRule_noActionRunCreated() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            mockParserReturnsRules(step, List.of(
                    buildRule("rule-1", "text/jsonlogic", "{\">\": [{\"var\": \"daysOverdue\"}, 30]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(false);

            List<ActionRun> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
            verify(actionRunRepository, never()).save(any());
            verify(intelligenceTriggerProducer, never()).publish(any());
        }

        @Test
        void multipleRules_someMatchSomeDont() {
            StepInstance step = buildStep("bp-check", StepState.MISSED);
            Deviation deviation = buildDeviation(step, DeviationType.MISSED);
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsRules(step, List.of(
                    buildRule("rule-match", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0"),
                    buildRule("rule-skip", "text/jsonlogic", "{\"==\": [1, 0]}",
                            "http://openphc.org/ActivityDefinition/other|1.0"),
                    buildRule("rule-match-2", "text/fhirpath", "true",
                            "http://openphc.org/ActivityDefinition/escalation|1.0")));

            when(expressionEvaluationService.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 1]}"), any()))
                    .thenReturn(true);
            when(expressionEvaluationService.evaluate(eq("text/jsonlogic"), eq("{\"==\": [1, 0]}"), any()))
                    .thenReturn(false);
            when(expressionEvaluationService.evaluate(eq("text/fhirpath"), eq("true"), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical(anyString())).thenReturn(actionDef);
            when(actionRunRepository.save(any(ActionRun.class))).thenAnswer(i -> {
                ActionRun ar = i.getArgument(0);
                if (ar.getId() == null) ar.setId(UUID.randomUUID());
                return ar;
            });
            when(intelligenceTriggerProducer.publish(any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            List<ActionRun> result = evaluator.evaluateOnDeviation(step, deviation);

            assertEquals(2, result.size());
            assertEquals("rule-match", result.get(0).getRuleId());
            assertEquals("rule-match-2", result.get(1).getRuleId());

            verify(intelligenceTriggerProducer, times(2)).publish(any());
        }

        @Test
        void missingActionDefinition_ruleSkippedAndLogged() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            mockParserReturnsRules(step, List.of(
                    buildRule("rule-1", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/missing|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical("http://openphc.org/ActivityDefinition/missing|1.0"))
                    .thenThrow(new EntityNotFoundException("not found"));

            List<ActionRun> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
            verify(actionRunRepository, never()).save(any());
        }

        @Test
        void noRulesOnStep_returnsEmptyList() {
            StepInstance step = buildStep("simple-step", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            mockParserReturnsRules(step, List.of());

            List<ActionRun> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
            verify(expressionEvaluationService, never()).evaluate(anyString(), anyString(), any());
        }

        @Test
        void deviationContextContainsExpectedVariables() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            step.setDueDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(5));
            step.setMissedDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            mockParserReturnsRules(step, List.of(
                    buildRule("rule-1", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            // Capture the context passed to evaluate
            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(false);

            evaluator.evaluateOnDeviation(step, deviation);

            ArgumentCaptor<JsonNode> contextCaptor = ArgumentCaptor.forClass(JsonNode.class);
            verify(expressionEvaluationService).evaluate(anyString(), anyString(), contextCaptor.capture());

            JsonNode context = contextCaptor.getValue();
            assertEquals("overdue", context.get("stepState").asText());
            assertEquals("overdue", context.get("deviationType").asText());
            assertEquals("bp-check", context.get("actionId").asText());
            assertEquals(0, context.get("repeatIndex").asInt());
            assertTrue(context.has("daysOverdue"));
            assertTrue(context.get("daysOverdue").asLong() >= 4); // ~5 days ago
            assertTrue(context.has("dueDate"));
            assertTrue(context.has("daysPastMissedDate"));
        }
    }

    // ── Completion Tests ──

    @Nested
    class EvaluateOnCompletion {

        @Test
        void completionWithMatchingRule_createsActionRun() {
            StepInstance step = buildStep("bp-check", StepState.COMPLETED);
            step.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            step.setCompletionStatus(CompletionStatus.LATE);
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsRules(step, List.of(
                    buildRule("late-notify", "text/jsonlogic",
                            "{\"==\": [{\"var\": \"completionStatus\"}, \"late\"]}",
                            "http://openphc.org/ActivityDefinition/late-alert|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical(anyString())).thenReturn(actionDef);
            when(actionRunRepository.save(any(ActionRun.class))).thenAnswer(i -> {
                ActionRun ar = i.getArgument(0);
                if (ar.getId() == null) ar.setId(UUID.randomUUID());
                return ar;
            });
            when(intelligenceTriggerProducer.publish(any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            List<ActionRun> result = evaluator.evaluateOnCompletion(step);

            assertEquals(1, result.size());
            assertEquals("completion", result.get(0).getTriggerReason());
            assertEquals("late-notify", result.get(0).getRuleId());
            assertNull(result.get(0).getDeviation());

            // No deviation to update
            verify(deviationRepository, never()).save(any());
        }

        @Test
        void completionContextContainsExpectedVariables() {
            StepInstance step = buildStep("bp-check", StepState.COMPLETED);
            step.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            step.setCompletionStatus(CompletionStatus.ON_TIME);
            step.setDueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));

            mockParserReturnsRules(step, List.of(
                    buildRule("rule-1", "text/jsonlogic", "{\"==\": [1, 0]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(false);

            evaluator.evaluateOnCompletion(step);

            ArgumentCaptor<JsonNode> contextCaptor = ArgumentCaptor.forClass(JsonNode.class);
            verify(expressionEvaluationService).evaluate(anyString(), anyString(), contextCaptor.capture());

            JsonNode context = contextCaptor.getValue();
            assertEquals("completed", context.get("stepState").asText());
            assertEquals("bp-check", context.get("actionId").asText());
            assertEquals("on_time", context.get("completionStatus").asText());
            assertTrue(context.has("completedAt"));
            assertTrue(context.has("dueDate"));
            assertFalse(context.has("deviationType"));
        }

        @Test
        void completionNoRules_returnsEmpty() {
            StepInstance step = buildStep("simple-step", StepState.COMPLETED);

            mockParserReturnsRules(step, List.of());

            List<ActionRun> result = evaluator.evaluateOnCompletion(step);

            assertTrue(result.isEmpty());
        }
    }

    // ── Edge Cases ──

    @Nested
    class EdgeCases {

        @Test
        void conditionEvaluationError_ruleSkipped() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            mockParserReturnsRules(step, List.of(
                    buildRule("error-rule", "text/jsonlogic", "invalid-expr",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenThrow(new RuntimeException("parse error"));

            List<ActionRun> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
            verify(actionRunRepository, never()).save(any());
        }

        @Test
        void actionNotFoundInPlanDefinition_returnsEmpty() {
            StepInstance step = buildStep("nonexistent-action", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);

            // Parser returns actions but none match the step's actionId
            var mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);
            when(planDefinitionParser.extractActions(mockPlanDef)).thenReturn(List.of(
                    new PlanDefinitionParser.ActionMetadata("other-action", "Other",
                            List.of(), List.of(), null, null, null, List.of())));

            List<ActionRun> result = evaluator.evaluateOnDeviation(step, deviation);

            assertTrue(result.isEmpty());
        }

        @Test
        void deviationAlreadyHasIntelligenceEventId_notOverwritten() {
            StepInstance step = buildStep("bp-check", StepState.OVERDUE);
            Deviation deviation = buildDeviation(step, DeviationType.OVERDUE);
            UUID existingEventId = UUID.randomUUID();
            deviation.setIntelligenceEventId(existingEventId);
            ActionDefinition actionDef = buildActionDefinition();

            mockParserReturnsRules(step, List.of(
                    buildRule("rule-1", "text/jsonlogic", "{\"==\": [1, 1]}",
                            "http://openphc.org/ActivityDefinition/alert|1.0")));

            when(expressionEvaluationService.evaluate(anyString(), anyString(), any()))
                    .thenReturn(true);
            when(actionDefinitionService.resolveByCanonical(anyString())).thenReturn(actionDef);
            when(actionRunRepository.save(any(ActionRun.class))).thenAnswer(i -> {
                ActionRun ar = i.getArgument(0);
                if (ar.getId() == null) ar.setId(UUID.randomUUID());
                return ar;
            });
            when(intelligenceTriggerProducer.publish(any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            evaluator.evaluateOnDeviation(step, deviation);

            // Should not overwrite existing intelligenceEventId
            verify(deviationRepository, never()).save(any());
            assertEquals(existingEventId, deviation.getIntelligenceEventId());
        }
    }

    // ── Helpers ──

    private StepInstance buildStep(String actionId, StepState state) {
        ProtocolDefinition protocolDef = ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://openphc.org/PlanDefinition/test")
                .version("1.0.0")
                .status(ProtocolDefinitionStatus.ACTIVE)
                .definition(objectMapper.createObjectNode().put("resourceType", "PlanDefinition"))
                .loadedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        ProtocolInstance protocolInstance = ProtocolInstance.builder()
                .id(UUID.randomUUID())
                .patientId("patient-1")
                .protocolDefinition(protocolDef)
                .protocolCanonical("http://openphc.org/PlanDefinition/test|1.0.0")
                .status(ProtocolInstanceStatus.ACTIVE)
                .enrolledAt(OffsetDateTime.now(ZoneOffset.UTC))
                .steps(new HashSet<>())
                .deviations(new HashSet<>())
                .build();

        return StepInstance.builder()
                .id(UUID.randomUUID())
                .protocolInstance(protocolInstance)
                .actionId(actionId)
                .repeatIndex(0)
                .state(state)
                .dueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(10))
                .build();
    }

    private Deviation buildDeviation(StepInstance step, DeviationType type) {
        return Deviation.builder()
                .id(UUID.randomUUID())
                .protocolInstance(step.getProtocolInstance())
                .stepInstance(step)
                .deviationType(type)
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private ActionDefinition buildActionDefinition() {
        return ActionDefinition.builder()
                .id(UUID.randomUUID())
                .canonicalUrl("http://openphc.org/ActivityDefinition/alert")
                .version("1.0")
                .name("alert")
                .title("Alert Action")
                .status(ActionDefinitionStatus.ACTIVE)
                .actionType(ActionType.CommunicationRequest)
                .definition(objectMapper.createObjectNode())
                .build();
    }

    private PlanDefinitionParser.IntelligenceRuleInfo buildRule(String ruleId, String language,
                                                                 String expression, String canonical) {
        return new PlanDefinitionParser.IntelligenceRuleInfo(
                ruleId, language, expression, canonical, null, null);
    }

    private void mockParserReturnsRules(StepInstance step,
                                         List<PlanDefinitionParser.IntelligenceRuleInfo> rules) {
        var mockPlanDef = mock(PlanDefinition.class);
        when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);
        when(planDefinitionParser.extractActions(mockPlanDef)).thenReturn(List.of(
                new PlanDefinitionParser.ActionMetadata(step.getActionId(), "Test Action",
                        List.of(), List.of(), null, null, null, rules)));
    }
}
