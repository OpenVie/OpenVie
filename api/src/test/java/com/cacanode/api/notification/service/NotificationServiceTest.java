package com.cacanode.api.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        notificationService.sendAndRecordWelcomeEmail(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "user@example.com",
                "Ada Lovelace",
                "Example Co",
                "verify-token"
        );

        Notification saved = lastSavedNotification();
        assertEquals(NotificationStatus.SENT, saved.getStatus());
        assertNotNull(saved.getSentAt());
    }

    @Test
    void emailServiceFailureMarksNotificationFailedAndRethrows() {
        doThrow(new EmailDeliveryException("all providers failed"))
                .when(emailService)
                .sendWelcomeEmail(any(), any(), any(), any());

        assertThrows(EmailDeliveryException.class, () -> notificationService.sendAndRecordWelcomeEmail(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "user@example.com",
                "Ada Lovelace",
                "Example Co",
                "verify-token"
        ));

        Notification saved = lastSavedNotification();
        assertEquals(NotificationStatus.FAILED, saved.getStatus());
    }

    private Notification lastSavedNotification() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }
}
