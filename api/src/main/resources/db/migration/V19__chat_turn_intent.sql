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

-- Chat turn intent classification columns for app awareness & action routing (W1.3)
ALTER TABLE public.chat_turns
    ADD COLUMN IF NOT EXISTS intent character varying(32) NULL,
    ADD COLUMN IF NOT EXISTS intent_confidence numeric(4,3) NULL,
    ADD COLUMN IF NOT EXISTS intent_app_kind character varying(64) NULL;

CREATE INDEX IF NOT EXISTS idx_chat_turns_tenant_intent
    ON public.chat_turns (tenant_id, intent);

COMMENT ON COLUMN public.chat_turns.intent IS
    'Classified turn intent: NONE, LIST_APPS, APP_DETAIL, APP_DATA, INSTALL, UNINSTALL, SUGGEST, CREATE_APP.';
COMMENT ON COLUMN public.chat_turns.intent_confidence IS
    'Confidence score of classified intent between 0.000 and 1.000.';
COMMENT ON COLUMN public.chat_turns.intent_app_kind IS
    'Target app kind if identified (e.g. crm, booking, signature-request).';
