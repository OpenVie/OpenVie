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

CREATE TABLE public.bizapp_booking_appointments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    org_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    customer_name character varying(200) NOT NULL,
    customer_email character varying(200) NOT NULL,
    service character varying(64) NOT NULL,
    booking_date date NOT NULL,
    guests integer,
    notes character varying(500),
    runtime jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_by uuid NOT NULL,
    updated_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_bizapp_booking_appointments PRIMARY KEY (id),
    CONSTRAINT ck_bizapp_booking_appointments_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'ARCHIVED'::character varying])::text[]))),
    CONSTRAINT ck_bizapp_booking_appointments_service CHECK (((service)::text = ANY ((ARRAY['consultation'::character varying, 'tour'::character varying, 'meeting-room'::character varying])::text[])))
);

COMMENT ON TABLE public.bizapp_booking_appointments IS 'Dedicated typed table for platform bundled Booking appointments.';

ALTER TABLE ONLY public.bizapp_booking_appointments
    ADD CONSTRAINT bizapp_booking_appointments_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organizations(id);
ALTER TABLE ONLY public.bizapp_booking_appointments
    ADD CONSTRAINT bizapp_booking_appointments_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);
ALTER TABLE ONLY public.bizapp_booking_appointments
    ADD CONSTRAINT bizapp_booking_appointments_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);

CREATE INDEX idx_bizapp_booking_appointments_scope
    ON public.bizapp_booking_appointments USING btree (tenant_id, updated_at DESC);
CREATE INDEX idx_bizapp_booking_appointments_service
    ON public.bizapp_booking_appointments USING btree (service);

-- Data migration: move existing booking rows from JSONB store
INSERT INTO public.bizapp_booking_appointments (
    id, org_id, tenant_id, customer_name, customer_email, service, booking_date, guests, notes, runtime, status, created_by, updated_by, created_at, updated_at
)
SELECT
    id,
    org_id,
    tenant_id,
    COALESCE(payload ->> 'customerName', 'Guest'),
    COALESCE(payload ->> 'customerEmail', 'guest@example.com'),
    COALESCE(payload ->> 'service', 'consultation'),
    COALESCE(CASE WHEN (payload ->> 'bookingDate') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' THEN (payload ->> 'bookingDate')::date ELSE CURRENT_DATE END, CURRENT_DATE),
    CASE WHEN (payload ->> 'guests') ~ '^[0-9]+$' THEN (payload ->> 'guests')::integer ELSE NULL END,
    payload ->> 'notes',
    runtime,
    status,
    created_by,
    updated_by,
    created_at,
    updated_at
FROM public.bizapp_records
WHERE app_kind = 'booking' AND entity = 'bookings';

DELETE FROM public.bizapp_records
WHERE app_kind = 'booking' AND entity = 'bookings';
