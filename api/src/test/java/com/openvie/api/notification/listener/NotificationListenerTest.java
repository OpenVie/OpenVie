package com.openvie.api.notification.listener;

import com.openvie.api.notification.service.EmailDeliveryException;
import com.openvie.api.notification.service.EmailService;
import com.openvie.api.notification.service.NotificationService;
import com.openvie.api.tenant.api.event.AccountCreatedEvent;
import com.openvie.api.tenant.api.event.UserInvitedEvent;
import com.openvie.api.tenant.api.event.WorkspaceMemberAddedEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationListenerTest {

    @Test
    void invitationEventSendsMemberInvitationEmail() {
        NotificationService notificationService = mock(NotificationService.class);
        EmailService emailService = mock(EmailService.class);
        NotificationListener listener = new NotificationListener(notificationService, emailService);
        UUID orgId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(3);
        UserInvitedEvent event = new UserInvitedEvent(
                orgId, workspaceId, UUID.randomUUID(), "member@example.com", "Acme Corp", "Finance",
                "MEMBER", "invite-token", expiresAt);

        listener.handleUserInvited(event);

        verify(notificationService).sendAndRecordInvitationEmail(
                orgId, workspaceId, "member@example.com", "Acme Corp", "Finance", "MEMBER",
                "invite-token", expiresAt);
    }

    @Test
    void workspaceMemberAddedEventSendsWorkspaceAddedNotice() {
        NotificationService notificationService = mock(NotificationService.class);
        EmailService emailService = mock(EmailService.class);
        NotificationListener listener = new NotificationListener(notificationService, emailService);
        WorkspaceMemberAddedEvent event = new WorkspaceMemberAddedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "member@example.com",
                "Dana Perdana", "Acme Corp", "Finance", "MEMBER");

        listener.handleWorkspaceMemberAdded(event);

        verify(emailService).sendWorkspaceAddedEmail(
                event.orgId(), "member@example.com", "Dana Perdana",
                "Acme Corp", "Finance", "MEMBER");
    }

    @Test
    void accountCreatedEventSendsAccountCreatedNotice() {
        NotificationService notificationService = mock(NotificationService.class);
        EmailService emailService = mock(EmailService.class);
        NotificationListener listener = new NotificationListener(notificationService, emailService);
        AccountCreatedEvent event = new AccountCreatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "new@example.com", "New Person");

        listener.handleAccountCreated(event);

        verify(emailService).sendAccountCreatedEmail(
                event.orgId(), "new@example.com", "New Person");
    }

    @Test
    void deliveryFailuresOfBestEffortNoticesAreSwallowed() {
        NotificationService notificationService = mock(NotificationService.class);
        EmailService emailService = mock(EmailService.class);
        doThrow(new EmailDeliveryException("smtp relay down")).when(emailService)
                .sendWorkspaceAddedEmail(any(), any(), any(), any(), any(), any());
        doThrow(new EmailDeliveryException("smtp relay down")).when(emailService)
                .sendAccountCreatedEmail(any(), any(), any());
        NotificationListener listener = new NotificationListener(notificationService, emailService);

        listener.handleWorkspaceMemberAdded(new WorkspaceMemberAddedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "member@example.com",
                "Dana Perdana", "Acme Corp", "Finance", "MEMBER"));
        listener.handleAccountCreated(new AccountCreatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "new@example.com", "New Person"));

        verify(emailService).sendWorkspaceAddedEmail(
                any(), any(), any(), any(), any(), any());
        verify(emailService).sendAccountCreatedEmail(any(), any(), any());
    }
}
