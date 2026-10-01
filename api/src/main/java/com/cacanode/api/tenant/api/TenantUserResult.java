package com.cacanode.api.tenant.api;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class TenantUserResult {
    private UUID tenantId;
    private UUID userId;
    private String email;
    private String fullName;
    private String role;
    private String status;
}
