// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

export interface Document {
  id: string;
  fileName: string;
  fileType: string;
  status: DocumentStatus;
  fileSizeBytes: number;
  jobId: string;
  knowledgeBaseId: string;
  chunkCount?: number | null;
  errorMessage?: string | null;
  uploadedAt: string;
  uploadedBy?: string | null;
  approvalStatus?: "PENDING" | "APPROVED" | "REJECTED" | null;
  approvalTarget?: "workspace" | "shared" | "public" | null;
  /** How the text was obtained: text (native layer), ocr, or mixed. */
  textSource?: string | null;
  /** Number of source pages when known (PDF and images). */
  pageCount?: number | null;
}

export type DocumentStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";

export type ApprovalDecision = "APPROVE" | "REJECT" | "ESCALATE";
export type PublishTarget = "workspace" | "shared" | "public";

export interface WorkflowTask {
  taskId: string;
  taskName: string;
  taskDefinitionKey: string;
  approvalLevel: number | null;
  candidateGroup: string | null;
  assignee: string | null;
  processInstanceId: string;
  documentId: string;
  documentFileName: string;
  createdAt: string;
}

export interface WorkspaceTasks {
  assignedToMe: WorkflowTask[];
  groupTasks: WorkflowTask[];
}

export interface WorkflowProcess {
  processInstanceId: string;
  documentId: string;
  documentFileName: string;
  sensitive: boolean;
  approvalLevel: number;
  policyVersion: string;
  publishTarget: PublishTarget;
  documentStatus: string;
  escalated: boolean;
  startedAt: string;
  endedAt: string | null;
}

export interface StartProcessResponse {
  processInstanceId: string;
  documentId: string;
  documentFileName: string;
  publishTarget: PublishTarget;
  sensitive: boolean;
  approvalLevel: number;
  policyVersion: string;
  slaReminder: string;
  slaEscalation: string;
  taskId: string;
  /** True when the submitting authority already decided the document. */
  autoApproved: boolean;
  /** APPROVED when autoApproved, else null while in review. */
  documentStatus: string | null;
}

export interface CompleteTaskResponse {
  taskId: string;
  decision: ApprovalDecision;
  processInstanceId: string;
  documentId: string;
  processEnded: boolean;
  documentStatus: string | null;
  nextTaskId: string | null;
}

/** One audited event of an approval process (`GET /workflow/processes/:id/log`). */
export interface WorkflowLogEntry {
  occurredAt: string | null;
  actorId: string | null;
  actorName: string | null;
  action: string;
  metadata: Record<string, unknown>;
}

export interface ProcessLog {
  process: WorkflowProcess;
  entries: WorkflowLogEntry[];
}

export interface DocumentUploadResponse {
  id: string;
  jobId: string;
  fileName: string;
  status: DocumentStatus;
}

export interface DocumentStatusResponse {
  id: string;
  jobId: string;
  fileName: string;
  fileType: string;
  fileSizeBytes: number;
  uploadedAt: string;
  uploadedBy?: string | null;
  knowledgeBaseId: string;
  status: DocumentStatus;
  approvalStatus?: "PENDING" | "APPROVED" | "REJECTED" | null;
  approvalTarget?: "workspace" | "shared" | "public" | null;
  chunkCount?: number | null;
  textSource?: string | null;
  pageCount?: number | null;
  errorMessage?: string | null;
}

export interface DocumentUnit {
  unit_id: string | null;
  chunk_index: number;
  text: string;
  source_name: string | null;
  modality: string | null;
  block_type: string | null;
  section_path: string[];
  heading_context: string | null;
  page_number: number | null;
  sheet_name: string | null;
  cell_range: string | null;
  table_id: string | null;
  source_start: number | null;
  source_end: number | null;
  parser_version?: string | null;
}

/** Organization-level role carried by the account. */
export type OrgRole = "ORG_OWNER" | "MEMBER";
/** Per-workspace authority on the membership row. */
export type WorkspaceRole = "WORKSPACE_ADMIN" | "MEMBER";
export type UserStatus = "ACTIVE" | "INACTIVE";
export type InvitationStatus = "PENDING" | "EXPIRED" | "CANCELLED" | "ACCEPTED";
export type WorkspaceVisibility = "PUBLIC" | "PRIVATE";

export interface TeamMember {
  id: string;
  email: string;
  fullName: string;
  workspaceRole: WorkspaceRole;
  status: UserStatus;
  joinedAt: string;
  lastLoginAt: string | null;
  currentUser: boolean;
}

export interface TeamInvitation {
  id: string;
  email: string;
  role: WorkspaceRole;
  status: InvitationStatus;
  invitedAt: string;
  expiresAt: string;
  lastSentAt: string;
}

export interface TeamDirectory {
  members: TeamMember[];
  invitations: TeamInvitation[];
  visibility: WorkspaceVisibility;
  defaultWorkspace: boolean;
}

export interface OrganizationMember {
  id: string;
  email: string;
  fullName: string;
  orgRole: OrgRole;
  status: UserStatus;
  createdAt: string;
  lastLoginAt: string | null;
  currentUser: boolean;
}

/** Machine API key as reported by `/admin/api-keys` (secrets are write-only). */
export interface ApiKeyView {
  id: string;
  workspaceId: string;
  serviceUserId: string;
  name: string;
  /** `ovk_` + first secret characters, identification only. */
  keyPrefix: string;
  role: WorkspaceRole;
  status: string;
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
  createdBy: string;
  /** True when the key can no longer authenticate (revoked or expired). */
  expired: boolean;
}

/** Creation response: `key` is the secret shown exactly once — store it now. */
export interface CreatedApiKey {
  id: string;
  key: string;
  keyView: ApiKeyView;
}

export interface InvitationValidation {
  email: string;
  organizationName: string;
  workspaceName: string;
  role: WorkspaceRole;
  expiresAt: string;
}

/** Matches Spring Boot `AuthResponse.user` (JWT login/refresh/switch). */
export interface AuthUser {
  userId: string;
  orgId: string;
  activeWorkspaceId: string;
  fullName: string;
  email: string;
  orgRole: OrgRole;
  workspaceRole: WorkspaceRole;
  mustChangePassword: boolean;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: AuthUser;
}

/** One workspace the caller belongs to (`GET /auth/workspaces`). */
export interface WorkspaceSummary {
  id: string;
  name: string;
  slug: string;
  role: WorkspaceRole;
  isDefault: boolean;
}

/** Admin view of a workspace (`GET /workspaces`). */
export interface WorkspaceView {
  id: string;
  name: string;
  slug: string;
  status: string;
  visibility: WorkspaceVisibility;
  isDefault: boolean;
  memberCount: number;
  createdAt: string;
}

export interface WorkspaceMemberView {
  userId: string;
  email: string;
  fullName: string;
  status: UserStatus;
  workspaceRole: WorkspaceRole;
  joinedAt: string;
}

export interface OrganizationSnapshot {
  id: string;
  name: string;
  slug: string;
  allowSelfRegistration: boolean;
}

/** Notification channel as reported by the API (secrets are write-only). */
export interface NotificationChannelView {
  id: string;
  type: string;
  enabled: boolean;
  credentialsStored: boolean;
  lastDeliveryError: string | null;
  lastDeliveryAt: string | null;
}

export interface SetupStatus {
  required: boolean;
}

export interface RegistrationStatus {
  setupRequired: boolean;
  selfRegistrationAllowed: boolean;
}

export interface TenantWorkspace {
  tenantId: string;
  knowledgeBase: {
    id: string;
    name: string;
    slug: string;
    defaultLocale: string;
  };
  chatbot: {
    id: string;
    displayName: string;
    defaultLocale: string;
    welcomeMessage: string;
  };
}

export interface ChatSessionResponse {
  id: string;
  chatbot_id: string;
  knowledge_base_id: string;
  tenant_id: string;
  locale: string;
}

export interface ChatCitationImage {
  media_id: string;
  media_type?: string | null;
  width?: number | null;
  height?: number | null;
}

export interface ChatCitation {
  id: string;
  document_id: string;
  source_name: string;
  page_number: number | null;
  chunk_index: number;
  score: number;
  snippet: string;
  unit_id?: string | null;
  modality?: string | null;
  section_path?: string[];
  block_type?: string | null;
  sheet_name?: string | null;
  cell_range?: string | null;
  table_id?: string | null;
  parser_version?: string | null;
  public_url?: string | null;
  image?: ChatCitationImage | null;
}

export interface AssistantMessageResponse {
  id?: string;
  role: "assistant";
  content: string;
  citations: ChatCitation[];
  action?: Record<string, unknown> | null;
  /** Short follow-up questions (at most a few) the user can send next; absent when none were made. */
  suggested_questions?: string[];
}

export interface ChatHistoryMessageResponse {
  id?: string;
  role: "user" | "assistant" | "system";
  content: string;
  citations: ChatCitation[];
  sequence_number?: number | null;
  action?: Record<string, unknown> | null;
}

export interface PlaygroundSession {
  id: string;
  title: string;
  message_count: number;
  status: string;
  created_at: string;
  last_activity_at: string;
}

// ─── BizApps (Tier 3) ────────────────────────────────────────────────────────
// Mirror of the wire records in com.openvie.api.bizapp.dto.BizAppDtos and the
// bizapp.v1 manifest shape the backend validator enforces.

export interface BizAppLocalized {
  vi?: string | null;
  en?: string | null;
}

export interface BizAppField {
  name: string;
  type: "string" | "number" | "boolean" | "enum" | "date" | "ref";
  entity?: string;
  required?: boolean;
  maxLen?: number;
  format?: string;
  options?: string[];
  pii?: boolean;
  label?: BizAppLocalized;
}

export interface BizAppEntity {
  name: string;
  label?: BizAppLocalized;
  fields: BizAppField[];
  knowledge?: {
    project?: boolean;
    titleField?: string;
    fields?: string[];
  };
  hooks?: Record<string, string[]>;
}

export interface BizAppBlock {
  id: string;
  type: string;
  params?: Record<string, unknown>;
}

export interface BizAppManifest {
  schema: "bizapp.v1";
  kind: string;
  name: BizAppLocalized;
  version?: number;
  icon?: string;
  storage?: "jsonb" | "typed";
  rbac?: Record<string, string[]>;
  entities: BizAppEntity[];
  blocks?: BizAppBlock[];
  ui?: {
    views: string[];
    layout?: Record<string, { x: number; y: number }>;
  };
}
export interface BizAppInstalledAppView {
  kind: string;
  version: number;
  nameEn: string;
  nameVi: string;
  icon?: string | null;
  status: "ACTIVE" | "DISABLED";
  manifest: BizAppManifest;
  config?: Record<string, unknown> | null;
  processKey?: string | null;
  installedAt: string;
}

export interface BizAppTemplateView {
  /** Only for org (`source === "org"`) templates; platform entries are null. */
  id?: string | null;
  source: "platform" | "org";
  kind: string;
  name: string;
  nameVi?: string | null;
  summary?: string | null;
  version: number;
}

export interface BizAppRecordView {
  id: string;
  appKind: string;
  entity: string;
  payload: Record<string, unknown>;
  runtime?: Record<string, unknown> | null;
  status: "ACTIVE" | "ARCHIVED";
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

/** Spring Data page body as serialized by the API. */
export interface BizAppRecordPage {
  content: BizAppRecordView[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface BizAppCredentialView {
  id: string;
  name: string;
  format: "api_key" | "bearer" | "basic";
  createdAt: string;
}

export interface BizAppInstallResult {
  installationId: string;
  kind: string;
  version: number;
  processKey?: string | null;
}

/** Stateless manifest validation result (`POST /bizapps/validate`). */
export interface BizAppValidationResult {
  ok: boolean;
  errors: string[];
}

/** One draft entry — AI-generated, canvas-edited, imported or remixed. */
export interface BizAppDraftView {
  id: string;
  source: "ai" | "canvas" | "import" | "remix";
  title: string | null;
  manifest: Record<string, unknown> | null;
  /** `{ ok, errors[] }` as stored by the backend, when validation ran. */
  validation: Record<string, unknown> | null;
  meta: Record<string, unknown> | null;
  createdAt: string;
  updatedAt: string;
}

/** Response of `POST /bizapps/drafts/generate` (draft only, never installs). */
export interface BizAppGenerateDraftResult {
  draftId: string;
  manifest: Record<string, unknown> | null;
  validation: BizAppValidationResult | null;
  meta: Record<string, unknown> | null;
}
