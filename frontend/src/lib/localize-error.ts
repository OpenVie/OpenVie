/**
 * Centralized error localization for API responses across OpenVie.
 *
 * Spring Boot and FastAPI backend error messages are emitted in English by
 * default. This utility maps server error strings to the user's active
 * locale (Vietnamese or English).
 */

export function getClientLocale(): "vi" | "en" {
  if (typeof document !== "undefined" && document.documentElement.lang) {
    return document.documentElement.lang.toLowerCase().startsWith("en") ? "en" : "vi";
  }
  return "vi";
}

const ERROR_TRANSLATIONS: Record<string, { vi: string; en: string }> = {
  // Authentication & Session
  "invalid email or password": {
    vi: "Email hoặc mật khẩu không đúng",
    en: "Invalid email or password",
  },
  "refresh token missing": {
    vi: "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.",
    en: "Refresh token is missing. Please sign in again.",
  },
  "invalid refresh token": {
    vi: "Phiên đăng nhập không hợp lệ hoặc đã hết hạn",
    en: "Invalid or expired refresh token",
  },
  "session is no longer valid": {
    vi: "Phiên làm việc không còn hợp lệ. Vui lòng đăng nhập lại.",
    en: "Session is no longer valid. Please sign in again.",
  },
  "user account is disabled": {
    vi: "Tài khoản người dùng đã bị vô hiệu hóa",
    en: "User account is disabled",
  },
  "sso is not enabled": {
    vi: "Đăng nhập một lần (SSO) chưa được kích hoạt",
    en: "SSO is not enabled",
  },
  "an account already exists for this email": {
    vi: "Tài khoản với email này đã tồn tại trong tổ chức",
    en: "An account already exists for this email",
  },
  "email is already taken": {
    vi: "Địa chỉ email này đã được sử dụng",
    en: "Email is already taken",
  },
  "self-registration is not enabled for this installation": {
    vi: "Tính năng tự đăng ký chưa được kích hoạt cho hệ thống này",
    en: "Self-registration is not enabled for this installation",
  },
  "this installation has already been set up": {
    vi: "Hệ thống đã được thiết lập từ trước",
    en: "This installation has already been set up",
  },
  "current password is incorrect": {
    vi: "Mật khẩu hiện tại không đúng",
    en: "Current password is incorrect",
  },
  "password must be at least 12 characters": {
    vi: "Mật khẩu phải có ít nhất 12 ký tự",
    en: "Password must be at least 12 characters",
  },
  "use the change-password endpoint for your own password": {
    vi: "Vui lòng dùng chức năng đổi mật khẩu cho tài khoản của bạn",
    en: "Use the change-password page for your own password",
  },
  "you cannot change your own role": {
    vi: "Bạn không thể tự thay đổi vai trò của chính mình",
    en: "You cannot change your own role",
  },
  "you cannot deactivate your own account": {
    vi: "Bạn không thể tự vô hiệu hóa tài khoản của chính mình",
    en: "You cannot deactivate your own account",
  },
  "the organization owner cannot be deactivated here": {
    vi: "Không thể vô hiệu hóa chủ sở hữu tổ chức tại đây",
    en: "The organization owner cannot be deactivated here",
  },
  "user not found": {
    vi: "Không tìm thấy người dùng",
    en: "User not found",
  },

  // Invitations
  "invitation is invalid or no longer available": {
    vi: "Lời mời không hợp lệ hoặc không còn khả dụng",
    en: "Invitation is invalid or no longer available",
  },
  "invitation has expired": {
    vi: "Lời mời đã hết hạn",
    en: "Invitation has expired",
  },
  "only pending or expired invitations can be resent": {
    vi: "Chỉ có thể gửi lại lời mời đang chờ hoặc đã hết hạn",
    en: "Only pending or expired invitations can be resent",
  },
  "only pending invitations can be cancelled": {
    vi: "Chỉ có thể hủy lời mời đang chờ",
    en: "Only pending invitations can be cancelled",
  },

  // Workspaces & Members
  "workspace was not found": {
    vi: "Không tìm thấy không gian làm việc",
    en: "Workspace was not found",
  },
  "workspace not found": {
    vi: "Không tìm thấy không gian làm việc",
    en: "Workspace not found",
  },
  "workspace is not active": {
    vi: "Không gian làm việc không ở trạng thái hoạt động",
    en: "Workspace is not active",
  },
  "workspace does not belong to your organization": {
    vi: "Không gian làm việc không thuộc tổ chức của bạn",
    en: "Workspace does not belong to your organization",
  },
  "that account is already a member of this workspace": {
    vi: "Tài khoản này đã là thành viên của không gian làm việc",
    en: "That account is already a member of this workspace",
  },
  "only workspace admins can add members to a private workspace": {
    vi: "Chỉ quản trị viên mới có thể thêm thành viên vào không gian làm việc riêng tư",
    en: "Only workspace admins can add members to a private workspace",
  },
  "regular members can only add colleagues as regular members": {
    vi: "Thành viên thường chỉ có thể thêm đồng nghiệp với vai trò thành viên",
    en: "Regular members can only add colleagues as regular members",
  },
  "members cannot be removed from the default workspace": {
    vi: "Không thể xóa thành viên khỏi không gian làm việc mặc định",
    en: "Members cannot be removed from the default workspace",
  },
  "you cannot remove yourself from this workspace": {
    vi: "Bạn không thể tự xóa mình khỏi không gian làm việc này",
    en: "You cannot remove yourself from this workspace",
  },
  "a workspace needs at least one active admin": {
    vi: "Không gian làm việc cần có ít nhất một quản trị viên đang hoạt động",
    en: "A workspace needs at least one active admin",
  },
  "only workspace admins may remove members": {
    vi: "Chỉ quản trị viên mới có quyền xóa thành viên",
    en: "Only workspace admins may remove members",
  },
  "this action requires workspace admin authority": {
    vi: "Thao tác này yêu cầu quyền quản trị viên không gian làm việc",
    en: "This action requires workspace admin authority",
  },
  "this action requires organization owner authority": {
    vi: "Thao tác này yêu cầu quyền chủ sở hữu tổ chức",
    en: "This action requires organization owner authority",
  },

  // Documents
  "document not found": {
    vi: "Không tìm thấy tài liệu",
    en: "Document not found",
  },
  "document was not found": {
    vi: "Không tìm thấy tài liệu",
    en: "Document was not found",
  },
  "chat scope must reference completed documents of this knowledge base.": {
    vi: "Phạm vi tài liệu chỉ có thể áp dụng cho các tài liệu đã lập chỉ mục xong.",
    en: "Chat scope must reference completed documents of this knowledge base.",
  },
  "the model returned an invalid citation.": {
    vi: "Mô hình trả về nguồn trích dẫn không hợp lệ.",
    en: "The model returned an invalid citation.",
  },

  // Workflow & Approvals
  "approval process not found": {
    vi: "Không tìm thấy quy trình phê duyệt",
    en: "Approval process not found",
  },
  "approval task not found": {
    vi: "Không tìm thấy nhiệm vụ phê duyệt",
    en: "Approval task not found",
  },
  "a rejection requires a reason": {
    vi: "Từ chối tài liệu bắt buộc phải có lý do",
    en: "A rejection requires a reason",
  },
  "you cannot approve your own submission. hand it to another reviewer or escalate it upward.": {
    vi: "Bạn không thể tự duyệt tài liệu do chính mình nộp. Hãy chuyển cho người khác duyệt hoặc chuyển cấp.",
    en: "You cannot approve your own submission. Hand it to another reviewer or escalate it upward.",
  },
  "task already claimed by another approver": {
    vi: "Nhiệm vụ này đã được nhận bởi người duyệt khác",
    en: "Task already claimed by another approver",
  },
  "task already claimed by another assignee": {
    vi: "Nhiệm vụ này đã được nhận bởi người duyệt khác",
    en: "Task already claimed by another assignee",
  },
  "only level 1 tasks can be handed off to level 2": {
    vi: "Chỉ nhiệm vụ cấp 1 mới có thể chuyển tiếp lên cấp 2",
    en: "Only level 1 tasks can be handed off to level 2",
  },
  "approval tasks belong to the document journal — use the approval endpoints": {
    vi: "Nhiệm vụ thuộc quy trình phê duyệt tài liệu — vui lòng thao tác qua mục Phê duyệt",
    en: "Approval tasks belong to the document journal — use the approval endpoints",
  },
  "you can only view approval processes that you submitted": {
    vi: "Bạn chỉ có thể xem các quy trình phê duyệt do chính mình nộp",
    en: "You can only view approval processes that you submitted",
  },
  "this approval process belongs to another workspace": {
    vi: "Quy trình phê duyệt này thuộc về không gian làm việc khác",
    en: "This approval process belongs to another workspace",
  },
  "this approval task belongs to another workspace": {
    vi: "Nhiệm vụ phê duyệt này thuộc về không gian làm việc khác",
    en: "This approval task belongs to another workspace",
  },

  // Rate limit & API keys
  "authenticated request rate limit exceeded": {
    vi: "Đã vượt quá giới hạn tần suất yêu cầu. Vui lòng thử lại sau giây lát.",
    en: "Authenticated request rate limit exceeded. Please try again shortly.",
  },
  "rate limit exceeded": {
    vi: "Đã vượt quá giới hạn tần suất yêu cầu. Vui lòng thử lại sau giây lát.",
    en: "Rate limit exceeded. Please try again shortly.",
  },
  "invalid api key": {
    vi: "Khóa API không hợp lệ hoặc đã bị thu hồi",
    en: "Invalid or revoked API key",
  },
  "api key not found": {
    vi: "Không tìm thấy khóa API",
    en: "API key not found",
  },
  "webhook endpoint not found": {
    vi: "Không tìm thấy cấu hình webhook",
    en: "Webhook endpoint not found",
  },
  "webhook endpoint is disabled": {
    vi: "Cấu hình webhook đang bị tắt",
    en: "Webhook endpoint is disabled",
  },
};

/**
 * Translates a raw server error message to the target locale if known.
 * Returns the translated message, or rawMessage if no translation exists.
 */
export function localizeErrorMessage(rawMessage: string, targetLocale?: string): string {
  if (!rawMessage || typeof rawMessage !== "string") {
    return "";
  }
  const locale = (targetLocale?.toLowerCase().startsWith("en") ? "en" : (targetLocale?.toLowerCase().startsWith("vi") ? "vi" : getClientLocale()));
  const normalized = rawMessage.trim().toLowerCase();

  const exact = ERROR_TRANSLATIONS[normalized];
  if (exact) {
    return exact[locale];
  }

  // Parameterized error matching: "Please wait X seconds before resending"
  const waitMatch = normalized.match(/^please wait (\d+) seconds before resending$/);
  if (waitMatch) {
    const seconds = waitMatch[1];
    return locale === "vi"
      ? `Vui lòng đợi ${seconds} giây trước khi gửi lại`
      : `Please wait ${seconds} seconds before resending`;
  }

  return rawMessage;
}
