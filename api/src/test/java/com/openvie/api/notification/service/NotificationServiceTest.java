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

package com.openvie.api.notification.service;

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

import com.openvie.api.notification.enums.NotificationStatus;
import com.openvie.api.notification.enums.NotificationType;
import com.openvie.api.notification.model.Notification;
import com.openvie.api.notification.repository.NotificationRepository;

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
