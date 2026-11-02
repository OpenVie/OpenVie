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

CREATE TABLE public.bizapp_crm_leads (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    org_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    email character varying(200),
    phone character varying(40),
    stage character varying(32) NOT NULL,
    deal_value numeric(14,2),
    notes character varying(500),
    runtime jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_by uuid NOT NULL,
    updated_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_bizapp_crm_leads PRIMARY KEY (id),
    CONSTRAINT ck_bizapp_crm_leads_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'ARCHIVED'::character varying])::text[]))),
    CONSTRAINT ck_bizapp_crm_leads_stage CHECK (((stage)::text = ANY ((ARRAY['NEW'::character varying, 'CONTACTED'::character varying, 'QUALIFIED'::character varying, 'WON'::character varying, 'LOST'::character varying])::text[])))
);

COMMENT ON TABLE public.bizapp_crm_leads IS 'Dedicated typed table for platform bundled CRM leads.';

ALTER TABLE ONLY public.bizapp_crm_leads
    ADD CONSTRAINT bizapp_crm_leads_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);
ALTER TABLE ONLY public.bizapp_crm_leads
    ADD CONSTRAINT bizapp_crm_leads_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);
ALTER TABLE ONLY public.bizapp_crm_leads
    ADD CONSTRAINT bizapp_crm_leads_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);

CREATE INDEX idx_bizapp_crm_leads_scope
    ON public.bizapp_crm_leads USING btree (tenant_id, updated_at DESC);
CREATE INDEX idx_bizapp_crm_leads_stage
    ON public.bizapp_crm_leads USING btree (stage);

-- Data migration: move existing CRM leads rows from JSONB store
INSERT INTO public.bizapp_crm_leads (
    id, org_id, tenant_id, name, email, phone, stage, deal_value, notes, runtime, status, created_by, updated_by, created_at, updated_at
)
SELECT
    id,
    org_id,
    tenant_id,
    COALESCE(payload ->> 'name', 'Unnamed Lead'),
    payload ->> 'email',
    payload ->> 'phone',
    COALESCE(payload ->> 'stage', 'NEW'),
    CASE WHEN (payload ->> 'dealValue') ~ '^-?[0-9]+(\.[0-9]+)?$' THEN (payload ->> 'dealValue')::numeric(14,2) ELSE NULL END,
    payload ->> 'notes',
    runtime,
    status,
    created_by,
    updated_by,
    created_at,
    updated_at
FROM public.bizapp_records
WHERE app_kind = 'crm' AND entity = 'leads';

DELETE FROM public.bizapp_records
WHERE app_kind = 'crm' AND entity = 'leads';
