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

package com.openvie.api.auth.dto.response;

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
        private String orgId;
        private String activeWorkspaceId;
        private String fullName;
        private String email;
        private String orgRole;
        private String workspaceRole;
        private boolean mustChangePassword;
    }
}
