-- V23: BizApp connector layer (HTTP + MQTT durable jobs, typed sinks).
--
-- external_call stops being an inline HTTP call: every hook/schedule run
-- becomes a durable job executed after commit, retried with exponential
-- backoff, audited and metered per attempt. Telemetry appends dedupe on a
-- caller-declared key instead of creating a row per poll. Credentials gain an
-- optional workspace scope so one org vault can serve multiple workspaces
-- with the same entry names.

ALTER TABLE public.bizapp_records ADD COLUMN dedupe_key character varying(64);
CREATE UNIQUE INDEX uq_bizapp_records_dedupe ON public.bizapp_records
  (tenant_id, app_kind, entity, dedupe_key) WHERE dedupe_key IS NOT NULL;

CREATE TABLE public.bizapp_connector_jobs (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  org_id uuid NOT NULL,
  tenant_id uuid NOT NULL,
  installation_id uuid NOT NULL,
  app_kind character varying(64) NOT NULL,
  entity character varying(64) NOT NULL,
  record_id uuid NOT NULL,
  block_id character varying(64) NOT NULL,
  trigger_kind character varying(16) NOT NULL,
  status character varying(16) DEFAULT 'PENDING' NOT NULL,
  attempt integer DEFAULT 0 NOT NULL,
  max_attempts integer DEFAULT 3 NOT NULL,
  backoff_ms bigint DEFAULT 10000 NOT NULL,
  next_attempt_at timestamp without time zone DEFAULT now() NOT NULL,
  last_error text,
  created_at timestamp without time zone DEFAULT now() NOT NULL,
  updated_at timestamp without time zone DEFAULT now() NOT NULL,
  CONSTRAINT pk_bizapp_connector_jobs PRIMARY KEY (id),
  CONSTRAINT ck_bizapp_connector_jobs_trigger CHECK (trigger_kind IN ('HOOK','SCHEDULE')),
  CONSTRAINT ck_bizapp_connector_jobs_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','DEAD'))
);
CREATE INDEX idx_bizapp_connector_jobs_due ON public.bizapp_connector_jobs (status, next_attempt_at);
CREATE INDEX idx_bizapp_connector_jobs_key ON public.bizapp_connector_jobs
  (installation_id, block_id, record_id, status);

COMMENT ON TABLE public.bizapp_connector_jobs IS 'Durable external_call runs: hook/schedule triggers, retried with backoff, audited per attempt.';

ALTER TABLE ONLY public.bizapp_connector_jobs
    ADD CONSTRAINT bizapp_connector_jobs_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);
ALTER TABLE ONLY public.bizapp_connector_jobs
    ADD CONSTRAINT bizapp_connector_jobs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);
ALTER TABLE ONLY public.bizapp_connector_jobs
    ADD CONSTRAINT bizapp_connector_jobs_installation_id_fkey FOREIGN KEY (installation_id) REFERENCES public.bizapp_installations(id);

ALTER TABLE public.bizapp_credentials ADD COLUMN tenant_id uuid;
ALTER TABLE public.bizapp_credentials DROP CONSTRAINT uq_bizapp_credentials_org_name;
ALTER TABLE public.bizapp_credentials ADD CONSTRAINT uq_bizapp_credentials_org_tenant_name
  UNIQUE NULLS NOT DISTINCT (org_id, tenant_id, name);
ALTER TABLE public.bizapp_credentials DROP CONSTRAINT ck_bizapp_credentials_format;
ALTER TABLE public.bizapp_credentials ADD CONSTRAINT ck_bizapp_credentials_format
  CHECK (format IN ('api_key','bearer','basic','oauth2','mqtt','hmac'));
CREATE INDEX idx_bizapp_credentials_org_tenant ON public.bizapp_credentials (org_id, tenant_id);
