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

export interface User {
  id: string;
  email: string;
  fullName: string;
  role: UserRole;
  status: UserStatus;
  joinedAt: string;
}

export type AuthRole = "TENANT_ADMIN" | "USER";
export type UserRole = "TENANT_ADMIN" | "USER";
export type UserStatus = "ACTIVE" | "INACTIVE";
export type InvitationStatus = "PENDING" | "EXPIRED" | "CANCELLED" | "ACCEPTED";

export interface TeamMember extends User {
  lastLoginAt: string | null;
  currentUser: boolean;
}

export interface TeamInvitation {
  id: string;
  email: string;
  role: UserRole;
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
  tenantName: string;
  role: AuthRole;
  expiresAt: string;
}

/** Matches Spring Boot `AuthResponse.user` (JWT login/refresh). */
export interface AuthUser {
  userId: string;
  tenantId: string;
  email: string;
  fullName: string;
  role: AuthRole;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: AuthUser;
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

export interface LoginStep1Response {
  message: string;
  email: string;
  requires2FA: boolean;
}

export type LoginResponse = AuthResponse | LoginStep1Response;

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

