-- OpenVie fresh-install baseline (new public repository).
--
-- This file is the reviewed squash of the internal migration chain and is
-- intended ONLY for new installations. Private deployments with historical
-- data are not upgrade targets for this release; never point this migration
-- at an existing private database.
--
-- Retained planes: tenants/users/invitations, model configuration,
-- knowledge bases, documents and their status/outbox, chat sessions,
-- messages and cited turns, module event outbox/inbox, audit and
-- notification records. Recruitment, billing, widget, analytics, platform,
-- ticket/webhook/integration and public-evidence structures were removed
-- together with their code.

--
--



SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';


--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid,
    action character varying(100) NOT NULL,
    resource_type character varying(100),
    resource_id uuid,
    ip_address character varying(45),
    user_agent text,
    metadata jsonb,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: chat_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    session_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid,
    role character varying(50) NOT NULL,
    content text NOT NULL,
    citations jsonb DEFAULT '[]'::jsonb NOT NULL,
    sequence_number integer NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    action jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT chat_messages_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying, 'system'::character varying])::text[])))
);


--
-- Name: chat_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_sessions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid,
    chatbot_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    locale character varying(20) DEFAULT 'vi-VN'::character varying NOT NULL,
    status character varying(50) DEFAULT 'OPEN'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    closed_at timestamp without time zone,
    channel character varying(50) DEFAULT 'EMPLOYEE_PLAYGROUND'::character varying NOT NULL,
    customer_metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    last_activity_at timestamp without time zone DEFAULT now() NOT NULL,
    hidden_at timestamp without time zone,
    next_sequence_number integer DEFAULT 1 NOT NULL
);


--
-- Name: chat_turns; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_turns (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    session_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    generation_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    idempotency_key_hash character varying(64),
    request_fingerprint character varying(64) NOT NULL,
    user_message_id uuid NOT NULL,
    assistant_message_id uuid,
    knowledge_revision bigint NOT NULL,
    generation_context jsonb NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    failure_code character varying(100),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT chat_turn_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: chatbots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chatbots (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    model_config_version_id uuid NOT NULL,
    display_name character varying(255) NOT NULL,
    default_locale character varying(20) DEFAULT 'vi-VN'::character varying NOT NULL,
    welcome_message text NOT NULL,
    safe_instructions text NOT NULL,
    response_tone character varying(100) DEFAULT 'HELPFUL'::character varying NOT NULL,
    citation_policy character varying(100) DEFAULT 'REQUIRED_FOR_KNOWLEDGE'::character varying NOT NULL,
    general_knowledge_policy character varying(100) DEFAULT 'ALLOW_WITH_DISCLOSURE'::character varying NOT NULL,
    retrieval_settings jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(50) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.documents (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    uploaded_by uuid NOT NULL,
    file_name character varying(500) NOT NULL,
    file_type character varying(50) NOT NULL,
    file_size_bytes bigint NOT NULL,
    storage_path text NOT NULL,
    status character varying(50) DEFAULT 'PENDING'::character varying NOT NULL,
    job_id character varying(255),
    chunk_count integer,
    error_message text,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    knowledge_base_id uuid NOT NULL
);


--
-- Name: internal_event_inbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.internal_event_inbox (
    event_id uuid NOT NULL,
    event_type character varying(150) NOT NULL,
    aggregate_id uuid NOT NULL,
    payload jsonb NOT NULL,
    received_at timestamp without time zone DEFAULT now() NOT NULL,
    processed_at timestamp without time zone,
    processing_result character varying(100)
);


--
-- Name: internal_event_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.internal_event_outbox (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    event_id uuid NOT NULL,
    aggregate_type character varying(100) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type character varying(150) NOT NULL,
    payload jsonb NOT NULL,
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp without time zone DEFAULT now() NOT NULL,
    published_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: invitations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.invitations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    invited_by uuid NOT NULL,
    email character varying(255) NOT NULL,
    role character varying(50) DEFAULT 'USER'::character varying NOT NULL,
    token_hash character varying(255) NOT NULL,
    status character varying(50) DEFAULT 'PENDING'::character varying NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    last_sent_at timestamp without time zone NOT NULL,
    accepted_at timestamp without time zone
);


--
-- Name: knowledge_bases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledge_bases (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    slug character varying(100) NOT NULL,
    description text,
    default_locale character varying(20) DEFAULT 'vi-VN'::character varying NOT NULL,
    status character varying(50) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    search_revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_knowledge_base_search_revision_non_negative CHECK ((search_revision >= 0))
);


--
-- Name: login_2fa_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.login_2fa_state (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    email character varying(255) NOT NULL,
    token_hash character varying(255) NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    used boolean DEFAULT false NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    challenge_type character varying(16) DEFAULT 'LINK'::character varying NOT NULL,
    verification_attempt_count integer DEFAULT 0 NOT NULL,
    CONSTRAINT chk_login_2fa_challenge_type CHECK (((challenge_type)::text = ANY ((ARRAY['LINK'::character varying, 'CODE'::character varying])::text[])))
);


--
-- Name: model_config_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.model_config_versions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(100) NOT NULL,
    version_label character varying(100) NOT NULL,
    generation_model_id character varying(255) NOT NULL,
    generation_adapter_id character varying(255),
    generation_runtime character varying(100) DEFAULT 'vLLM'::character varying NOT NULL,
    generation_endpoint character varying(255) DEFAULT 'internal://model-gateway/generation'::character varying NOT NULL,
    text_embedding_model_id character varying(255) NOT NULL,
    text_embedding_dimension integer NOT NULL,
    text_embedding_runtime character varying(100) DEFAULT 'internal'::character varying NOT NULL,
    status character varying(50) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: module_event_inbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.module_event_inbox (
    consumer_name character varying(160) NOT NULL,
    event_id uuid NOT NULL,
    event_type character varying(160) NOT NULL,
    processed_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: module_event_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.module_event_outbox (
    event_id uuid NOT NULL,
    event_type character varying(160) NOT NULL,
    event_version integer NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_at timestamp without time zone,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_error text,
    CONSTRAINT chk_module_event_outbox_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PUBLISHED'::character varying, 'DEAD'::character varying])::text[])))
);


--
-- Name: notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notifications (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid,
    type character varying(100) NOT NULL,
    title character varying(255) NOT NULL,
    message text NOT NULL,
    status character varying(50) DEFAULT 'PENDING'::character varying NOT NULL,
    sent_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT notifications_type_check CHECK (((type)::text = ANY (ARRAY[('WELCOME_EMAIL'::character varying)::text, ('LOGIN_2FA_EMAIL'::character varying)::text, ('DOCUMENT_COMPLETED'::character varying)::text, ('DOCUMENT_FAILED'::character varying)::text, ('USER_INVITED'::character varying)::text])))
);


--
-- Name: refresh_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_tokens (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    token_hash character varying(255) NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    revoked boolean DEFAULT false NOT NULL,
    persistent boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: tenants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenants (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(255) NOT NULL,
    slug character varying(100) NOT NULL,
    status character varying(50) DEFAULT 'PENDING'::character varying NOT NULL,
    suspended_at timestamp without time zone,
    suspended_reason character varying(255),
    max_documents integer DEFAULT 150,
    max_messages integer DEFAULT 5000,
    max_storage_mb integer DEFAULT 5120,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    max_team_members integer
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    full_name character varying(255),
    role character varying(50) DEFAULT 'USER'::character varying NOT NULL,
    status character varying(50) DEFAULT 'ACTIVE'::character varying NOT NULL,
    invited_by uuid,
    last_login_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: chat_messages chat_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_pkey PRIMARY KEY (id);


--
-- Name: chat_sessions chat_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_sessions
    ADD CONSTRAINT chat_sessions_pkey PRIMARY KEY (id);


--
-- Name: chat_turns chat_turns_generation_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_turns
    ADD CONSTRAINT chat_turns_generation_id_key UNIQUE (generation_id);


--
-- Name: chat_turns chat_turns_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_turns
    ADD CONSTRAINT chat_turns_pkey PRIMARY KEY (id);


--
-- Name: chatbots chatbots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chatbots
    ADD CONSTRAINT chatbots_pkey PRIMARY KEY (id);


--
-- Name: documents documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_pkey PRIMARY KEY (id);


--
-- Name: internal_event_inbox internal_event_inbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.internal_event_inbox
    ADD CONSTRAINT internal_event_inbox_pkey PRIMARY KEY (event_id);


--
-- Name: internal_event_outbox internal_event_outbox_event_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.internal_event_outbox
    ADD CONSTRAINT internal_event_outbox_event_id_key UNIQUE (event_id);


--
-- Name: internal_event_outbox internal_event_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.internal_event_outbox
    ADD CONSTRAINT internal_event_outbox_pkey PRIMARY KEY (id);


--
-- Name: invitations invitations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invitations
    ADD CONSTRAINT invitations_pkey PRIMARY KEY (id);


--
-- Name: invitations invitations_token_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invitations
    ADD CONSTRAINT invitations_token_key UNIQUE (token_hash);


--
-- Name: knowledge_bases knowledge_bases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_bases
    ADD CONSTRAINT knowledge_bases_pkey PRIMARY KEY (id);


--
-- Name: login_2fa_state login_2fa_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.login_2fa_state
    ADD CONSTRAINT login_2fa_state_pkey PRIMARY KEY (id);


--
-- Name: model_config_versions model_config_versions_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_config_versions
    ADD CONSTRAINT model_config_versions_name_key UNIQUE (name);


--
-- Name: model_config_versions model_config_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_config_versions
    ADD CONSTRAINT model_config_versions_pkey PRIMARY KEY (id);


--
-- Name: module_event_inbox module_event_inbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.module_event_inbox
    ADD CONSTRAINT module_event_inbox_pkey PRIMARY KEY (consumer_name, event_id);


--
-- Name: module_event_outbox module_event_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.module_event_outbox
    ADD CONSTRAINT module_event_outbox_pkey PRIMARY KEY (event_id);


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);


--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);


--
-- Name: refresh_tokens refresh_tokens_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_token_hash_key UNIQUE (token_hash);


--
-- Name: tenants tenants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);


--
-- Name: tenants tenants_slug_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_slug_key UNIQUE (slug);


--
-- Name: chat_messages uq_chat_message_session_sequence; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT uq_chat_message_session_sequence UNIQUE (session_id, sequence_number);


--
-- Name: knowledge_bases uq_knowledge_base_tenant_slug; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_bases
    ADD CONSTRAINT uq_knowledge_base_tenant_slug UNIQUE (tenant_id, slug);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: idx_audit_log_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_log_created_at ON public.audit_logs USING btree (created_at);


--
-- Name: idx_audit_log_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_log_tenant_id ON public.audit_logs USING btree (tenant_id);


--
-- Name: idx_audit_log_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_log_user_id ON public.audit_logs USING btree (user_id);


--
-- Name: idx_chat_message_session_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_message_session_id ON public.chat_messages USING btree (session_id);


--
-- Name: idx_chat_message_session_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_message_session_sequence ON public.chat_messages USING btree (session_id, sequence_number);


--
-- Name: idx_chat_message_tenant_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_message_tenant_created ON public.chat_messages USING btree (tenant_id, created_at);


--
-- Name: idx_chat_message_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_message_tenant_id ON public.chat_messages USING btree (tenant_id);


--
-- Name: idx_chat_messages_content_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_messages_content_trgm ON public.chat_messages USING gin (lower(content) public.gin_trgm_ops);


--
-- Name: idx_chat_messages_session_role_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_messages_session_role_sequence ON public.chat_messages USING btree (session_id, role, sequence_number);


--
-- Name: idx_chat_session_chatbot_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_session_chatbot_id ON public.chat_sessions USING btree (chatbot_id);


--
-- Name: idx_chat_session_knowledge_base_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_session_knowledge_base_id ON public.chat_sessions USING btree (knowledge_base_id);


--
-- Name: idx_chat_session_last_activity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_session_last_activity ON public.chat_sessions USING btree (status, channel, last_activity_at);


--
-- Name: idx_chat_session_playground_history; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_session_playground_history ON public.chat_sessions USING btree (tenant_id, user_id, channel, hidden_at, last_activity_at DESC);


--
-- Name: idx_chat_session_tenant_channel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_session_tenant_channel ON public.chat_sessions USING btree (tenant_id, channel);


--
-- Name: idx_chat_session_tenant_channel_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_session_tenant_channel_created ON public.chat_sessions USING btree (tenant_id, channel, created_at);


--
-- Name: idx_chat_session_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_session_tenant_id ON public.chat_sessions USING btree (tenant_id);


--
-- Name: idx_chat_session_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_session_tenant_status ON public.chat_sessions USING btree (tenant_id, status);


--
-- Name: idx_chat_session_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_session_user_id ON public.chat_sessions USING btree (user_id);


--
-- Name: idx_chat_sessions_conversation_activity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_sessions_conversation_activity ON public.chat_sessions USING btree (tenant_id, channel, last_activity_at DESC, id DESC);


--
-- Name: idx_chat_sessions_playground_activity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_sessions_playground_activity ON public.chat_sessions USING btree (tenant_id, user_id, channel, last_activity_at DESC, id DESC) WHERE (hidden_at IS NULL);


--
-- Name: idx_chat_turn_session_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_turn_session_status ON public.chat_turns USING btree (session_id, status);


--
-- Name: idx_chat_turn_tenant_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_turn_tenant_created ON public.chat_turns USING btree (tenant_id, created_at DESC);


--
-- Name: idx_chatbot_knowledge_base_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chatbot_knowledge_base_id ON public.chatbots USING btree (knowledge_base_id);


--
-- Name: idx_chatbot_model_config_version_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chatbot_model_config_version_id ON public.chatbots USING btree (model_config_version_id);


--
-- Name: idx_chatbot_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chatbot_status ON public.chatbots USING btree (status);


--
-- Name: idx_chatbot_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chatbot_tenant_id ON public.chatbots USING btree (tenant_id);


--
-- Name: idx_document_failure_tenant_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_failure_tenant_updated ON public.documents USING btree (tenant_id, status, updated_at, id);


--
-- Name: idx_document_job_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_job_id ON public.documents USING btree (job_id);


--
-- Name: idx_document_knowledge_base_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_knowledge_base_id ON public.documents USING btree (knowledge_base_id);


--
-- Name: idx_document_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_status ON public.documents USING btree (status);


--
-- Name: idx_document_tenant_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_tenant_created ON public.documents USING btree (tenant_id, created_at DESC);


--
-- Name: idx_document_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_tenant_id ON public.documents USING btree (tenant_id);


--
-- Name: idx_document_tenant_knowledge_base; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_tenant_knowledge_base ON public.documents USING btree (tenant_id, knowledge_base_id);


--
-- Name: idx_document_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_tenant_status ON public.documents USING btree (tenant_id, status);


--
-- Name: idx_documents_file_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_documents_file_name_trgm ON public.documents USING gin (lower((file_name)::text) public.gin_trgm_ops);


--
-- Name: idx_documents_list_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_documents_list_order ON public.documents USING btree (tenant_id, knowledge_base_id, created_at DESC, id DESC);


--
-- Name: idx_internal_event_outbox_status_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_internal_event_outbox_status_updated ON public.internal_event_outbox USING btree (status, next_attempt_at, updated_at, id);


--
-- Name: idx_internal_inbox_unprocessed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_internal_inbox_unprocessed ON public.internal_event_inbox USING btree (processed_at, received_at);


--
-- Name: idx_internal_outbox_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_internal_outbox_due ON public.internal_event_outbox USING btree (status, next_attempt_at, created_at);


--
-- Name: idx_invitations_tenant_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_invitations_tenant_email ON public.invitations USING btree (tenant_id, lower((email)::text));


--
-- Name: idx_invitations_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_invitations_tenant_id ON public.invitations USING btree (tenant_id);


--
-- Name: idx_invitations_token_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_invitations_token_hash ON public.invitations USING btree (token_hash);


--
-- Name: idx_knowledge_base_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledge_base_status ON public.knowledge_bases USING btree (status);


--
-- Name: idx_knowledge_base_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledge_base_tenant_id ON public.knowledge_bases USING btree (tenant_id);


--
-- Name: idx_login_2fa_expires_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_login_2fa_expires_at ON public.login_2fa_state USING btree (expires_at);


--
-- Name: idx_login_2fa_state_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_login_2fa_state_email ON public.login_2fa_state USING btree (email);


--
-- Name: idx_login_2fa_token_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_login_2fa_token_hash ON public.login_2fa_state USING btree (token_hash);


--
-- Name: idx_login_2fa_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_login_2fa_user_id ON public.login_2fa_state USING btree (user_id);


--
-- Name: idx_model_config_version_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_model_config_version_status ON public.model_config_versions USING btree (status);


--
-- Name: idx_module_event_outbox_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_module_event_outbox_due ON public.module_event_outbox USING btree (status, next_attempt_at, created_at);


--
-- Name: idx_module_event_outbox_status_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_module_event_outbox_status_updated ON public.module_event_outbox USING btree (status, next_attempt_at, created_at, event_id);


--
-- Name: idx_notification_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_tenant_id ON public.notifications USING btree (tenant_id);


--
-- Name: idx_refresh_tokens_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_tokens_user_id ON public.refresh_tokens USING btree (user_id);


--
-- Name: idx_users_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_email ON public.users USING btree (email);


--
-- Name: idx_users_tenant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_tenant_id ON public.users USING btree (tenant_id);


--
-- Name: uq_chat_turn_session_idempotency; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_chat_turn_session_idempotency ON public.chat_turns USING btree (session_id, idempotency_key_hash) WHERE (idempotency_key_hash IS NOT NULL);


--
-- Name: uq_invitations_pending_tenant_email; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_invitations_pending_tenant_email ON public.invitations USING btree (tenant_id, lower((email)::text)) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: audit_logs audit_logs_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: audit_logs audit_logs_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: chat_messages chat_messages_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_session_id_fkey FOREIGN KEY (session_id) REFERENCES public.chat_sessions(id) ON DELETE CASCADE;


--
-- Name: chat_messages chat_messages_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: chat_messages chat_messages_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE SET NULL;


--
-- Name: chat_sessions chat_sessions_chatbot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_sessions
    ADD CONSTRAINT chat_sessions_chatbot_id_fkey FOREIGN KEY (chatbot_id) REFERENCES public.chatbots(id) ON DELETE RESTRICT;


--
-- Name: chat_sessions chat_sessions_knowledge_base_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_sessions
    ADD CONSTRAINT chat_sessions_knowledge_base_id_fkey FOREIGN KEY (knowledge_base_id) REFERENCES public.knowledge_bases(id) ON DELETE RESTRICT;


--
-- Name: chat_sessions chat_sessions_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_sessions
    ADD CONSTRAINT chat_sessions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: chat_sessions chat_sessions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_sessions
    ADD CONSTRAINT chat_sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: chat_turns chat_turns_assistant_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_turns
    ADD CONSTRAINT chat_turns_assistant_message_id_fkey FOREIGN KEY (assistant_message_id) REFERENCES public.chat_messages(id) ON DELETE RESTRICT;


--
-- Name: chat_turns chat_turns_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_turns
    ADD CONSTRAINT chat_turns_session_id_fkey FOREIGN KEY (session_id) REFERENCES public.chat_sessions(id) ON DELETE CASCADE;


--
-- Name: chat_turns chat_turns_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_turns
    ADD CONSTRAINT chat_turns_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: chat_turns chat_turns_user_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_turns
    ADD CONSTRAINT chat_turns_user_message_id_fkey FOREIGN KEY (user_message_id) REFERENCES public.chat_messages(id) ON DELETE RESTRICT;


--
-- Name: chatbots chatbots_knowledge_base_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chatbots
    ADD CONSTRAINT chatbots_knowledge_base_id_fkey FOREIGN KEY (knowledge_base_id) REFERENCES public.knowledge_bases(id) ON DELETE RESTRICT;


--
-- Name: chatbots chatbots_model_config_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chatbots
    ADD CONSTRAINT chatbots_model_config_version_id_fkey FOREIGN KEY (model_config_version_id) REFERENCES public.model_config_versions(id) ON DELETE RESTRICT;


--
-- Name: chatbots chatbots_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chatbots
    ADD CONSTRAINT chatbots_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: documents documents_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: documents documents_uploaded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_uploaded_by_fkey FOREIGN KEY (uploaded_by) REFERENCES public.users(id);


--
-- Name: documents fk_documents_knowledge_base; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT fk_documents_knowledge_base FOREIGN KEY (knowledge_base_id) REFERENCES public.knowledge_bases(id) ON DELETE RESTRICT;


--
-- Name: invitations invitations_invited_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invitations
    ADD CONSTRAINT invitations_invited_by_fkey FOREIGN KEY (invited_by) REFERENCES public.users(id);


--
-- Name: invitations invitations_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invitations
    ADD CONSTRAINT invitations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: knowledge_bases knowledge_bases_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_bases
    ADD CONSTRAINT knowledge_bases_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: login_2fa_state login_2fa_state_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.login_2fa_state
    ADD CONSTRAINT login_2fa_state_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: notifications notifications_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: notifications notifications_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: refresh_tokens refresh_tokens_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
-- Name: refresh_tokens refresh_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: users users_invited_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_invited_by_fkey FOREIGN KEY (invited_by) REFERENCES public.users(id);


--
-- Name: users users_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;


--
--


