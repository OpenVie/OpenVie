-- Copyright 2026 LinkedNodeDigital
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- V25: repair legacy manifests that carried contract keys in the wrong place.
--
-- Older AI drafts (and the installations created from them) repeated
-- `storage` on entities/fields, and a few used `enumValues` instead of
-- `options`. The validator reports those as unknown keys, so upgrades and
-- composer validation fail even though the app works. This migration removes
-- the nested keys from every stored manifest (drafts, org templates and
-- frozen installations) and promotes `enumValues` to `options` when options
-- are missing or still the placeholder ["default"].
--
-- The manifest-level `storage` is preserved. The migration is idempotent:
-- rows without nested keys are left untouched.

CREATE OR REPLACE FUNCTION public._bizapp_clean_manifest(m jsonb)
RETURNS jsonb
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
        WHEN jsonb_typeof(m -> 'entities') = 'array' THEN
            jsonb_set(m, '{entities}', (
                SELECT jsonb_agg(
                    CASE
                        WHEN e.value ? 'fields' THEN
                            (e.value - 'storage') || jsonb_build_object('fields', COALESCE((
                                SELECT jsonb_agg(
                                    CASE
                                        WHEN f.value ? 'enumValues'
                                             AND (NOT (f.value ? 'options')
                                                  OR f.value -> 'options' = '["default"]'::jsonb)
                                            THEN ((f.value - 'enumValues') - 'storage')
                                                 || jsonb_build_object('options', f.value -> 'enumValues')
                                        ELSE (f.value - 'storage') - 'enumValues'
                                    END
                                    ORDER BY f.ord
                                )
                                FROM jsonb_array_elements(e.value -> 'fields')
                                     WITH ORDINALITY AS f(value, ord)
                            ), '[]'::jsonb))
                        ELSE e.value - 'storage'
                    END
                    ORDER BY e.ord
                )
                FROM jsonb_array_elements(m -> 'entities')
                     WITH ORDINALITY AS e(value, ord)
            ))
        ELSE m
    END
$$;

UPDATE bizapp_drafts
SET manifest = jsonb_set(public._bizapp_clean_manifest(manifest), '{storage}', '"jsonb"'::jsonb)
WHERE jsonb_typeof(manifest -> 'entities') = 'array'
  AND manifest ->> 'storage' = 'typed';

UPDATE bizapp_marketplace_templates
SET manifest = jsonb_set(public._bizapp_clean_manifest(manifest), '{storage}', '"jsonb"'::jsonb)
WHERE jsonb_typeof(manifest -> 'entities') = 'array'
  AND manifest ->> 'storage' = 'typed';

UPDATE bizapp_installations
SET manifest = jsonb_set(public._bizapp_clean_manifest(manifest), '{storage}', '"jsonb"'::jsonb)
WHERE jsonb_typeof(manifest -> 'entities') = 'array'
  AND manifest ->> 'storage' = 'typed';

UPDATE bizapp_drafts
SET manifest = public._bizapp_clean_manifest(manifest)
WHERE jsonb_typeof(manifest -> 'entities') = 'array';

UPDATE bizapp_marketplace_templates
SET manifest = public._bizapp_clean_manifest(manifest)
WHERE jsonb_typeof(manifest -> 'entities') = 'array';

UPDATE bizapp_installations
SET manifest = public._bizapp_clean_manifest(manifest)
WHERE jsonb_typeof(manifest -> 'entities') = 'array';

DROP FUNCTION public._bizapp_clean_manifest(jsonb);
