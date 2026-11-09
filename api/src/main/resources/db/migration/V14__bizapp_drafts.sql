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

CREATE TABLE public.bizapp_drafts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    org_id uuid NOT NULL,
    created_by uuid NOT NULL,
    source character varying(32) NOT NULL,
    title character varying(200) NOT NULL,
    manifest jsonb NOT NULL,
    validation jsonb DEFAULT '{}'::jsonb NOT NULL,
    meta jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_bizapp_drafts PRIMARY KEY (id),
    CONSTRAINT ck_bizapp_drafts_source CHECK (((source)::text = ANY ((ARRAY['ai'::character varying, 'canvas'::character varying, 'import'::character varying, 'remix'::character varying])::text[])))
);

COMMENT ON TABLE public.bizapp_drafts IS 'Draft BizApp manifests authored via AI, canvas, import, or remix. Drafts are never executable until installed.';

ALTER TABLE ONLY public.bizapp_drafts
    ADD CONSTRAINT bizapp_drafts_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);
ALTER TABLE ONLY public.bizapp_drafts
    ADD CONSTRAINT bizapp_drafts_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);

CREATE INDEX idx_bizapp_drafts_org_updated
    ON public.bizapp_drafts USING btree (org_id, updated_at DESC);
