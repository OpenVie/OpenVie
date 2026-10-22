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

package com.openvie.api.tenant.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

import com.openvie.api.common.controller.BaseController;
import com.openvie.api.tenant.dto.TenantWorkspaceResponse;
import com.openvie.api.tenant.service.TenantWorkspaceService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Tag(name = "Tenants", description = "Endpoints for tenant management")
@RestController
@RequestMapping({"/api/v1/tenants", "/api/tenants"})
@RequiredArgsConstructor
public class TenantController extends BaseController {
  private final TenantWorkspaceService tenantWorkspaceService;

  @Operation(summary = "Get or provision the caller's workspace",
          description = "Returns the workspace bound to the caller's tenant token, provisioning a default one on first "
                  + "call (idempotent).")
  @GetMapping("/me/workspace")
  public TenantWorkspaceResponse getMyWorkspace(HttpServletRequest request) {
    return tenantWorkspaceService.getOrProvisionWorkspace(getTenantId(request));
  }

}
