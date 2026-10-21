-- V8: approval decision write-back (workflow → document state).
--
-- The Flowable process stays the journal of who decided what and when; the
-- document row carries the authoritative current state so listings, caches,
-- and downstream consumers never have to re-derive it from engine history.
-- A NULL approval_status means the document was never submitted for approval;
-- PENDING means a process is open.

ALTER TABLE public.documents
    ADD COLUMN approval_status character varying(20),
    ADD COLUMN approval_target character varying(20),
    ADD COLUMN approval_decided_by uuid,
    ADD COLUMN approval_decided_at timestamp without time zone;

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_approval_status_check CHECK (approval_status IS NULL
        OR (approval_status)::text = ANY (ARRAY['PENDING'::text, 'APPROVED'::text, 'REJECTED'::text])),
    ADD CONSTRAINT documents_approval_target_check CHECK (approval_target IS NULL
        OR (approval_target)::text = ANY (ARRAY['workspace'::text, 'shared'::text, 'public'::text])),
    ADD CONSTRAINT documents_approval_decided_by_fkey FOREIGN KEY (approval_decided_by)
        REFERENCES public.users(id);

COMMENT ON COLUMN public.documents.approval_status IS
    'Approval state written back by the workflow module; NULL = never submitted';
COMMENT ON COLUMN public.documents.approval_target IS
    'Publish target chosen at submission: workspace, shared, or public';

CREATE INDEX idx_documents_tenant_approval_status
    ON public.documents USING btree (tenant_id, approval_status);

-- Workflow decision notices are recorded in the notifications journal, so the
-- pinned type list grows by one value.
ALTER TABLE public.notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE public.notifications ADD CONSTRAINT notifications_type_check
    CHECK ((type)::text = ANY (ARRAY['DOCUMENT_COMPLETED'::text, 'DOCUMENT_FAILED'::text,
        'USER_INVITED'::text, 'WORKFLOW_DECIDED'::text]));
