package com.cacanode.api.tenant.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

import com.cacanode.api.common.controller.BaseController;
import com.cacanode.api.tenant.dto.TenantWorkspaceResponse;
import com.cacanode.api.tenant.service.TenantWorkspaceService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Tag(name = "Tenants", description = "Endpoints for tenant management")
@RestController
@RequestMapping({"/api/v1/tenants", "/api/tenants"})
@RequiredArgsConstructor
public class TenantController extends BaseController {
  private final TenantWorkspaceService tenantWorkspaceService;

  @GetMapping("/me/workspace")
  public TenantWorkspaceResponse getMyWorkspace(HttpServletRequest request) {
    return tenantWorkspaceService.getOrProvisionWorkspace(getTenantId(request));
  }

}
