-- V6: API keys for machine-to-machine integration.
--
-- An API key authenticates as a REAL service-member user row so every existing
-- foreign key (documents.uploaded_by, chat_sessions.user_id, audit_logs.user_id,
-- ...) stays satisfied: the "mà mọi actor đều là member" model. The user row is
-- provisioned with an unusable password credential (never matches the encoder)
-- and joins exactly one workspace; the key's authority is that workspace role,
-- re-resolved live on every request exactly like user JWTs.
--
-- Only the SHA-256 hash of the presented secret is stored; the plaintext is
-- shown to the creator exactly once in the creation response.

CREATE TABLE public.api_keys (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    org_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    name character varying(120) NOT NULL,
    key_prefix character varying(16) NOT NULL,
    key_hash character varying(64) NOT NULL,
    role character varying(32) DEFAULT 'MEMBER'::character varying NOT NULL,
    created_by uuid NOT NULL,
    last_used_at timestamp without time zone,
    expires_at timestamp without time zone,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_api_keys PRIMARY KEY (id),
    CONSTRAINT chk_api_key_role CHECK (((role)::text = ANY ((ARRAY['WORKSPACE_ADMIN'::character varying, 'MEMBER'::character varying])::text[]))),
    CONSTRAINT chk_api_key_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT uq_api_keys_key_hash UNIQUE (key_hash)
);

COMMENT ON TABLE public.api_keys IS 'Machine-to-machine credentials scoped to one workspace; authenticate via Authorization: Bearer ovk_...';

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);
ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);
ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);

CREATE INDEX idx_api_keys_org ON public.api_keys USING btree (org_id);
CREATE INDEX idx_api_keys_tenant ON public.api_keys USING btree (tenant_id);
