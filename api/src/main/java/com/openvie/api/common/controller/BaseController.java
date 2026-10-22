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

package com.openvie.api.common.controller;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Reads the request scope established by JwtAuthFilter. Every value here is
 * re-resolved from the database on each request; nothing is trusted straight
 * from a token.
 */
public abstract class BaseController {

  protected UUID getTenantId(HttpServletRequest request) {
    return UUID.fromString((String) request.getAttribute("tenantId"));
  }

  protected UUID getUserId(HttpServletRequest request) {
    return UUID.fromString((String) request.getAttribute("userId"));
  }

  /** Workspace role of the caller: WORKSPACE_ADMIN or MEMBER. */
  protected String getRole(HttpServletRequest request) {
    return (String) request.getAttribute("role");
  }

  /** Organization owners act with admin authority inside workspaces they hold. */
  protected boolean isWorkspaceAdmin(HttpServletRequest request) {
    return "WORKSPACE_ADMIN".equals(getRole(request));
  }

  protected UUID getOrgId(HttpServletRequest request) {
    return UUID.fromString((String) request.getAttribute("orgId"));
  }

  protected String getOrgRole(HttpServletRequest request) {
    return (String) request.getAttribute("orgRole");
  }

}
