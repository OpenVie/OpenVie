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

-- Document provenance & safe retrieval: distinguish system documents from user documents.
ALTER TABLE public.documents
    ADD COLUMN IF NOT EXISTS document_kind character varying(24) DEFAULT 'USER' NOT NULL,
    ADD COLUMN IF NOT EXISTS source_ref character varying(128) NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_documents_document_kind'
    ) THEN
        ALTER TABLE public.documents
            ADD CONSTRAINT ck_documents_document_kind
            CHECK (document_kind IN ('USER', 'BIZAPP_RECORD', 'BIZAPP_DIGEST'));
    END IF;
END $$;

UPDATE public.documents
SET document_kind = 'USER'
WHERE document_kind IS NULL;

CREATE INDEX IF NOT EXISTS idx_documents_tenant_kb_kind
    ON public.documents (tenant_id, knowledge_base_id, document_kind);

CREATE INDEX IF NOT EXISTS idx_documents_tenant_source_ref
    ON public.documents (tenant_id, source_ref);

COMMENT ON COLUMN public.documents.document_kind IS
    'Provenance kind: USER for human uploads, BIZAPP_RECORD for projected app records, BIZAPP_DIGEST for aggregated app report digests.';
COMMENT ON COLUMN public.documents.source_ref IS
    'Reference locator for system-generated documents, e.g. bizapp:crm.';
