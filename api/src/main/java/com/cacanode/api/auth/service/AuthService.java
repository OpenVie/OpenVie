package com.cacanode.api.auth.service;

import com.cacanode.api.auth.dto.request.LoginRequest;
import com.cacanode.api.auth.dto.response.AuthResponse;
import com.cacanode.api.auth.dto.response.ResendVerificationResponse;
import com.cacanode.api.auth.dto.response.InvitationValidationResponse;
import com.cacanode.api.auth.dto.request.AcceptInvitationRequest;
import com.cacanode.api.tenant.api.UserAuthDto;

import jakarta.servlet.http.HttpServletResponse;

public interface AuthService {

    boolean isEmailExist(String email);

    void setRefreshTokenCookie(HttpServletResponse response, String refreshToken, boolean persistent);

    void clearRefreshTokenCookie(HttpServletResponse response);



    Object login(LoginRequest req, HttpServletResponse res);


    AuthResponse verifyLogin2FA(String token, HttpServletResponse res);


    ResendVerificationResponse resendLogin2FA(String email);

    void logout(String refreshToken);

    AuthResponse refreshToken(String refreshToken, HttpServletResponse res);



    AuthResponse issueAuthTokens(UserAuthDto user, HttpServletResponse response, boolean persistent);

    InvitationValidationResponse validateInvitation(String token);

    AuthResponse acceptInvitation(AcceptInvitationRequest request, HttpServletResponse response);
}
