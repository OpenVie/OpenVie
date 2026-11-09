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

ALTER TABLE public.documents
    ADD COLUMN content_hash character varying(64);

CREATE INDEX idx_documents_tenant_kb_filename
    ON public.documents (tenant_id, knowledge_base_id, file_name);

CREATE INDEX idx_documents_tenant_kb_content_hash
    ON public.documents (tenant_id, knowledge_base_id, content_hash);
