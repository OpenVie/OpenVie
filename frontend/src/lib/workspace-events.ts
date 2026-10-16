export const WORKSPACES_CHANGED_EVENT = "openvie:workspaceschange"

export function announceWorkspacesChanged() {
  window.dispatchEvent(new Event(WORKSPACES_CHANGED_EVENT))
}
