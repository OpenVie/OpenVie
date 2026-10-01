package com.cacanode.api.tenant.api;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RegisterTenantCommand {
    private String companyName;
    private String email;
    private String passwordHash; // already hashed by auth module
    private String fullName;
}
