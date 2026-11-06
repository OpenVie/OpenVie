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

-- AI-generated approval blocks must use the only BPMN approval template
-- bundled for BizApps. Older drafts may contain model-invented paths that
-- passed syntax validation but fail during installation.
UPDATE public.bizapp_drafts
SET manifest = jsonb_set(
        manifest,
        '{blocks}',
        (
            SELECT jsonb_agg(
                    CASE
                        WHEN block.elem ->> 'type' = 'approval_workitem' THEN
                            jsonb_set(
                                    block.elem,
                                    '{params,bpmnTemplate}',
                                    '"processes/bizapp-single-approval.bpmn20.xml"'::jsonb,
                                    false
                            )
                        ELSE block.elem
                    END
                    ORDER BY block.ordinality
            )
            FROM jsonb_array_elements(manifest -> 'blocks') WITH ORDINALITY AS block(elem, ordinality)
        ),
        false
    ),
    updated_at = now()
WHERE source = 'ai'
  AND jsonb_typeof(manifest -> 'blocks') = 'array'
  AND EXISTS (
        SELECT 1
        FROM jsonb_array_elements(manifest -> 'blocks') AS block(elem)
        WHERE block.elem ->> 'type' = 'approval_workitem'
          AND block.elem #>> '{params,bpmnTemplate}'
              IS DISTINCT FROM 'processes/bizapp-single-approval.bpmn20.xml'
    );
