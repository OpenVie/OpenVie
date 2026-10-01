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
}

export type DocumentStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";

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
  chunkCount?: number | null;
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
  public_url?: string | null;
}

export interface AssistantMessageResponse {
  role: "assistant";
  content: string;
  citations: ChatCitation[];
}

export interface ChatHistoryMessageResponse {
  role: "user" | "assistant" | "system";
  content: string;
  citations: ChatCitation[];
  sequence_number?: number | null;
}

export interface PlaygroundSession {
  id: string;
  title: string;
  message_count: number;
  status: string;
  created_at: string;
  last_activity_at: string;
}
