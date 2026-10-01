package com.cacanode.api.notification.listener;

import com.cacanode.api.notification.service.NotificationService;
import com.cacanode.api.tenant.api.event.UserInvitedEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationListenerTest {

    @Test
    void invitationEventSendsMemberInvitationEmail() {
        NotificationService notificationService = mock(NotificationService.class);
        NotificationListener listener = new NotificationListener(notificationService);
        UUID workspaceId = UUID.randomUUID();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(3);
        UserInvitedEvent event = new UserInvitedEvent(
                workspaceId, UUID.randomUUID(), "member@example.com", "Acme Corp", "Finance",
                "MEMBER", "invite-token", expiresAt);

        listener.handleUserInvited(event);

        verify(notificationService).sendAndRecordInvitationEmail(
                workspaceId, "member@example.com", "Acme Corp", "Finance", "MEMBER",
                "invite-token", expiresAt);
    }
}
