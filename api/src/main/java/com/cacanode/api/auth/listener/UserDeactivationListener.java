package com.cacanode.api.auth.listener;

import com.cacanode.api.auth.repository.RefreshTokenRepository;
import com.cacanode.api.tenant.api.event.UserDeactivatedEvent;
import com.cacanode.api.common.event.durable.ModuleEventInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class UserDeactivationListener {
    private final RefreshTokenRepository refreshTokenRepository;
    @Autowired(required = false)
    private ModuleEventInboxService inboxService;

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revoke(UserDeactivatedEvent event) {
        if (inboxService != null && !inboxService.claim("auth.refresh-token-revocation")) return;
        refreshTokenRepository.revokeAllByUserId(event.userId());
    }
}
