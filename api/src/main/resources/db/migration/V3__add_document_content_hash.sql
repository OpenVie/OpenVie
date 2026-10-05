ALTER TABLE public.documents
    ADD COLUMN content_hash character varying(64);

CREATE INDEX idx_documents_tenant_kb_filename
    ON public.documents (tenant_id, knowledge_base_id, file_name);

CREATE INDEX idx_documents_tenant_kb_content_hash
    ON public.documents (tenant_id, knowledge_base_id, content_hash);
