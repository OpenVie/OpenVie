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
