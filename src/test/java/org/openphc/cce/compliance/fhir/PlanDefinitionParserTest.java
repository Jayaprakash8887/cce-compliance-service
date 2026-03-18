package org.openphc.cce.compliance.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.TriggerDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.compliance.domain.entity.TriggerIndex;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlanDefinitionParserTest {

    private static FhirContext fhirContext;
    private PlanDefinitionParser parser;
    private String fixtureJson;

    @BeforeAll
    static void initFhirContext() {
        fhirContext = FhirContext.forR4();
    }

    @BeforeEach
    void setUp() throws IOException {
        parser = new PlanDefinitionParser(fhirContext);
        try (InputStream is = getClass().getResourceAsStream("/fhir/plan-definition-anc-high-risk.json")) {
            assertNotNull(is, "Test fixture not found");
            fixtureJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parse_validJson_returnsPlanDefinition() {
        PlanDefinition pd = parser.parse(fixtureJson);

        assertNotNull(pd);
        assertEquals("PlanDefinition/anc-high-risk", pd.getId());
        assertEquals("http://openphc.org/PlanDefinition/anc-high-risk", pd.getUrl());
        assertEquals("1.0.0", pd.getVersion());
        assertEquals("ANCHighRiskProtocol", pd.getName());
    }

    @Test
    void parse_validJson_correctActionCount() {
        PlanDefinition pd = parser.parse(fixtureJson);

        assertEquals(6, pd.getAction().size());
    }

    @Test
    void extractActions_returnsAllActionsWithMetadata() {
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.ActionMetadata> actions = parser.extractActions(pd);

        assertEquals(6, actions.size());

        // Verify first action (initial-enrollment)
        PlanDefinitionParser.ActionMetadata enrollment = actions.get(0);
        assertEquals("initial-enrollment", enrollment.id());
        assertEquals("Initial Enrollment on ANC Encounter", enrollment.title());
        assertEquals(1, enrollment.triggers().size());
        assertEquals(1, enrollment.relatedActions().size());
    }

    @Test
    void extractActions_extractsConditionCorrectly() {
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.ActionMetadata> actions = parser.extractActions(pd);

        // initial-enrollment has a JSONLogic condition
        PlanDefinitionParser.ActionMetadata enrollment = actions.get(0);
        PlanDefinitionParser.ConditionInfo condition = enrollment.triggers().get(0).condition();
        assertNotNull(condition);
        assertEquals("text/jsonlogic", condition.language());
        assertEquals("{\"==\": [{\"var\": \"status\"}, \"finished\"]}", condition.expression());
    }

    @Test
    void extractActions_extractsRelatedActionsCorrectly() {
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.ActionMetadata> actions = parser.extractActions(pd);

        // initial-enrollment → blood-pressure-check after 7 days
        PlanDefinitionParser.ActionMetadata enrollment = actions.get(0);
        assertEquals(1, enrollment.relatedActions().size());

        PlanDefinitionParser.RelatedActionInfo ra = enrollment.relatedActions().get(0);
        assertEquals("blood-pressure-check", ra.actionId());
        assertEquals("after-end", ra.relationship());
        assertEquals(0, new BigDecimal("7").compareTo(ra.offsetValue()));
        assertEquals("d", ra.offsetUnit());

        // blood-pressure-check → lab-work after 14 days
        PlanDefinitionParser.ActionMetadata bp = actions.get(1);
        PlanDefinitionParser.RelatedActionInfo bpRa = bp.relatedActions().get(0);
        assertEquals("lab-work", bpRa.actionId());
        assertEquals(0, new BigDecimal("14").compareTo(bpRa.offsetValue()));
    }

    @Test
    void extractActions_extractsTimingCorrectly() {
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.ActionMetadata> actions = parser.extractActions(pd);

        // initial-enrollment timing
        PlanDefinitionParser.TimingInfo enrollTiming = actions.get(0).timing();
        assertNotNull(enrollTiming);
        assertEquals(1, enrollTiming.count());
        assertEquals(1, enrollTiming.frequency());
        assertEquals(0, BigDecimal.ONE.compareTo(enrollTiming.period()));
        assertEquals("d", enrollTiming.periodUnit());

        // blood-pressure-check timing
        PlanDefinitionParser.TimingInfo bpTiming = actions.get(1).timing();
        assertNotNull(bpTiming);
        assertEquals(4, bpTiming.count());
        assertEquals(1, bpTiming.frequency());
        assertEquals(0, new BigDecimal("7").compareTo(bpTiming.period()));
        assertEquals("d", bpTiming.periodUnit());
    }

    @Test
    void buildTriggerIndexEntries_correctDecomposition() {
        PlanDefinition pd = parser.parse(fixtureJson);
        UUID protocolDefId = UUID.randomUUID();

        List<TriggerIndex> entries = parser.buildTriggerIndexEntries(pd, protocolDefId);

        // Count expected entries:
        // initial-enrollment: 2 codeFilters (type + serviceType) = 2 rows
        // blood-pressure-check: 1 codeFilter (code) = 1 row
        // lab-work: 2 codeFilters (code + category) = 2 rows
        // any-encounter-log: no codeFilter but has data[].type = 1 row (empty path)
        // encounter-condition-only: no codeFilter but has data[].type = 1 row (empty path)
        // global-risk-assessment: no data[] → 0 rows
        assertEquals(7, entries.size());

        // Verify initial-enrollment decomposition
        List<TriggerIndex> enrollmentEntries = entries.stream()
                .filter(e -> "initial-enrollment".equals(e.getActionId()))
                .toList();
        assertEquals(2, enrollmentEntries.size());

        TriggerIndex typeEntry = enrollmentEntries.stream()
                .filter(e -> "type".equals(e.getPath()))
                .findFirst().orElseThrow();
        assertEquals("Encounter", typeEntry.getResourceType());
        assertEquals("http://openphc.org/encounter-types", typeEntry.getCodeSystem());
        assertEquals("anc-visit", typeEntry.getCodeValue());
        assertEquals(protocolDefId, typeEntry.getProtocolDefinitionId());

        TriggerIndex serviceTypeEntry = enrollmentEntries.stream()
                .filter(e -> "serviceType".equals(e.getPath()))
                .findFirst().orElseThrow();
        assertEquals("http://openphc.org/service-types", serviceTypeEntry.getCodeSystem());
        assertEquals("high-risk-anc", serviceTypeEntry.getCodeValue());
    }

    @Test
    void buildTriggerIndexEntries_labWorkHasTwoCodeFilters() {
        PlanDefinition pd = parser.parse(fixtureJson);
        UUID protocolDefId = UUID.randomUUID();
        List<TriggerIndex> entries = parser.buildTriggerIndexEntries(pd, protocolDefId);

        List<TriggerIndex> labEntries = entries.stream()
                .filter(e -> "lab-work".equals(e.getActionId()))
                .toList();
        assertEquals(2, labEntries.size());

        assertTrue(labEntries.stream().anyMatch(e ->
                "code".equals(e.getPath()) && "24323-8".equals(e.getCodeValue())));
        assertTrue(labEntries.stream().anyMatch(e ->
                "category".equals(e.getPath()) && "LAB".equals(e.getCodeValue())));
    }

    @Test
    void buildTriggerIndexEntries_resourceTypeOnlyCreatesEmptyPathEntry() {
        PlanDefinition pd = parser.parse(fixtureJson);
        UUID protocolDefId = UUID.randomUUID();
        List<TriggerIndex> entries = parser.buildTriggerIndexEntries(pd, protocolDefId);

        // any-encounter-log has data[].type=Encounter but no codeFilter
        List<TriggerIndex> anyEncounter = entries.stream()
                .filter(e -> "any-encounter-log".equals(e.getActionId()))
                .toList();
        assertEquals(1, anyEncounter.size());
        assertEquals("Encounter", anyEncounter.get(0).getResourceType());
        assertEquals("", anyEncounter.get(0).getPath());
        assertEquals("", anyEncounter.get(0).getCodeSystem());
        assertEquals("", anyEncounter.get(0).getCodeValue());
    }

    @Test
    void buildTriggerIndexEntries_conditionOnlyTriggerNotIndexed() {
        PlanDefinition pd = parser.parse(fixtureJson);
        UUID protocolDefId = UUID.randomUUID();
        List<TriggerIndex> entries = parser.buildTriggerIndexEntries(pd, protocolDefId);

        // global-risk-assessment has no data[] → should not appear in trigger_index
        boolean hasGlobalRisk = entries.stream()
                .anyMatch(e -> "global-risk-assessment".equals(e.getActionId()));
        assertFalse(hasGlobalRisk);
    }

    @Test
    void extractConditionOnlyTriggers_identifiesCorrectly() {
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.ConditionOnlyTriggerInfo> condOnly = parser.extractConditionOnlyTriggers(pd);

        // Only global-risk-assessment has no data[] with a condition
        assertEquals(1, condOnly.size());
        assertEquals("global-risk-assessment", condOnly.get(0).actionId());
        assertEquals("text/jsonlogic", condOnly.get(0).conditionLanguage());
        assertEquals("{\">\": [{\"var\": \"riskScore\"}, 7]}", condOnly.get(0).conditionExpression());
    }

    @Test
    void validateTriggers_validPlanDefinition_noException() {
        PlanDefinition pd = parser.parse(fixtureJson);
        assertDoesNotThrow(() -> parser.validateTriggers(pd));
    }

    @Test
    void validateTriggers_triggerWithNoDataAndNoCondition_throws() {
        // Build a minimal PlanDefinition with an invalid trigger
        PlanDefinition pd = new PlanDefinition();
        PlanDefinition.PlanDefinitionActionComponent action = pd.addAction();
        action.setId("invalid-action");
        TriggerDefinition trigger = action.addTrigger();
        trigger.setType(TriggerDefinition.TriggerType.NAMEDEVENT);
        // No data[], no condition

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.validateTriggers(pd));
        assertTrue(ex.getMessage().contains("invalid-action"));
        assertTrue(ex.getMessage().contains("no data[] and no condition"));
    }

    @Test
    void parse_malformedJson_throwsDataFormatException() {
        assertThrows(DataFormatException.class, () -> parser.parse("{ invalid json !!!"));
    }

    @Test
    void parse_wrongResourceType_throwsDataFormatException() {
        String patientJson = """
                {
                  "resourceType": "Patient",
                  "id": "test"
                }
                """;
        assertThrows(DataFormatException.class, () -> parser.parse(patientJson));
    }

    @Test
    void extractActions_extractsFhirPathCondition() {
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.ActionMetadata> actions = parser.extractActions(pd);

        // encounter-condition-only has FHIRPath condition
        PlanDefinitionParser.ActionMetadata ecAction = actions.get(4);
        assertEquals("encounter-condition-only", ecAction.id());
        PlanDefinitionParser.ConditionInfo cond = ecAction.triggers().get(0).condition();
        assertNotNull(cond);
        assertEquals("text/fhirpath", cond.language());
        assertEquals("Encounter.status = 'finished'", cond.expression());
    }

    @Test
    void extractActions_actionWithNoRelatedActions() {
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.ActionMetadata> actions = parser.extractActions(pd);

        // lab-work has no relatedAction
        PlanDefinitionParser.ActionMetadata labWork = actions.get(2);
        assertEquals("lab-work", labWork.id());
        assertTrue(labWork.relatedActions().isEmpty());
    }

    @Test
    void extractActions_actionWithNoTiming() {
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.ActionMetadata> actions = parser.extractActions(pd);

        // lab-work has no timing
        PlanDefinitionParser.ActionMetadata labWork = actions.get(2);
        assertNull(labWork.timing());
    }
}
