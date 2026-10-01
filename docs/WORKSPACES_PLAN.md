# v0.2 plan: workspaces, optional email, three roles

**Status: plan only.** Baseline is the first public commit (`a60f266`). This document
supersedes parts of the first-commit contract; those deltas are listed explicitly in
§1 so no gate from v0.1 is silently dropped. Nothing here is shipped until §7 evidence
passes against the final tree.

## 1. Contract deltas vs the first commit

| v0.1 fixed choice | v0.2 decision | Why it changed |
| --- | --- | --- |
| Operator-only, one-time tenant bootstrap **CLI**; no second tenant by policy | One-time **web setup** creates org + sys-admin + default workspace; CLI removed | Self-hosters should not need a shell to claim their install |
| Login requires email LINK 2FA challenge | Login is email + password; email is never required to authenticate | Email is optional (§5); a fresh install with no mail must be usable |
| Invitations always available, delivered by mandatory SMTP | Invitations exist **only while an org email channel is enabled** | Delivery guarantees are the whole point of an invitation; no silent token-in-UI fallback |
| Public self-registration removed at the API | Org-level toggle `allow_self_registration`; when on, account creation is open (Slack-style join) | Small orgs want it; closed deployments turn it off |
| Roles `TENANT_ADMIN`, `USER` | Roles `ORG_OWNER` (UI: sys-admin), `WORKSPACE_ADMIN`, `MEMBER`; wire rename happens now | Membership rebuild (§2) makes the rename nearly free; later it is a production migration |
| One tenant per user account | Org-level identity; workspace membership is a join row; one user, many workspaces | Departments (§2) |
| `tenant_id` columns | **Kept**, renamed in UI copy to "workspace"; internal invariant unchanged | Qdrant payload filters, Kuzu scoping, gRPC contract, ingestion event schemas already carry it; corp lineage shares the engine |

Unchanged and still binding: fresh-install PostgreSQL baseline, local-Ollama default with
no hosted fallback, `EMPLOYEE_PLAYGROUND` channel name, completed-JSON answers, citation
authorization owned by Spring, no billing/platform/widget/recruitment/mobile surfaces.

## 2. Data model

```
organizations        (id, name, slug, allow_self_registration default false, created_at)
users                (id, org_id, email UNIQUE(org_id,email), password_hash, full_name,
                      role: ORG_OWNER|MEMBER, status, must_change_password bool)
workspaces           (id, org_id, tenant_id kept as column name = workspace identity,
                      name, slug, visibility PUBLIC|PRIVATE, is_default bool)
workspace_members    (user_id, workspace_id, role: WORKSPACE_ADMIN|MEMBER,
                      UNIQUE(user_id, workspace_id))
```

- All existing scoped tables (`documents`, `chat_sessions`, `knowledge_bases`, `chatbots`,
  …) keep their `tenant_id` column; its value is now a `workspaces.id`. No data move.
- `login_2fa_state` table and its endpoints are **dropped** from the baseline (§4).
- `invitations` gains `workspace_id`, `role`, `invited_by`; keeps token/expiry/single-use.
- `notification_channels` (org-level): `type` (`none|smtp|sendgrid|brevo|…`), encrypted
  credential blob, enabled flag, last delivery status. Env config becomes bootstrap
  default that seeds/overrides only when no DB row exists.
- Baseline strategy: **rebuild `V1`** (no install has ever been published; no remote is
  connected). If the v0.1 commit is ever pushed before v0.2 ships, this flips to
  forward migrations and §7 gains a migration gate.

## 3. Auth and active workspace

- Login → JWT claims: `orgId`, `activeWorkspaceId`, `roleInWorkspace` (+ `ORG_OWNER` flag).
- `POST /auth/workspaces/switch` reissues the token after validating membership
  (org owners may target any workspace in their org).
- Every scoped controller resolves workspace from the **token**, re-validates membership
  per request (defense in depth; a stale token for a revoked membership must 403).
- Refresh rotation, revocation, logout, cookie flags: unchanged from v0.1.
- Frontend: workspace switcher in `AppShell`; `/setup` page (locale-aware, en+vi);
  login page drops the verify-email hop; `verify-login`/`check-login-email` routes deleted.

## 4. Roles and permissions

| Capability | ORG_OWNER | WORKSPACE_ADMIN (of ws) | MEMBER |
| --- | --- | --- | --- |
| Complete one-time web setup | first one only | — | — |
| Create/rename/archive workspaces | ✅ | — | — |
| Org settings (self-registration toggle, email channels) | ✅ | — | — |
| Appoint/dismiss workspace admins | ✅ (any ws) | — | — |
| Invite members to ws (requires enabled channel) | ✅ (any ws) | own ws | — |
| Approve/see pending joiners | ✅ | own ws | — |
| Delete documents | — | any in own ws | own uploads only |
| Upload / chat / view / citations | as member of ws | own ws | own ws |
| Reset a member's password | ✅ | own ws | — |

- Guards: last active `ORG_OWNER` cannot be demoted/deactivated (promotable owners —
  bus-factor rule); last active `WORKSPACE_ADMIN` per workspace likewise (extend the
  existing `ensureNotFinalActiveAdmin` pattern per workspace).
- Sys-admin governs **above** workspaces but may hold ordinary memberships inside them.
- Owner lockout recovery without email: documented offline procedure (single SQL or a
  `--app.command.mode=reset-owner` headless command that requires local DB access).
  Decision: ship the headless command; SQL snippets in docs are not a feature.
- Admin-created account with no email: admin sets an initial password shown once,
  `must_change_password=true`, forced change on first login.

## 5. Email as optional plugin (in-repo SPI — no runtime jar loading)

- SPI: `NotificationChannel { type(); availability(); send(EmailMessage) }` — evolves
  `EmailProvider`; implementations remain conditional Spring beans (`@ConditionalOnProperty`
  + DB-row-driven enablement). Enterprise/private channels are compile-time beans in the
  hosted repo against the same interface.
- `none` is the default. `MailConfigurationValidator` no longer fails startup; instead:
  actuator `mail` component + `/settings` report "no channel configured", invitations and
  email-dependent actions return `409 NO_NOTIFICATION_CHANNEL`.
- Outbox: new terminal-ish status `NO_CHANNEL` (not retried, not DEAD, does not degrade
  readiness); switching a channel on offers a one-shot replay of `NO_CHANNEL`
  invitation/notice events (idempotent via existing inbox).
- 2FA email path deleted entirely (§1); `sendLogin2FAEmail`, its listener, and template go.
- Credentials at rest: encrypted with a server-side key (`TOKEN_KEY`-style env, separate
  `NOTIFICATION_ENC_KEY`); never returned by any API, write-only after creation.

## 6. Setup and registration flows

- `GET /setup/status` (public): `{required: bool}` — true only while `users.count()==0`.
- `POST /setup` (public until first success, then permanently 404): org name, admin
  name/email/password. Single transaction with a PostgreSQL advisory lock (or singleton
  guard row) so concurrent submissions cannot create two orgs. Password rules move into
  the service layer (≥12, reject known dev seed password) and apply to setup,
  registration, invitation acceptance, and admin-set passwords alike.
- Self-registration (`allow_self_registration=true`): creates `MEMBER` accounts,
  auto-membership rows for the default workspace + all `PUBLIC` workspaces;
  `PRIVATE` workspaces reachable only by invitation/admin. New users land `ACTIVE`
  (Slack model — no approval queue in v0.2; the queue is a candidate follow-up).
- Takeover window: compose example binds published ports to `127.0.0.1` by default;
  README says explicitly that whoever reaches the port first owns the install until setup
  completes, and setup completion is irreversible.

## 7. Execution waves and exit evidence

Mirror the v0.1 discipline: dependency order, callers/tests/docs updated in-wave, no
flag-only removals.

1. **W1 — schema + auth core**: baseline rebuild (drop `login_2fa_state`, add org/
   workspace/membership/channel tables), JWT claims, switch endpoint, membership
   revalidation interceptor. *Exit:* Testcontainers baseline test green; login/refresh/
   switch tests; forged-workspace-claim rejected; setup race test (two concurrent POSTs →
   exactly one org).
2. **W2 — control plane**: controllers/services re-keyed to active workspace; document
   delete rules (own vs any); invitations gated on channel availability; admin-set
   password + forced change; reset-owner headless command. *Exit:* A/B workspace
   isolation tests at API level incl. cross-workspace document/session/citation IDs from
   a member of only one; 409 paths when channel off.
3. **W3 — email plugin**: SPI rename, `none` default, DB channel config CRUD (owner-only),
   `NO_CHANNEL` outbox semantics, replay-on-enable, validator demotion to health. *Exit:*
   boot with zero mail config succeeds; readiness stays UP; invitation blocked cleanly;
   enable channel → replay delivers once (Testcontainers + Mailpit).
4. **W4 — web**: `/setup`, login simplification, workspace switcher + `/workspaces`
   admin, `/settings` (org: registration toggle, channels; workspace: visibility, admins),
   users page per-workspace, i18n en+vi, delete `verify-login`/`check-login-email`.
   *Exit:* lint/typecheck/i18n/test/build; Playwright: setup→login→switch→upload→cite
   in a real browser; removed routes 404.
5. **W5 — infra/docs/CI**: compose port binding, docs rewrite (ARCHITECTURE planes,
   DEVELOPMENT, DEPLOYMENT operator-bootstrap section replaced by web setup, OPEN_SOURCE
   boundary, README roles table), CI matrix unchanged plus new baseline/e2e jobs.
   *Exit:* fresh-machine README walkthrough to a two-workspace install with a member in
   both, no email configured, then channel-on invitation.

**Acceptance run** (same stop rule as v0.1): static/schema suites; live stack smoke from
empty volumes — setup race, self-registration on/off, workspace create/switch/archive,
invite via Mailpit, upload→index→cite→delete incl. member-deletes-own vs
member-cannot-delete-others, cross-workspace denial with B's token, no-mail boot with
readiness UP and `NO_CHANNEL` visibility, replay-on-enable; security/egress unchanged
(local model only, no hosted fallback); alignment evaluator unchanged. Any unexercised
scenario blocks the v0.2 tag, not a workaround.

## 8. Explicitly out of scope

Runtime plugin jar loading (extension API maintenance burden); per-workspace email
config (channels are org-level; per-department senders are a later need if it appears);
approval queue for joiners; cross-org hosting (platform layer stays private); billing,
quotas, mobile, widget, recruitment (v0.1 exclusions still apply).
