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

-- Issue #8: SLA tuning for the e-Office approval timers.
-- Rows exist only for organizations that overrode the application defaults
-- (PT24H reminder / PT48H escalation in WorkflowProperties); the timers reach
-- the BPMN process as ISO-8601 duration process variables at start time.
-- Minutes keep the demo path honest: shortening to 1 minute is a legal value.
-- Column shape mirrors the WorkflowSlaConfig entity, which extends BaseEntity
-- (id uuid primary key, created_at/updated_at bookkeeping).
CREATE TABLE workflow_sla_configs (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    organization_id uuid NOT NULL UNIQUE REFERENCES organizations (id) ON DELETE CASCADE,
    reminder_minutes integer NOT NULL CHECK (reminder_minutes >= 1),
    escalation_minutes integer NOT NULL CHECK (escalation_minutes >= 1),
    updated_by uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE workflow_sla_configs IS
    'Per-organization approval SLA overrides (issue #8): reminder and escalation timers in minutes.';
