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

-- V11: BizApp installations (Tier 3).
--
-- One row per installed app per workspace (tenant). `manifest` freezes the
-- exact JSON accepted at install time: later template edits never mutate a
-- running app; upgrades re-validate and replace via a new installation
-- version. `process_key` pins the Flowable definition deployed for the app's
-- approval_workitem block; uninstall is soft (status DISABLED) so records and
-- audit history stay intact.

CREATE TABLE public.bizapp_installations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    org_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    kind character varying(64) NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    manifest jsonb NOT NULL,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    process_key character varying(128),
    installed_by uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    CONSTRAINT pk_bizapp_installations PRIMARY KEY (id),
    CONSTRAINT ck_bizapp_install_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[]))),
    CONSTRAINT uq_bizapp_installations_tenant_kind UNIQUE (tenant_id, kind)
);

COMMENT ON TABLE public.bizapp_installations IS 'BizApps installed into one workspace; manifest frozen per install so upgrades are explicit.';

ALTER TABLE ONLY public.bizapp_installations
    ADD CONSTRAINT bizapp_installations_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);
ALTER TABLE ONLY public.bizapp_installations
    ADD CONSTRAINT bizapp_installations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);
ALTER TABLE ONLY public.bizapp_installations
    Add CONSTRAINT bizapp_installations_installed_by_fkey FOREIGN KEY (installed_by) REFERENCES public.users(id);

CREATE INDEX idx_bizapp_installations_org ON public.bizapp_installations USING btree (org_id);
CREATE INDEX idx_bizapp_installations_tenant ON public.bizapp_installations USING btree (tenant_id);
