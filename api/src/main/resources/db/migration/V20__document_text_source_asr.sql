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

-- V20: Allow 'asr' in documents_text_source_check for speech transcription.

ALTER TABLE ONLY public.documents
    DROP CONSTRAINT IF EXISTS documents_text_source_check;

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_text_source_check CHECK (text_source IS NULL
        OR (text_source)::text = ANY (ARRAY['text'::text, 'ocr'::text, 'asr'::text, 'mixed'::text]));

COMMENT ON COLUMN public.documents.text_source IS
    'How the text was obtained: text (native layer), ocr, asr (speech transcription), or mixed; NULL = legacy';
