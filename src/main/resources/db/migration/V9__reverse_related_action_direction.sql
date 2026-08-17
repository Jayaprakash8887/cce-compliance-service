-- =============================================================================
-- V9: Reverse relatedAction direction in stored PlanDefinitions
-- =============================================================================
-- WHY THIS EXISTS
--   FHIR `PlanDefinition.action.relatedAction` is a BACKWARD pointer: `relationship`
--   describes the declaring action's relationship to the referenced one, so
--
--       { "id": "lab-results",
--         "relatedAction": [{ "actionId": "lab-order",
--                             "relationship": "after-end",
--                             "offsetDuration": { "value": 3, "unit": "d" } }] }
--
--   reads as "lab-results happens 3 days after lab-order ends" — `actionId` names the
--   PREREQUISITE and the declaring action is the dependent.
--
--   The service read it as a forward pointer ("completing lab-order creates lab-results"),
--   and every protocol authored against that reading encodes the graph reversed. The code
--   has been corrected (PlanDefinitionParser.classifyRelationship /
--   buildDependencyGraph, StepInstanceService.createDependentSteps), so the stored
--   definitions must be flipped to match, in the same release.
--
--   Left unflipped, a definition yields zero prerequisite edges under the new reader:
--   progressive instantiation, ORDER_VIOLATION detection and the mandatory-step backfill
--   all go SILENTLY quiet for that protocol rather than failing loudly.
--
-- WHAT IS AND IS NOT MIGRATED
--   Only protocol_definition.definition, and within it only the `after-*` family (plus edges with
--   no `relationship` at all, which default to after-end). Such an edge A -> B becomes B -> A, with
--   `relationship` and `offsetDuration` travelling with the edge to its new owner.
--
--   `before-*` edges are deliberately LEFT ALONE. `A.relatedAction = {B, before}` reads "A happens
--   before B", which already states the author's intended ordering correctly — the parser reads
--   both families now (PlanDefinitionParser.classifyRelationship) and normalizes them into one
--   directed graph, so reversing a before-* edge would invert an ordering that was already right.
--
--   `concurrent-*` edges are also left alone, but they state no ordering at all and so no longer
--   sequence steps. The old reader treated them as sequential; any that exist are listed by the
--   RAISE NOTICE below so the behaviour change is visible in the Flyway log rather than silent.
--
--   Nothing else needs migrating, because flipping the reader and the data TOGETHER is
--   behaviour-neutral: old code + old JSON and new code + flipped JSON create the same
--   dependent step with the same due/overdue/missed dates.
--     - trigger_index      — built from action.trigger[] only, never from relatedAction.
--                            No rebuild, no reindex.
--     - step_instance      — existing due/overdue/missed dates were computed correctly under
--                            the old pairing and stay correct. Do NOT recompute; in-flight
--                            PENDING/DUE rows keep their schedules.
--     - deviation, *_history — append-only records that were correct when written.
--
-- FLAT ID SPACE
--   Reversal is global across the whole action tree, not per nesting level: action ids are
--   unique across all levels (PlanDefinitionParser.validateActionIds) and edges routinely
--   cross levels — e.g. a top-level `consultation` pointing at its own nested sub-step.
--
-- DANGLING EDGES
--   An edge whose target action does not exist anywhere in the definition (an authoring typo)
--   cannot be expressed in the corrected direction — there is no action to hang it on — so it
--   is dropped. Such an edge was already inert: the old code found no metadata for the target,
--   treated it as non-mandatory and skipped instantiation.
--
-- IDEMPOTENCE
--   Not idempotent by nature — reversing twice restores the original. Flyway runs it exactly
--   once per database, which is the guarantee relied on here.
-- =============================================================================

-- Every action id in the tree, at any depth.
CREATE OR REPLACE FUNCTION cce_v9_action_ids(actions JSONB)
RETURNS JSONB LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    act JSONB;
    ids JSONB := '[]'::JSONB;
BEGIN
    IF actions IS NULL OR jsonb_typeof(actions) <> 'array' THEN
        RETURN ids;
    END IF;

    FOR act IN SELECT jsonb_array_elements(actions) LOOP
        IF act ? 'id' THEN
            ids := ids || jsonb_build_array(act -> 'id');
        END IF;
        IF act ? 'action' THEN
            ids := ids || cce_v9_action_ids(act -> 'action');
        END IF;
    END LOOP;

    RETURN ids;
END;
$$;

-- True for the edges this migration reverses: the after-* family, and an edge with no
-- relationship at all (which the parser defaults to after-end).
CREATE OR REPLACE FUNCTION cce_v9_is_after_edge(edge JSONB)
RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS $$
    SELECT edge -> 'relationship' IS NULL
        OR edge ->> 'relationship' IN ('after', 'after-start', 'after-end');
$$;

-- Every after-* edge, inverted: {target: <the action that will own it>, edge: <relatedAction entry>}.
CREATE OR REPLACE FUNCTION cce_v9_reversed_edges(actions JSONB, valid_ids JSONB)
RETURNS JSONB LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    act    JSONB;
    edge   JSONB;
    result JSONB := '[]'::JSONB;
BEGIN
    IF actions IS NULL OR jsonb_typeof(actions) <> 'array' THEN
        RETURN result;
    END IF;

    FOR act IN SELECT jsonb_array_elements(actions) LOOP
        FOR edge IN SELECT jsonb_array_elements(COALESCE(act -> 'relatedAction', '[]'::JSONB)) LOOP
            -- before-*/concurrent-*: keep as authored (see header)
            CONTINUE WHEN NOT cce_v9_is_after_edge(edge);
            -- Dangling target: no action to hand the reversed edge to, so drop it
            CONTINUE WHEN NOT (valid_ids @> jsonb_build_array(edge -> 'actionId'));

            result := result || jsonb_build_array(jsonb_build_object(
                'target', edge -> 'actionId',
                'edge',   jsonb_set(edge, '{actionId}', act -> 'id')));
        END LOOP;

        IF act ? 'action' THEN
            result := result || cce_v9_reversed_edges(act -> 'action', valid_ids);
        END IF;
    END LOOP;

    RETURN result;
END;
$$;

-- Rebuild the tree: drop every after-* relatedAction, keep the rest, attach the reversed ones.
CREATE OR REPLACE FUNCTION cce_v9_apply_reversed(actions JSONB, inbound JSONB)
RETURNS JSONB LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    act     JSONB;
    kept    JSONB;
    edges   JSONB;
    rebuilt JSONB := '[]'::JSONB;
BEGIN
    IF actions IS NULL OR jsonb_typeof(actions) <> 'array' THEN
        RETURN actions;
    END IF;

    FOR act IN SELECT jsonb_array_elements(actions) LOOP
        -- Preserve the edges this migration does not touch
        SELECT COALESCE(jsonb_agg(e), '[]'::JSONB) INTO kept
        FROM jsonb_array_elements(COALESCE(act -> 'relatedAction', '[]'::JSONB)) e
        WHERE NOT cce_v9_is_after_edge(e);

        edges := kept || COALESCE(inbound -> (act ->> 'id'), '[]'::JSONB);

        IF jsonb_array_length(edges) > 0 THEN
            act := jsonb_set(act, '{relatedAction}', edges, true);
        ELSE
            act := act - 'relatedAction';
        END IF;

        IF act ? 'action' THEN
            act := jsonb_set(act, '{action}', cce_v9_apply_reversed(act -> 'action', inbound), true);
        END IF;

        rebuilt := rebuilt || jsonb_build_array(act);
    END LOOP;

    RETURN rebuilt;
END;
$$;

CREATE OR REPLACE FUNCTION cce_v9_reverse_related_action(actions JSONB)
RETURNS JSONB LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    item        JSONB;
    target      TEXT;
    inbound     JSONB := '{}'::JSONB;
BEGIN
    FOR item IN SELECT jsonb_array_elements(
                    cce_v9_reversed_edges(actions, cce_v9_action_ids(actions))) LOOP
        target  := item ->> 'target';
        inbound := jsonb_set(
            inbound,
            ARRAY[target],
            COALESCE(inbound -> target, '[]'::JSONB) || jsonb_build_array(item -> 'edge'),
            true);
    END LOOP;

    RETURN cce_v9_apply_reversed(actions, inbound);
END;
$$;

-- Surface concurrent-* edges: they no longer sequence steps (see header), so anyone reading the
-- Flyway log can decide whether the protocol needs re-authoring with after-*/before-*.
DO $$
DECLARE
    row_ RECORD;
    found BOOLEAN := false;
BEGIN
    FOR row_ IN
        SELECT url, version, e ->> 'actionId' AS target, e ->> 'relationship' AS relationship
        FROM protocol_definition,
             LATERAL jsonb_path_query(definition, 'strict $.**.relatedAction[*]') e
        WHERE e ->> 'relationship' LIKE 'concurrent%'
    LOOP
        found := true;
        RAISE NOTICE 'V9: protocol % v% has a concurrent-* relatedAction -> % (relationship=%). '
                     'concurrent-* states no ordering and will NOT sequence these steps; '
                     're-author with after-*/before-* if one must follow the other.',
                     row_.url, row_.version, row_.target, row_.relationship;
    END LOOP;

    IF NOT found THEN
        RAISE NOTICE 'V9: no concurrent-* relatedAction found — nothing needs re-authoring.';
    END IF;
END;
$$;

UPDATE protocol_definition
SET definition = jsonb_set(definition, '{action}',
                           cce_v9_reverse_related_action(definition -> 'action'), true),
    updated_at = now()
WHERE jsonb_typeof(definition -> 'action') = 'array'
  AND jsonb_path_exists(definition, 'strict $.**.relatedAction');

DROP FUNCTION cce_v9_reverse_related_action(JSONB);
DROP FUNCTION cce_v9_is_after_edge(JSONB);
DROP FUNCTION cce_v9_apply_reversed(JSONB, JSONB);
DROP FUNCTION cce_v9_reversed_edges(JSONB, JSONB);
DROP FUNCTION cce_v9_action_ids(JSONB);
