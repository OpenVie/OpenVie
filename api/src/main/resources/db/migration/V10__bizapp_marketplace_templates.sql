-- V10: BizApp marketplace templates (Tier 3).
--
-- A template is an organization-crafted copy of an installed BizApp manifest.
-- Bundled templates ship as JSON in api/src/main/resources/marketplace/ and are
-- not journaled here; this table stores only the org's own reusable templates.
-- Manifests are validated by BizAppManifestValidator before storage, so the
-- column trusts the validator rather than the client.

CREATE TABLE public.bizapp_marketplace_templates (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    org_id uuid NOT NULL,
    kind character varying(64) NOT NULL,
    name character varying(120) NOT NULL,
    summary character varying(500),
    version integer DEFAULT 1 NOT NULL,
    manifest jsonb NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_bizapp_marketplace_templates PRIMARY KEY (id),
    CONSTRAINT uq_bizapp_templates_org_kind_name UNIQUE (org_id, kind, name)
);

COMMENT ON TABLE public.bizapp_marketplace_templates IS 'Organization-scoped BizApp template catalog; one manifest JSON per reusable app.';

ALTER TABLE ONLY public.bizapp_marketplace_templates
    ADD CONSTRAINT bizapp_templates_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);
ALTER TABLE ONLY public.bizapp_marketplace_templates
    ADD CONSTRAINT bizapp_templates_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);

CREATE INDEX idx_bizapp_templates_org ON public.bizapp_marketplace_templates USING btree (org_id);
