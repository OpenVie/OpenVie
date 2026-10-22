-- V9: OCR ingestion metadata (text_source, page_count).
--
-- The Python ingestion worker recognizes scanned-PDF pages and standalone
-- images when INGESTION_OCR_ENABLED is on; the COMPLETED status event carries
-- how the text was obtained and how many source pages were read.
-- A NULL text_source means the row predates OCR (or the document was parsed
-- purely by the disabled-default text pipeline); 'text', 'ocr', or 'mixed'.

ALTER TABLE public.documents
    ADD COLUMN text_source character varying(16),
    ADD COLUMN page_count integer;

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_text_source_check CHECK (text_source IS NULL
        OR (text_source)::text = ANY (ARRAY['text'::text, 'ocr'::text, 'mixed'::text]));

COMMENT ON COLUMN public.documents.text_source IS
    'How the text was obtained: text (native layer), ocr, or mixed; NULL = legacy';
COMMENT ON COLUMN public.documents.page_count IS
    'Number of source pages when known (PDF and images); NULL otherwise';
