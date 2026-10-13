-- Issue #4: link pre-SSO password accounts to Keycloak identities.
--
-- users.keycloak_subject stores the Keycloak `sub` for an account that was
-- linked by verified email. NULL means password-only (never logged in via
-- SSO). The partial unique index keeps subjects immutable-once-set and
-- prevents two accounts sharing one SSO identity.
--
-- organizations.sso_link_require_otp is the future TOTP-confirmation switch
-- for first-time linking (Keycloak-native TOTP, no SMTP code involved).
-- It defaults to FALSE: auto-link by verified email is the active path until
-- the organization turns confirmation on. See docs/KEYCLOAK.md.
--
-- Idempotent on fresh (V1..V3 applied) and upgraded databases: plain ADD
-- COLUMN / CREATE INDEX IF NOT EXISTS, no data backfill.

ALTER TABLE public.users
    ADD COLUMN keycloak_subject character varying(255);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_keycloak_subject
    ON public.users (keycloak_subject)
    WHERE keycloak_subject IS NOT NULL;

ALTER TABLE public.organizations
    ADD COLUMN sso_link_require_otp boolean DEFAULT false NOT NULL;
