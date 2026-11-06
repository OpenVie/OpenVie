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

-- Add read_at and link columns to notifications for in-app bell & tracking
ALTER TABLE public.notifications
    ADD COLUMN IF NOT EXISTS read_at timestamp without time zone,
    ADD COLUMN IF NOT EXISTS link character varying(500);

-- Extend type check constraint to include BOOKING_REMINDER
ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_type_check;
ALTER TABLE public.notifications ADD CONSTRAINT notifications_type_check
    CHECK ((type)::text = ANY (ARRAY[
        'DOCUMENT_COMPLETED'::text,
        'DOCUMENT_FAILED'::text,
        'USER_INVITED'::text,
        'WORKFLOW_DECIDED'::text,
        'BOOKING_REMINDER'::text
    ]));

CREATE INDEX IF NOT EXISTS idx_notification_user_unread
    ON public.notifications (user_id, read_at)
    WHERE read_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_notification_tenant_user
    ON public.notifications (tenant_id, user_id, created_at DESC);

-- Update bundled booking template manifest in existing installations to include record.customerEmail in notifyCustomer block
UPDATE public.bizapp_installations
SET manifest = jsonb_set(
    manifest,
    '{blocks}',
    (
        SELECT jsonb_agg(
            CASE
                WHEN elem ->> 'id' = 'notifyCustomer' THEN
                    jsonb_set(elem, '{params,to}', '["creator", "record.customerEmail"]'::jsonb)
                ELSE elem
            END
        )
        FROM jsonb_array_elements(manifest -> 'blocks') elem
    )
)
WHERE kind = 'booking' AND manifest -> 'blocks' IS NOT NULL;
