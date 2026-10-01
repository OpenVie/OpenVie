package com.cacanode.api.auth.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthResponse {
    private String accessToken;
    private String tokenType;
    private long expiresIn;         // access token expiry in seconds
    private UserInfo user;

    @Getter
    @Builder
    public static class UserInfo {
        private String userId;
        private String tenantId;
        private String fullName;
        private String email;
        private String role;
        private String plan;
    }
}
