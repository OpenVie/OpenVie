package com.cacanode.api.auth.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoginStep1Response {
    private String message;
    private String email;
    private Boolean requires2FA;
}
