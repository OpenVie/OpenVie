-- V12: BizApp generic record store (Tier 3).
--
-- Community/BizApp entities live here as JSONB `payload` rows; the manifest
-- (stored on the installation) defines the validated field schema at the app
-- layer, while PostgreSQL keeps only structural invariants: tenant scoping,
-- ACTIVE-only reads, and owning user. Flagship apps may graduate to dedicated
-- tables in later versions without touching this store.

CREATE TABLE public.bizapp_records (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    org_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    app_kind character varying(64) NOT NULL,
    entity character varying(64) NOT NULL,
    payload jsonb NOT NULL,
    runtime jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_by uuid NOT NULL,
    updated_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_bizapp_records PRIMARY KEY (id),
    CONSTRAINT ck_bizapp_records_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'ARCHIVED'::character varying])::text[])))
);

COMMENT ON TABLE public.bizapp_records IS 'Tenant-scoped BizApp entity records; payload validated against the installed manifest, not a DDL.';

ALTER TABLE ONLY public.bizapp_records
    ADD CONSTRAINT bizapp_records_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);
ALTER TABLE ONLY public.bizapp_records
    ADD CONSTRAINT bizapp_records_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);
ALTER TABLE ONLY public.bizapp_records
    ADD CONSTRAINT bizapp_records_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);

CREATE INDEX idx_bizapp_records_scope
    ON public.bizapp_records USING btree (tenant_id, app_kind, entity, updated_at DESC);
CREATE INDEX idx_bizapp_records_payload
    ON public.bizapp_records USING gin (payload jsonb_path_ops);
CREATE INDEX idx_bizapp_records_app ON public.bizapp_records USING btree (app_kind);
