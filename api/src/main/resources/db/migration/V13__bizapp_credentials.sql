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

-- V13: BizApp credential vault (Tier 3).
--
-- Outbound integration secrets (external_call, future e-sign) live here
-- encrypted at rest with AES-256-GCM under BIZAPP_ENC_KEY, the same envelope
-- discipline as notification channel credentials. Rows are ORG-scoped: an org
-- owns one namespace and only ORG_OWNERs manage it. No plaintext ever leaves
-- the store; manifests reference credentials by id only.

CREATE TABLE public.bizapp_credentials (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    org_id uuid NOT NULL,
    name character varying(120) NOT NULL,
    format character varying(24) NOT NULL,
    encrypted_data text NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_bizapp_credentials PRIMARY KEY (id),
    CONSTRAINT ck_bizapp_credentials_format CHECK (((format)::text = ANY ((ARRAY['api_key'::character varying, 'bearer'::character varying, 'basic'::character varying])::text[]))),
    CONSTRAINT uq_bizapp_credentials_org_name UNIQUE (org_id, name)
);

COMMENT ON TABLE public.bizapp_credentials IS 'Org-scoped outbound secrets, AES-256-GCM at rest; manifests reference by id.';

ALTER TABLE ONLY public.bizapp_credentials
    ADD CONSTRAINT bizapp_credentials_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);
ALTER TABLE ONLY public.bizapp_credentials
    ADD CONSTRAINT bizapp_credentials_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);

CREATE INDEX idx_bizapp_credentials_org ON public.bizapp_credentials USING btree (org_id);
