package org.openphc.cce.compliance.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.ResourceType;
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
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        assertEquals(6, actions.size());

        // Verify first action (initial-enrollment)
        PlanDefinitionParser.StepMetadata enrollment = actions.get(0);
        assertEquals("initial-enrollment", enrollment.id());
        assertEquals("Initial Enrollment on ANC Encounter", enrollment.title());
        assertEquals(1, enrollment.triggers().size());
        // Chain head — it depends on nothing, so it declares no relatedAction
        assertTrue(enrollment.relatedSteps().isEmpty());
    }

    @Test
    void extractActions_extractsConditionCorrectly() {
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // initial-enrollment has a JSONLogic condition
        PlanDefinitionParser.StepMetadata enrollment = actions.get(0);
        PlanDefinitionParser.ConditionInfo condition = enrollment.triggers().get(0).condition();
        assertNotNull(condition);
        assertEquals("text/jsonlogic", condition.language());
        assertEquals("{\"==\": [{\"var\": \"status\"}, \"finished\"]}", condition.expression());
    }

    @Test
    void extractActions_extractsRelatedActionsCorrectly() {
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // relatedAction points at the prerequisite: blood-pressure-check comes 7 days
        // after initial-enrollment ends
        PlanDefinitionParser.StepMetadata bp = actions.get(1);
        assertEquals("blood-pressure-check", bp.id());
        assertEquals(1, bp.relatedSteps().size());

        PlanDefinitionParser.RelatedStepInfo bpRa = bp.relatedSteps().get(0);
        assertEquals("initial-enrollment", bpRa.actionId());
        assertEquals("after-end", bpRa.relationship());
        assertEquals(0, new BigDecimal("7").compareTo(bpRa.offsetValue()));
        assertEquals("d", bpRa.offsetUnit());

        // lab-work comes 14 days after blood-pressure-check ends
        PlanDefinitionParser.StepMetadata labWork = actions.get(2);
        assertEquals("lab-work", labWork.id());
        PlanDefinitionParser.RelatedStepInfo labRa = labWork.relatedSteps().get(0);
        assertEquals("blood-pressure-check", labRa.actionId());
        assertEquals(0, new BigDecimal("14").compareTo(labRa.offsetValue()));
    }

    @Test
    void extractActions_extractsTimingCorrectly() {
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

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
        assertEquals(ResourceType.Encounter, typeEntry.getResourceType());
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
        assertEquals(ResourceType.Encounter, anyEncounter.get(0).getResourceType());
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
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // encounter-condition-only has FHIRPath condition
        PlanDefinitionParser.StepMetadata ecAction = actions.get(4);
        assertEquals("encounter-condition-only", ecAction.id());
        PlanDefinitionParser.ConditionInfo cond = ecAction.triggers().get(0).condition();
        assertNotNull(cond);
        assertEquals("text/fhirpath", cond.language());
        assertEquals("Encounter.status = 'finished'", cond.expression());
    }

    @Test
    void extractActions_actionWithNoRelatedActions() {
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // initial-enrollment heads the chain, so it declares no relatedAction
        PlanDefinitionParser.StepMetadata enrollment = actions.get(0);
        assertEquals("initial-enrollment", enrollment.id());
        assertTrue(enrollment.relatedSteps().isEmpty());
    }

    @Test
    void extractActions_actionWithNoTiming() {
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // lab-work has no timing
        PlanDefinitionParser.StepMetadata labWork = actions.get(2);
        assertNull(labWork.timing());
    }

    // ── RMNCH Protocol Tests — same-path codeFilter ──

    @Test
    void buildTriggerIndex_rmnch_ancVisitHasTwoIdentifierCodeFilters() throws IOException {
        String rmnchJson = loadFixture("/fhir/plan-definition-rmnch-protocol.json");
        PlanDefinition pd = parser.parse(rmnchJson);
        UUID protocolDefId = UUID.randomUUID();
        List<TriggerIndex> entries = parser.buildTriggerIndexEntries(pd, protocolDefId);

        // anc-visit-1 should have 2 trigger index rows, both on path "identifier"
        // with different systems (encounter-type and visit-count)
        List<TriggerIndex> ancVisit1 = entries.stream()
                .filter(e -> "anc-visit-1".equals(e.getActionId()))
                .toList();
        assertEquals(2, ancVisit1.size());
        assertTrue(ancVisit1.stream().allMatch(e -> "identifier".equals(e.getPath())),
                "Both codeFilters should have path 'identifier'");
        assertTrue(ancVisit1.stream().anyMatch(e ->
                "http://mdtlabs.com/encounter-type".equals(e.getCodeSystem())
                        && "ANC".equals(e.getCodeValue())));
        assertTrue(ancVisit1.stream().anyMatch(e ->
                "http://mdtlabs.com/visit-count".equals(e.getCodeSystem())
                        && "1".equals(e.getCodeValue())));
    }

    @Test
    void buildTriggerIndex_rmnch_ancVisitsDistinguishedByVisitCount() throws IOException {
        String rmnchJson = loadFixture("/fhir/plan-definition-rmnch-protocol.json");
        PlanDefinition pd = parser.parse(rmnchJson);
        UUID protocolDefId = UUID.randomUUID();
        List<TriggerIndex> entries = parser.buildTriggerIndexEntries(pd, protocolDefId);

        // Each ANC visit should have a unique visit-count code
        for (int visit = 1; visit <= 3; visit++) {
            String actionId = "anc-visit-" + visit;
            String expectedCount = String.valueOf(visit);

            List<TriggerIndex> visitEntries = entries.stream()
                    .filter(e -> actionId.equals(e.getActionId()))
                    .filter(e -> "http://mdtlabs.com/visit-count".equals(e.getCodeSystem()))
                    .toList();
            assertEquals(1, visitEntries.size(), "Should have exactly one visit-count entry for " + actionId);
            assertEquals(expectedCount, visitEntries.get(0).getCodeValue());
        }
    }

    @Test
    void buildTriggerIndex_rmnch_totalEntryCount() throws IOException {
        String rmnchJson = loadFixture("/fhir/plan-definition-rmnch-protocol.json");
        PlanDefinition pd = parser.parse(rmnchJson);
        UUID protocolDefId = UUID.randomUUID();
        List<TriggerIndex> entries = parser.buildTriggerIndexEntries(pd, protocolDefId);

        // Expected:
        // registration: 1 (RelatedPerson, F1 only — empty path)
        // family-planning: 1 (encounter-type=FAMILY_PLANNING)
        // pregnancy-profile: 1 (encounter-type=PWPROFILE)
        // anc-visit-1: 2 (encounter-type=ANC, visit-count=1)
        // anc-visit-2: 2 (encounter-type=ANC, visit-count=2)
        // anc-visit-3: 2 (encounter-type=ANC, visit-count=3)
        // anc-referral: 2 (category=RMNCH, encounter-type=ANC)
        // pregnancy-outcome: 1 (encounter-type=PREGNANCYOUTCOME)
        // pnc: 1 (encounter-type=PNC_MOTHER)
        assertEquals(19, entries.size());
    }

    @Test
    void extractActions_rmnch_ancVisitsPresent() throws IOException {
        String rmnchJson = loadFixture("/fhir/plan-definition-rmnch-protocol.json");
        PlanDefinition pd = parser.parse(rmnchJson);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        for (String ancId : List.of("anc-visit-1", "anc-visit-2", "anc-visit-3")) {
            assertTrue(
                    actions.stream().anyMatch(a -> ancId.equals(a.id())),
                    ancId + " should be present in the protocol");
        }
    }

    @Test
    void extractActions_rmnch_pregnancyProfileTriggersAncChain() throws IOException {
        String rmnchJson = loadFixture("/fhir/plan-definition-rmnch-protocol.json");
        PlanDefinition pd = parser.parse(rmnchJson);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // anc-visit-1 declares pregnancy-profile as its prerequisite
        PlanDefinitionParser.StepMetadata ancVisit1 = actions.stream()
                .filter(a -> "anc-visit-1".equals(a.id()))
                .findFirst().orElseThrow();
        assertEquals(1, ancVisit1.relatedSteps().size());
        assertEquals("pregnancy-profile", ancVisit1.relatedSteps().get(0).actionId());
        assertEquals("after-end", ancVisit1.relatedSteps().get(0).relationship());
    }

    // ── Intelligence Actions Tests ──

    @Test
    void extractActions_extractsIntelligenceActions() throws IOException {
        String actionsJson = loadFixture("/fhir/plan-definition-with-intelligence-actions.json");
        PlanDefinition pd = parser.parse(actionsJson);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // blood-pressure-check has 2 intelligence actions
        PlanDefinitionParser.StepMetadata bpAction = actions.get(0);
        assertEquals("blood-pressure-check", bpAction.id());
        assertEquals(2, bpAction.intelligenceActions().size());

        PlanDefinitionParser.IntelligenceActionInfo action1 = bpAction.intelligenceActions().get(0);
        assertEquals("bp-high-alert", action1.actionId());
        assertEquals("text/jsonlogic", action1.conditionLanguage());
        assertEquals("{\">\": [{\"var\": \"systolic\"}, 140]}", action1.conditionExpression());
        assertEquals("http://openphc.org/ActivityDefinition/high-bp-alert|1.0.0", action1.definitionCanonical());
        assertEquals("HIGH", action1.severity());
        assertEquals("ASSIGNED_WORKER", action1.intelligenceDestination());

        PlanDefinitionParser.IntelligenceActionInfo action2 = bpAction.intelligenceActions().get(1);
        assertEquals("bp-critical-escalation", action2.actionId());
        assertEquals("text/fhirpath", action2.conditionLanguage());
        assertEquals("http://openphc.org/ActivityDefinition/bp-critical-escalation|1.0.0", action2.definitionCanonical());
        assertEquals("CRITICAL", action2.severity());
        assertEquals("SUPERVISOR", action2.intelligenceDestination());
    }

    @Test
    void extractActions_actionWithNoIntelligenceActions_emptyList() throws IOException {
        String actionsJson = loadFixture("/fhir/plan-definition-with-intelligence-actions.json");
        PlanDefinition pd = parser.parse(actionsJson);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // no-sub-actions has no intelligence actions → empty list
        PlanDefinitionParser.StepMetadata noSubs = actions.get(1);
        assertEquals("no-sub-actions", noSubs.id());
        assertTrue(noSubs.intelligenceActions().isEmpty());
    }

    @Test
    void extractActions_intelligenceActionMissingCondition_skipped() throws IOException {
        String actionsJson = loadFixture("/fhir/plan-definition-with-intelligence-actions.json");
        PlanDefinition pd = parser.parse(actionsJson);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // partial-actions has 3 intelligence actions: missing-condition, missing-definition, no-extensions-action
        // Only no-extensions-action passes both condition + definitionCanonical checks
        PlanDefinitionParser.StepMetadata partial = actions.get(2);
        assertEquals("partial-actions", partial.id());
        assertEquals(1, partial.intelligenceActions().size());
        assertEquals("no-extensions-action", partial.intelligenceActions().get(0).actionId());
    }

    @Test
    void extractActions_intelligenceActionMissingDefinitionCanonical_skipped() throws IOException {
        String actionsJson = loadFixture("/fhir/plan-definition-with-intelligence-actions.json");
        PlanDefinition pd = parser.parse(actionsJson);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // Verify missing-definition intelligence action was skipped
        PlanDefinitionParser.StepMetadata partial = actions.get(2);
        boolean hasMissingDef = partial.intelligenceActions().stream()
                .anyMatch(a -> "missing-definition".equals(a.actionId()));
        assertFalse(hasMissingDef);
    }

    @Test
    void extractActions_intelligenceActionWithExtensions_extractsSeverityAndDestination() throws IOException {
        String actionsJson = loadFixture("/fhir/plan-definition-with-intelligence-actions.json");
        PlanDefinition pd = parser.parse(actionsJson);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        PlanDefinitionParser.StepMetadata partial = actions.get(2);
        PlanDefinitionParser.IntelligenceActionInfo action = partial.intelligenceActions().get(0);
        assertEquals("no-extensions-action", action.actionId());
        assertEquals("http://openphc.org/ActivityDefinition/no-ext-action|1.0.0", action.definitionCanonical());
        assertEquals("LOW", action.severity());
        assertEquals("SPICE", action.intelligenceDestination());
    }

    @Test
    void extractActions_existingFixture_hasEmptyIntelligenceActions() {
        // Existing fixture has no intelligence actions → all actions should have empty intelligence actions
        PlanDefinition pd = parser.parse(fixtureJson);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        for (PlanDefinitionParser.StepMetadata action : actions) {
            assertTrue(action.intelligenceActions().isEmpty(),
                    "Action " + action.id() + " should have empty intelligence actions");
        }
    }

    @Test
    void extractActions_intelligenceActionMissingSeverity_throwsIllegalArgument() {
        // Build minimal PlanDefinition with intelligence action missing severity extension
        String json = """
                {
                  "resourceType": "PlanDefinition",
                  "id": "test-missing-severity",
                  "url": "http://test.org/PlanDefinition/missing-severity",
                  "version": "1.0",
                  "status": "active",
                  "action": [{
                    "id": "step-1",
                    "type": {"coding": [{"system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step"}]},
                    "trigger": [{"type": "named-event", "name": "test"}],
                    "action": [{
                      "id": "intel-action-1",
                      "type": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event"}]},
                      "condition": [{"kind": "applicability", "expression": {"language": "text/jsonlogic", "expression": "{\\"==\\": [1, 1]}"}}],
                      "definitionCanonical": "ActivityDefinition/test|1.0",
                      "extension": [
                        {"url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination", "valueCode": "openMRS"}
                      ]
                    }]
                  }]
                }
                """;
        PlanDefinition pd = parser.parse(json);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.extractSteps(pd));
        assertTrue(ex.getMessage().contains("intelligence-severity"));
    }

    @Test
    void extractActions_intelligenceActionMissingDestination_throwsIllegalArgument() {
        String json = """
                {
                  "resourceType": "PlanDefinition",
                  "id": "test-missing-dest",
                  "url": "http://test.org/PlanDefinition/missing-dest",
                  "version": "1.0",
                  "status": "active",
                  "action": [{
                    "id": "step-1",
                    "type": {"coding": [{"system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step"}]},
                    "trigger": [{"type": "named-event", "name": "test"}],
                    "action": [{
                      "id": "intel-action-1",
                      "type": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event"}]},
                      "condition": [{"kind": "applicability", "expression": {"language": "text/jsonlogic", "expression": "{\\"==\\": [1, 1]}"}}],
                      "definitionCanonical": "ActivityDefinition/test|1.0",
                      "extension": [
                        {"url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity", "valueCode": "HIGH"}
                      ]
                    }]
                  }]
                }
                """;
        PlanDefinition pd = parser.parse(json);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.extractSteps(pd));
        assertTrue(ex.getMessage().contains("intelligence-destination"));
    }

    @Test
    void extractActions_nestedActionMissingType_throwsIllegalArgument() {
        String json = """
                {
                  "resourceType": "PlanDefinition",
                  "id": "test-missing-type",
                  "url": "http://test.org/PlanDefinition/missing-type",
                  "version": "1.0",
                  "status": "active",
                  "action": [{
                    "id": "step-1",
                    "type": {"coding": [{"system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step"}]},
                    "trigger": [{"type": "named-event", "name": "test"}],
                    "action": [{
                      "id": "untyped-action",
                      "condition": [{"kind": "applicability", "expression": {"language": "text/jsonlogic", "expression": "{\\"==\\": [1, 1]}"}}],
                      "definitionCanonical": "ActivityDefinition/test|1.0"
                    }]
                  }]
                }
                """;
        PlanDefinition pd = parser.parse(json);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.extractSteps(pd));
        assertTrue(ex.getMessage().contains("must have explicit type coding"));
        assertTrue(ex.getMessage().contains("untyped-action"));
    }

    // ── ActionType Validation Tests ──

    @Test
    void validateActionTypes_topLevelActionMissingType_throwsException() {
        String json = """
                {
                  "resourceType": "PlanDefinition",
                  "id": "test-missing-top-type",
                  "url": "http://test.org/PlanDefinition/missing-top-type",
                  "version": "1.0",
                  "status": "active",
                  "action": [{
                    "id": "visit-encounter",
                    "title": "Patient Visit Encounter",
                    "trigger": [{"type": "named-event", "name": "test"}]
                  }]
                }
                """;
        PlanDefinition pd = parser.parse(json);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.validateActionTypes(pd));
        assertTrue(ex.getMessage().contains("must have explicit type coding"));
        assertTrue(ex.getMessage().contains("visit-encounter"));
    }

    @Test
    void validateActionTypes_nestedActionMissingType_throwsException() {
        String json = """
                {
                  "resourceType": "PlanDefinition",
                  "id": "test-missing-nested-type",
                  "url": "http://test.org/PlanDefinition/missing-nested-type",
                  "version": "1.0",
                  "status": "active",
                  "action": [{
                    "id": "step-1",
                    "type": {"coding": [{"system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step"}]},
                    "trigger": [{"type": "named-event", "name": "test"}],
                    "action": [{
                      "id": "untyped-nested",
                      "trigger": [{"type": "named-event", "name": "test2"}]
                    }]
                  }]
                }
                """;
        PlanDefinition pd = parser.parse(json);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.validateActionTypes(pd));
        assertTrue(ex.getMessage().contains("must have explicit type coding"));
        assertTrue(ex.getMessage().contains("untyped-nested"));
    }

    @Test
    void validateActionTypes_unsupportedTypeCoding_throwsException() {
        String json = """
                {
                  "resourceType": "PlanDefinition",
                  "id": "test-bad-type",
                  "url": "http://test.org/PlanDefinition/bad-type",
                  "version": "1.0",
                  "status": "active",
                  "action": [{
                    "id": "step-1",
                    "type": {"coding": [{"system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "bogus"}]},
                    "trigger": [{"type": "named-event", "name": "test"}]
                  }]
                }
                """;
        PlanDefinition pd = parser.parse(json);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.validateActionTypes(pd));
        assertTrue(ex.getMessage().contains("unsupported type coding"));
        assertTrue(ex.getMessage().contains("step-1"));
    }

    @Test
    void validateActionTypes_validTypeCodings_noException() {
        String json = """
                {
                  "resourceType": "PlanDefinition",
                  "id": "test-valid-types",
                  "url": "http://test.org/PlanDefinition/valid-types",
                  "version": "1.0",
                  "status": "active",
                  "action": [{
                    "id": "step-1",
                    "type": {"coding": [{"system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step"}]},
                    "trigger": [{"type": "named-event", "name": "test"}]
                  }]
                }
                """;
        PlanDefinition pd = parser.parse(json);
        assertDoesNotThrow(() -> parser.validateActionTypes(pd));
    }

    // ── ActionId Validation Tests ──

    @Test
    void validateActionIds_duplicateActionId_throwsException() {
        String json = """
                {
                  "resourceType": "PlanDefinition",
                  "id": "test",
                  "status": "active",
                  "url": "http://test.org/pd/dup",
                  "version": "1.0",
                  "action": [
                    {
                      "id": "step-1",
                      "title": "Step 1",
                      "type": {"coding": [{"system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step"}]},
                      "trigger": [{"type": "named-event", "name": "test"}]
                    },
                    {
                      "id": "step-1",
                      "title": "Duplicate Step",
                      "type": {"coding": [{"system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step"}]},
                      "trigger": [{"type": "named-event", "name": "test2"}]
                    }
                  ]
                }
                """;
        PlanDefinition pd = parser.parse(json);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.validateActionIds(pd));
        assertTrue(ex.getMessage().contains("Duplicate actionId"));
        assertTrue(ex.getMessage().contains("step-1"));
    }

    @Test
    void validateActionIds_missingActionId_throwsException() {
        String json = """
                {
                  "resourceType": "PlanDefinition",
                  "id": "test",
                  "status": "active",
                  "url": "http://test.org/pd/missing",
                  "version": "1.0",
                  "action": [
                    {
                      "title": "No ID Step",
                      "type": {"coding": [{"system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step"}]},
                      "trigger": [{"type": "named-event", "name": "test"}]
                    }
                  ]
                }
                """;
        PlanDefinition pd = parser.parse(json);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.validateActionIds(pd));
        assertTrue(ex.getMessage().contains("missing a required actionId"));
    }

    @Test
    void validateActionIds_duplicateNestedActionId_throwsException() {
        String json = """
                {
                  "resourceType": "PlanDefinition",
                  "id": "test",
                  "status": "active",
                  "url": "http://test.org/pd/nested-dup",
                  "version": "1.0",
                  "action": [
                    {
                      "id": "parent-step",
                      "title": "Parent",
                      "type": {"coding": [{"system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step"}]},
                      "trigger": [{"type": "named-event", "name": "test"}],
                      "action": [
                        {
                          "id": "parent-step",
                          "title": "Nested with same ID as parent",
                          "type": {"coding": [{"system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step"}]},
                          "trigger": [{"type": "named-event", "name": "test2"}]
                        }
                      ]
                    }
                  ]
                }
                """;
        PlanDefinition pd = parser.parse(json);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.validateActionIds(pd));
        assertTrue(ex.getMessage().contains("Duplicate actionId"));
        assertTrue(ex.getMessage().contains("parent-step"));
    }

    @Test
    void validateActionIds_validUniqueIds_noException() throws IOException {
        String json = loadFixture("/fhir/plan-definition-with-sub-steps.json");
        PlanDefinition pd = parser.parse(json);
        assertDoesNotThrow(() -> parser.validateActionIds(pd));
    }

    private String loadFixture(String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Test fixture not found: " + resourcePath);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ── Sub-Step (Flat Model) Plan Definition Tests ──

    @Test
    void extractActions_subStepsPlanDefinition_flattensNestedSteps() throws IOException {
        String json = loadFixture("/fhir/plan-definition-with-sub-steps.json");
        PlanDefinition pd = parser.parse(json);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // Flat model: all steps including former sub-steps appear as peer entries
        // Should have 8 top-level + 2 sub-steps = 10 total
        assertTrue(actions.size() >= 10, "Expected at least 10 flat steps, got " + actions.size());

        // anc-visit-1 parent step exists
        PlanDefinitionParser.StepMetadata visit1 = actions.stream()
                .filter(a -> "anc-visit-1".equals(a.id()))
                .findFirst().orElseThrow();
        assertFalse(visit1.triggers().isEmpty());

        // Former sub-steps are now peer entries in the flat list
        PlanDefinitionParser.StepMetadata referral = actions.stream()
                .filter(a -> "anc-visit-1-referral".equals(a.id()))
                .findFirst().orElseThrow();
        PlanDefinitionParser.StepMetadata ack = actions.stream()
                .filter(a -> "anc-visit-1-referral-ack".equals(a.id()))
                .findFirst().orElseThrow();
        assertNotNull(referral);
        assertNotNull(ack);
    }

    @Test
    void extractActions_subStepsPlanDefinition_subStepHasIntelligenceActions() throws IOException {
        String json = loadFixture("/fhir/plan-definition-with-sub-steps.json");
        PlanDefinition pd = parser.parse(json);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // anc-visit-1-referral has 1 intelligence action (escalation)
        PlanDefinitionParser.StepMetadata referralStep = actions.stream()
                .filter(a -> "anc-visit-1-referral".equals(a.id()))
                .findFirst().orElseThrow();
        assertEquals(1, referralStep.intelligenceActions().size());
        assertEquals("anc-visit-1-referral-escalation", referralStep.intelligenceActions().get(0).actionId());
        assertEquals("CRITICAL", referralStep.intelligenceActions().get(0).severity());

        // anc-visit-1-referral-ack has 1 intelligence action (overdue notification)
        PlanDefinitionParser.StepMetadata ackStep = actions.stream()
                .filter(a -> "anc-visit-1-referral-ack".equals(a.id()))
                .findFirst().orElseThrow();
        assertEquals(1, ackStep.intelligenceActions().size());
        assertEquals("anc-visit-1-overdue-notification", ackStep.intelligenceActions().get(0).actionId());
        assertEquals("HIGH", ackStep.intelligenceActions().get(0).severity());
    }

    @Test
    void extractActions_subStepsPlanDefinition_subStepRelatedActions() throws IOException {
        String json = loadFixture("/fhir/plan-definition-with-sub-steps.json");
        PlanDefinition pd = parser.parse(json);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // anc-visit-1-referral-ack declares anc-visit-1-referral as its prerequisite, so
        // completing the referral progressively instantiates the ack
        PlanDefinitionParser.StepMetadata ack = actions.stream()
                .filter(a -> "anc-visit-1-referral-ack".equals(a.id()))
                .findFirst().orElseThrow();
        assertTrue(ack.relatedSteps().stream()
                .anyMatch(r -> "anc-visit-1-referral".equals(r.actionId()) && "after-end".equals(r.relationship())));
    }

    @Test
    void buildTriggerIndexEntries_subStepsPlanDefinition_indexesSubStepTriggers() throws IOException {
        String json = loadFixture("/fhir/plan-definition-with-sub-steps.json");
        PlanDefinition pd = parser.parse(json);
        UUID protocolDefId = UUID.randomUUID();
        List<TriggerIndex> entries = parser.buildTriggerIndexEntries(pd, protocolDefId);

        // Sub-step triggers are indexed with their own actionId
        boolean hasReferral = entries.stream()
                .anyMatch(e -> "anc-visit-1-referral".equals(e.getId().getActionId()));
        assertTrue(hasReferral, "Should have trigger index for anc-visit-1-referral");

        boolean hasAck = entries.stream()
                .anyMatch(e -> "anc-visit-1-referral-ack".equals(e.getId().getActionId()));
        assertTrue(hasAck, "Should have trigger index for anc-visit-1-referral-ack");

        // Enclosing step itself should also have a trigger index
        boolean hasVisit1Direct = entries.stream()
                .anyMatch(e -> "anc-visit-1".equals(e.getId().getActionId()));
        assertTrue(hasVisit1Direct, "Should have direct trigger index for anc-visit-1");

        // Should NOT have composite actionIds (no '/' in any actionId)
        boolean hasComposite = entries.stream()
                .anyMatch(e -> e.getId().getActionId().contains("/"));
        assertFalse(hasComposite, "Should not have any composite actionIds with '/'");
    }

    @Test
    void validateTriggers_subStepsPlanDefinition_stepsWithTriggersAndSubStepsAreValid() throws IOException {
        String json = loadFixture("/fhir/plan-definition-with-sub-steps.json");
        PlanDefinition pd = parser.parse(json);
        // Should not throw — steps with both triggers and sub-steps are valid
        assertDoesNotThrow(() -> parser.validateTriggers(pd));
    }

    @Test
    void extractActions_subStepsPlanDefinition_subStepsHaveNoImplicitDependencyOnParent() throws IOException {
        String json = loadFixture("/fhir/plan-definition-with-sub-steps.json");
        PlanDefinition pd = parser.parse(json);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // Nesting is organizational only — a sub-step gets no implicit prerequisite on its parent,
        // so it is created by its own trigger rather than waiting for the parent to complete.
        PlanDefinitionParser.StepMetadata referral = actions.stream()
                .filter(a -> "anc-visit-1-referral".equals(a.id()))
                .findFirst().orElseThrow();
        assertTrue(referral.relatedSteps().isEmpty(),
                "Entry-point sub-step must not have an implicit prerequisite on its parent");

        // Its sibling declares only the explicit prerequisite the protocol author wrote
        PlanDefinitionParser.StepMetadata ack = actions.stream()
                .filter(a -> "anc-visit-1-referral-ack".equals(a.id()))
                .findFirst().orElseThrow();
        assertEquals(List.of("anc-visit-1-referral"),
                ack.relatedSteps().stream()
                        .map(PlanDefinitionParser.RelatedStepInfo::actionId).toList());
    }

    @Test
    void extractActions_subStepsPlanDefinition_progressiveChainBetweenSteps() throws IOException {
        String json = loadFixture("/fhir/plan-definition-with-sub-steps.json");
        PlanDefinition pd = parser.parse(json);
        List<PlanDefinitionParser.StepMetadata> actions = parser.extractSteps(pd);

        // anc-visit-2 comes 30 days after anc-visit-1
        PlanDefinitionParser.StepMetadata visit2 = actions.stream()
                .filter(a -> "anc-visit-2".equals(a.id()))
                .findFirst().orElseThrow();
        assertTrue(visit2.relatedSteps().stream()
                .anyMatch(r -> "anc-visit-1".equals(r.actionId()) && BigDecimal.valueOf(30).equals(r.offsetValue())));

        // anc-visit-3 comes 30 days after anc-visit-2
        PlanDefinitionParser.StepMetadata visit3 = actions.stream()
                .filter(a -> "anc-visit-3".equals(a.id()))
                .findFirst().orElseThrow();
        assertTrue(visit3.relatedSteps().stream()
                .anyMatch(r -> "anc-visit-2".equals(r.actionId())));
    }

    // ── EMR Service (nested consultation) protocol ──

    @Test
    void emrNestedProtocol_validatesAndFlattensToTenSteps() throws IOException {
        String json = loadFixture("/fhir/emr-service-protocol-nested.json");
        PlanDefinition pd = parser.parse(json);

        // Every action (incl. nested) carries a valid type coding
        assertDoesNotThrow(() -> parser.validateActionTypes(pd));

        List<PlanDefinitionParser.StepMetadata> steps = parser.extractSteps(pd);
        List<String> ids = steps.stream().map(PlanDefinitionParser.StepMetadata::id).toList();
        assertEquals(10, steps.size());
        assertTrue(ids.containsAll(List.of(
                "visit-encounter", "vitals-recording", "consultation", "chief-complaints",
                "history-assessment", "lab-order", "lab-results", "diagnosis", "treatment", "referral")));
    }

    @Test
    void emrNestedProtocol_declaresPrerequisiteChainAndNoImplicitParentLinks() throws IOException {
        String json = loadFixture("/fhir/emr-service-protocol-nested.json");
        PlanDefinition pd = parser.parse(json);
        List<PlanDefinitionParser.StepMetadata> steps = parser.extractSteps(pd);

        java.util.Map<String, List<String>> prerequisites = new java.util.HashMap<>();
        for (PlanDefinitionParser.StepMetadata s : steps) {
            prerequisites.put(s.id(), s.relatedSteps().stream()
                    .map(PlanDefinitionParser.RelatedStepInfo::actionId).toList());
        }

        // Each step names the step it comes after — relatedAction points at the prerequisite
        assertEquals(List.of(), prerequisites.get("visit-encounter"));
        assertEquals(List.of("visit-encounter"), prerequisites.get("vitals-recording"));
        assertEquals(List.of("vitals-recording"), prerequisites.get("consultation"));
        assertEquals(List.of("consultation"), prerequisites.get("chief-complaints"));
        assertEquals(List.of("chief-complaints"), prerequisites.get("history-assessment"));
        assertEquals(List.of("history-assessment"), prerequisites.get("lab-order"));
        assertEquals(List.of("lab-order"), prerequisites.get("lab-results"));
        assertEquals(List.of("lab-results"), prerequisites.get("diagnosis"));
        assertEquals(List.of("diagnosis"), prerequisites.get("treatment"));
        assertEquals(List.of("treatment"), prerequisites.get("referral"));

        // Nesting is organizational only — no nested sub-step gains an implicit prerequisite on
        // its parent. The only step depending on a parent step is the legitimate next sibling.
        PlanDefinitionParser.DependencyGraph graph = PlanDefinitionParser.buildDependencyGraph(steps);
        assertEquals(List.of("chief-complaints"),
                graph.successorsOf("consultation").stream().map(d -> d.step().id()).toList(),
                "only chief-complaints should depend on consultation");
        assertEquals(List.of("lab-results"),
                graph.successorsOf("lab-order").stream().map(d -> d.step().id()).toList(),
                "only lab-results should depend on lab-order");
        assertTrue(graph.successorsOf("referral").isEmpty(), "referral ends the chain");

        // Every step is reachable from the enrollment step by walking the derived forward chain
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>(List.of("visit-encounter"));
        while (!queue.isEmpty()) {
            String n = queue.poll();
            if (!seen.add(n)) continue;
            graph.successorsOf(n).forEach(d -> queue.add(d.step().id()));
        }
        assertEquals(10, seen.size(), "all 10 steps reachable from visit-encounter");
    }

    @Test
    void buildDependencyGraph_carriesTheDependentStepsOwnOffset() throws IOException {
        String json = loadFixture("/fhir/emr-service-protocol-nested.json");
        List<PlanDefinitionParser.StepMetadata> steps = parser.extractSteps(parser.parse(json));

        // lab-results declares "+3d after lab-order", so the edge indexed under lab-order must
        // carry lab-results' own offset — not anything lab-order declares.
        PlanDefinitionParser.StepDependency dependency =
                PlanDefinitionParser.buildDependencyGraph(steps).successorsOf("lab-order").get(0);

        assertEquals("lab-results", dependency.step().id());
        assertEquals("after-end", dependency.edge().relationship());
        assertEquals(0, new BigDecimal("3").compareTo(dependency.edge().offsetValue()));
        assertEquals("d", dependency.edge().offsetUnit());

        // Predecessor direction agrees
        assertEquals(List.of("lab-order"),
                PlanDefinitionParser.buildDependencyGraph(steps).predecessorsOf("lab-results"));
    }

    @Test
    void classifyRelationship_distinguishesTheThreeFamilies() {
        // relatedAction is relative: which end comes first depends on the family.
        assertEquals(PlanDefinitionParser.RelationshipDirection.AFTER,
                PlanDefinitionParser.classifyRelationship("after"));
        assertEquals(PlanDefinitionParser.RelationshipDirection.AFTER,
                PlanDefinitionParser.classifyRelationship("after-start"));
        assertEquals(PlanDefinitionParser.RelationshipDirection.AFTER,
                PlanDefinitionParser.classifyRelationship("after-end"));
        assertEquals(PlanDefinitionParser.RelationshipDirection.AFTER,
                PlanDefinitionParser.classifyRelationship(null), "absent defaults to after-end");

        assertEquals(PlanDefinitionParser.RelationshipDirection.BEFORE,
                PlanDefinitionParser.classifyRelationship("before"));
        assertEquals(PlanDefinitionParser.RelationshipDirection.BEFORE,
                PlanDefinitionParser.classifyRelationship("before-start"));
        assertEquals(PlanDefinitionParser.RelationshipDirection.BEFORE,
                PlanDefinitionParser.classifyRelationship("before-end"));

        assertEquals(PlanDefinitionParser.RelationshipDirection.UNORDERED,
                PlanDefinitionParser.classifyRelationship("concurrent"));
        assertEquals(PlanDefinitionParser.RelationshipDirection.UNORDERED,
                PlanDefinitionParser.classifyRelationship("concurrent-with-start"));
        assertEquals(PlanDefinitionParser.RelationshipDirection.UNORDERED,
                PlanDefinitionParser.classifyRelationship("concurrent-with-end"));
    }

    // ── Normalizing both ordering families into one graph ──

    private static PlanDefinitionParser.StepMetadata step(
            String id, PlanDefinitionParser.RelatedStepInfo... related) {
        return new PlanDefinitionParser.StepMetadata(id, id, List.of(), List.of(related),
                null, null, "must", List.of(), null);
    }

    private static PlanDefinitionParser.RelatedStepInfo edge(
            String actionId, String relationship, String offsetDays) {
        return new PlanDefinitionParser.RelatedStepInfo(
                actionId, relationship, offsetDays == null ? null : new BigDecimal(offsetDays), "d");
    }

    @Test
    void buildDependencyGraph_beforeRelationshipOrdersTheSameWayRoundAsAfter() {
        // "a before b" and "b after-end a" state the same ordering from opposite ends, so both
        // must yield the same directed edge a -> b.
        PlanDefinitionParser.DependencyGraph viaBefore = PlanDefinitionParser.buildDependencyGraph(
                List.of(step("a", edge("b", "before", "2")), step("b")));
        PlanDefinitionParser.DependencyGraph viaAfter = PlanDefinitionParser.buildDependencyGraph(
                List.of(step("a"), step("b", edge("a", "after-end", "2"))));

        for (PlanDefinitionParser.DependencyGraph graph : List.of(viaBefore, viaAfter)) {
            assertEquals(List.of("b"),
                    graph.successorsOf("a").stream().map(d -> d.step().id()).toList());
            assertEquals(List.of("a"), graph.predecessorsOf("b"));
            assertTrue(graph.successorsOf("b").isEmpty());

            PlanDefinitionParser.RelatedStepInfo normalized = graph.successorsOf("a").get(0).edge();
            assertEquals("a", normalized.actionId(), "normalized edge names the prerequisite");
            assertEquals("after-end", normalized.relationship());
            assertEquals(0, new BigDecimal("2").compareTo(normalized.offsetValue()));
        }
    }

    @Test
    void buildDependencyGraph_beforeStartAndBeforeEndBothOrderForward() {
        for (String relationship : List.of("before", "before-start", "before-end")) {
            PlanDefinitionParser.DependencyGraph graph = PlanDefinitionParser.buildDependencyGraph(
                    List.of(step("a", edge("b", relationship, "0")), step("b")));
            assertEquals(List.of("a"), graph.predecessorsOf("b"),
                    relationship + " must order a before b");
        }
    }

    @Test
    void buildDependencyGraph_concurrentAndDanglingEdgesEstablishNoOrdering() {
        PlanDefinitionParser.DependencyGraph graph = PlanDefinitionParser.buildDependencyGraph(
                List.of(step("a", edge("b", "concurrent-with-start", "0"),
                                  edge("ghost", "after-end", "0")),
                        step("b")));

        assertTrue(graph.successors().isEmpty(), "neither edge orders anything");
        assertTrue(graph.predecessors().isEmpty());
        assertTrue(PlanDefinitionParser.computeAncestors("a", graph).isEmpty(),
                "a dangling prerequisite is not an ancestor — there is no such step");
    }

    @Test
    void findUnorderedRelationships_andFindDanglingRelatedActions_reportInertEdges() {
        List<PlanDefinitionParser.StepMetadata> steps =
                List.of(step("a", edge("b", "concurrent", "0"), edge("ghost", "after-end", "0")),
                        step("b"));

        List<String> unordered = PlanDefinitionParser.findUnorderedRelationships(steps);
        assertEquals(1, unordered.size());
        assertTrue(unordered.get(0).contains("concurrent"), unordered.get(0));

        List<String> dangling = PlanDefinitionParser.findDanglingRelatedActions(steps);
        assertEquals(1, dangling.size());
        assertTrue(dangling.get(0).contains("ghost"), dangling.get(0));
    }

    // ── Expected mandatory actions ──

    @Test
    void computeMustPredecessorSteps_loneLateTreatment_returnsEveryMandatoryPredecessor() throws IOException {
        String json = loadFixture("/fhir/emr-service-protocol-nested.json");
        List<PlanDefinitionParser.StepMetadata> steps = parser.extractSteps(parser.parse(json));

        // Only `treatment` was recorded: every mandatory action on the path to it is already late,
        // which is what the backfill materializes as PENDING.
        java.util.Set<String> predecessors =
                PlanDefinitionParser.computeMustPredecessorSteps(List.of("treatment"), steps);

        assertEquals(java.util.Set.of("visit-encounter", "vitals-recording", "consultation",
                        "chief-complaints", "lab-order", "lab-results", "diagnosis"),
                predecessors);
        // `history-assessment` is optional (could); `referral` only follows the completed step.
        assertFalse(predecessors.contains("history-assessment"));
        assertFalse(predecessors.contains("referral"));
    }

    @Test
    void computeMustPredecessorSteps_excludesMandatoryStepsStillAheadInTheChain() throws IOException {
        String json = loadFixture("/fhir/emr-service-protocol-nested.json");
        List<PlanDefinitionParser.StepMetadata> steps = parser.extractSteps(parser.parse(json));

        // Progress has reached `chief-complaints`. `lab-order`, `lab-results` and `diagnosis` are
        // mandatory sub-steps of the same nesting group but still ahead in the forward chain, so
        // they are left to progressive instantiation and its relatedAction offsets.
        java.util.Set<String> predecessors =
                PlanDefinitionParser.computeMustPredecessorSteps(List.of("chief-complaints"), steps);

        assertEquals(java.util.Set.of("visit-encounter", "vitals-recording", "consultation"),
                predecessors);
    }
}
