-- V20: Allow 'asr' in documents_text_source_check for speech transcription.

ALTER TABLE ONLY public.documents
    DROP CONSTRAINT IF EXISTS documents_text_source_check;

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_text_source_check CHECK (text_source IS NULL
        OR (text_source)::text = ANY (ARRAY['text'::text, 'ocr'::text, 'asr'::text, 'mixed'::text]));

COMMENT ON COLUMN public.documents.text_source IS
    'How the text was obtained: text (native layer), ocr, asr (speech transcription), or mixed; NULL = legacy';
