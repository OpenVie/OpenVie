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
