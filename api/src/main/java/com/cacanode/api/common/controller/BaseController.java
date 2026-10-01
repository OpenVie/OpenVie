package com.cacanode.api.common.controller;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

public abstract class BaseController {

  protected UUID getTenantId(HttpServletRequest request) {
    return UUID.fromString((String) request.getAttribute("tenantId"));
  }

  protected UUID getUserId(HttpServletRequest request) {
    return UUID.fromString((String) request.getAttribute("userId"));
  }

  protected String getRole(HttpServletRequest request) {
    return (String) request.getAttribute("role");
  }

}
