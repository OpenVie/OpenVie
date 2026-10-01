package com.cacanode.api.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.cacanode.api.notification.enums.NotificationStatus;
import com.cacanode.api.notification.enums.NotificationType;
import com.cacanode.api.notification.model.Notification;
import com.cacanode.api.notification.repository.NotificationRepository;

class NotificationServiceTest {

    private NotificationRepository notificationRepository;
    private EmailService emailService;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        emailService = mock(EmailService.class);
        notificationService = new NotificationService(notificationRepository, emailService);

        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void emailServiceSuccessMarksNotificationSent() {
        sendInvitation();

        Notification saved = lastSavedNotification();
        assertEquals(NotificationStatus.SENT, saved.getStatus());
        assertEquals(NotificationType.USER_INVITED, saved.getType());
        assertNotNull(saved.getSentAt());
    }

    @Test
    void emailServiceFailureMarksNotificationFailedAndRethrows() {
        doThrow(new EmailDeliveryException("provider down"))
                .when(emailService)
                .sendInvitationEmail(any(), any(), any(), any(), any(), any(), any());

        assertThrows(EmailDeliveryException.class, this::sendInvitation);

        assertEquals(NotificationStatus.FAILED, lastSavedNotification().getStatus());
    }

    private void sendInvitation() {
        notificationService.sendAndRecordInvitationEmail(
                UUID.randomUUID(), UUID.randomUUID(), "user@example.com", "Acme", "Finance", "MEMBER",
                "invite-token", LocalDateTime.now().plusHours(72));
    }

    private Notification lastSavedNotification() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }
}
