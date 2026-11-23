-- V26: remove the inert registry/form/timeline block types from stored
-- manifests. Their runtime equivalents are still `ui.views` entries such as
-- `registry:requests`, `form:requests` and `timeline:requests`; those strings
-- are deliberately left untouched.
--
-- The migration is idempotent: a manifest with no matching block is not
-- rewritten. Drafts that were repaired get a validation marker so the composer
-- revalidates them before install.

CREATE OR REPLACE FUNCTION public._bizapp_strip_presentation_blocks(m jsonb)
RETURNS jsonb
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    removed_ids text[];
    filtered_blocks jsonb;
    next_manifest jsonb := m;
    entity jsonb;
    entities jsonb := '[]'::jsonb;
    hooks jsonb;
    hook_key text;
    targets jsonb;
    kept_targets jsonb;
BEGIN
    IF m IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT coalesce(array_agg(block->>'id'), ARRAY[]::text[])
    INTO removed_ids
    FROM jsonb_array_elements(
        CASE WHEN jsonb_typeof(m->'blocks') = 'array' THEN m->'blocks' ELSE '[]'::jsonb END
    ) AS block
    WHERE block->>'type' IN ('registry', 'form', 'timeline')
      AND block->>'id' IS NOT NULL;

    IF jsonb_typeof(m->'blocks') = 'array' THEN
        SELECT coalesce(jsonb_agg(block ORDER BY ordinality), '[]'::jsonb)
        INTO filtered_blocks
        FROM jsonb_array_elements(m->'blocks') WITH ORDINALITY AS entry(block, ordinality)
        WHERE block->>'type' NOT IN ('registry', 'form', 'timeline');

        next_manifest := jsonb_set(next_manifest, '{blocks}', filtered_blocks);
    END IF;

    IF cardinality(removed_ids) = 0 THEN
        RETURN next_manifest;
    END IF;

    IF next_manifest #> '{ui,layout}' IS NOT NULL THEN
        next_manifest := jsonb_set(
            next_manifest,
            '{ui,layout}',
            (next_manifest #> '{ui,layout}') - removed_ids
        );
    END IF;

    -- Defensive: old invalid manifests may have hooked a presentation block.
    -- Valid manifests never do because the validator rejects those targets.
    IF jsonb_typeof(next_manifest->'entities') = 'array' THEN
        FOR entity IN SELECT value FROM jsonb_array_elements(next_manifest->'entities')
        LOOP
            IF jsonb_typeof(entity->'hooks') = 'object' THEN
                hooks := '{}'::jsonb;
                FOR hook_key, targets IN SELECT key, value FROM jsonb_each(entity->'hooks')
                LOOP
                    SELECT coalesce(jsonb_agg(target), '[]'::jsonb)
                    INTO kept_targets
                    FROM jsonb_array_elements_text(targets) AS target
                    WHERE target <> ALL(removed_ids);

                    IF jsonb_array_length(kept_targets) > 0 THEN
                        hooks := hooks || jsonb_build_object(hook_key, kept_targets);
                    END IF;
                END LOOP;

                entity := CASE
                    WHEN hooks = '{}'::jsonb THEN entity - 'hooks'
                    ELSE jsonb_set(entity, '{hooks}', hooks)
                END;
            END IF;
            entities := entities || jsonb_build_array(entity);
        END LOOP;
        next_manifest := jsonb_set(next_manifest, '{entities}', entities);
    END IF;

    RETURN next_manifest;
END;
$$;

UPDATE bizapp_drafts
SET manifest = public._bizapp_strip_presentation_blocks(manifest),
    validation = jsonb_build_object(
        'ok', false,
        'errors', jsonb_build_array(
            'Legacy presentation blocks were removed; open and save to revalidate'
        )
    )
WHERE EXISTS (
    SELECT 1
    FROM jsonb_array_elements(
        CASE WHEN jsonb_typeof(manifest->'blocks') = 'array' THEN manifest->'blocks' ELSE '[]'::jsonb END
    ) AS block
    WHERE block->>'type' IN ('registry', 'form', 'timeline')
);

UPDATE bizapp_installations
SET manifest = public._bizapp_strip_presentation_blocks(manifest)
WHERE EXISTS (
    SELECT 1
    FROM jsonb_array_elements(
        CASE WHEN jsonb_typeof(manifest->'blocks') = 'array' THEN manifest->'blocks' ELSE '[]'::jsonb END
    ) AS block
    WHERE block->>'type' IN ('registry', 'form', 'timeline')
);

UPDATE bizapp_marketplace_templates
SET manifest = public._bizapp_strip_presentation_blocks(manifest)
WHERE EXISTS (
    SELECT 1
    FROM jsonb_array_elements(
        CASE WHEN jsonb_typeof(manifest->'blocks') = 'array' THEN manifest->'blocks' ELSE '[]'::jsonb END
    ) AS block
    WHERE block->>'type' IN ('registry', 'form', 'timeline')
);
