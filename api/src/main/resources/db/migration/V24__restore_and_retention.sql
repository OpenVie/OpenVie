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

-- V24: restorable archive + retention metadata.
--
-- Archive was a one-way status flip: the data stayed in PostgreSQL, but there
-- was no product path back. These columns make the archived state auditable
-- (who/when) and let a retention sweep find candidates. `legal_hold` is an
-- operator switch that always wins over retention.

ALTER TABLE public.tenants ADD COLUMN archived_at timestamp without time zone;
ALTER TABLE public.tenants ADD COLUMN archived_by uuid;
ALTER TABLE public.tenants ADD COLUMN legal_hold boolean DEFAULT false NOT NULL;

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_archived_by_fkey FOREIGN KEY (archived_by) REFERENCES public.users(id);

CREATE INDEX idx_tenants_archived
    ON public.tenants USING btree (status, archived_at)
    WHERE ((status)::text = 'ARCHIVED'::text);

ALTER TABLE public.bizapp_records ADD COLUMN archived_at timestamp without time zone;
ALTER TABLE public.bizapp_records ADD COLUMN archived_by uuid;

ALTER TABLE ONLY public.bizapp_records
    ADD CONSTRAINT bizapp_records_archived_by_fkey FOREIGN KEY (archived_by) REFERENCES public.users(id);

CREATE INDEX idx_bizapp_records_archived
    ON public.bizapp_records USING btree (status, archived_at)
    WHERE ((status)::text = 'ARCHIVED'::text);
