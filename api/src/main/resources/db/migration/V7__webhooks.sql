-- V7: webhook endpoints + delivery outbox.
--
-- Open-core integration surface: instead of integrators polling status
-- endpoints, the platform pushes signed events. Endpoints are managed by the
-- organization owner; the signing secret is stored with the shared
-- NOTIFICATION_ENC_KEY cipher (same secret-at-rest guarantee as mail-channel
-- credentials). Deliveries follow the existing internal outbox discipline:
-- a transactional insert next to the domain change, then an asynchronous
-- dispatcher with bounded retries and an explicit DEAD status.

CREATE TABLE public.webhook_endpoints (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    org_id uuid NOT NULL,
    name character varying(120) NOT NULL,
    url character varying(1000) NOT NULL,
    secret_encrypted text,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    events jsonb,
    created_by uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_webhook_endpoints PRIMARY KEY (id),
    CONSTRAINT chk_webhook_endpoint_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[])))
);

COMMENT ON TABLE public.webhook_endpoints IS 'Signed event push targets per organization; POST with X-OpenVie-Signature: t=...,v1=...';

CREATE TABLE public.webhook_deliveries (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    event_id uuid NOT NULL,
    endpoint_id uuid NOT NULL,
    org_id uuid NOT NULL,
    tenant_id uuid,
    event_type character varying(150) NOT NULL,
    payload jsonb NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp without time zone DEFAULT now() NOT NULL,
    last_http_status integer,
    last_error text,
    delivered_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_webhook_deliveries PRIMARY KEY (id),
    CONSTRAINT chk_webhook_delivery_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'DELIVERED'::character varying, 'DEAD'::character varying])::text[])))
);

COMMENT ON TABLE public.webhook_deliveries IS 'Transactional webhook outbox: one row per (event, endpoint); delivered asynchronously with retries';

ALTER TABLE ONLY public.webhook_endpoints
    ADD CONSTRAINT webhook_endpoints_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);
ALTER TABLE ONLY public.webhook_endpoints
    ADD CONSTRAINT webhook_endpoints_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);
ALTER TABLE ONLY public.webhook_deliveries
    ADD CONSTRAINT webhook_deliveries_endpoint_id_fkey FOREIGN KEY (endpoint_id) REFERENCES public.webhook_endpoints(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.webhook_deliveries
    ADD CONSTRAINT webhook_deliveries_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);

CREATE INDEX idx_webhook_deliveries_due ON public.webhook_deliveries USING btree (status, next_attempt_at);
CREATE INDEX idx_webhook_deliveries_endpoint ON public.webhook_deliveries USING btree (endpoint_id, created_at);
CREATE INDEX idx_webhook_endpoints_org ON public.webhook_endpoints USING btree (org_id);
