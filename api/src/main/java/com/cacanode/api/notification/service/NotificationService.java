package com.cacanode.api.notification.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.cacanode.api.notification.enums.NotificationStatus;
import com.cacanode.api.notification.enums.NotificationType;
import com.cacanode.api.notification.model.Notification;
import com.cacanode.api.notification.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "NOTIFICATION-SERVICE")
@Service
@RequiredArgsConstructor
public class NotificationService {

  private final NotificationRepository notificationRepository;
  private final EmailService emailService;

  public void sendAndRecordInvitationEmail(
      UUID tenantId,
      String email,
      String organizationName,
      String workspaceName,
      String role,
      String token,
      LocalDateTime expiresAt) {
    Notification notification = new Notification();
    notification.setTenantId(tenantId);
    notification.setType(NotificationType.USER_INVITED);
    notification.setTitle("You're invited to " + workspaceName);
    notification.setMessage("Team invitation sent to " + email);
    notification.setStatus(NotificationStatus.PENDING);
    notificationRepository.save(notification);

    try {
      emailService.sendInvitationEmail(email, organizationName, workspaceName, role, token, expiresAt);
      notification.setStatus(NotificationStatus.SENT);
      notification.setSentAt(LocalDateTime.now());
    } catch (Exception e) {
      notification.setStatus(NotificationStatus.FAILED);
      log.error("Invitation email sending failed: {}", e.getMessage());
      throw e;
    } finally {
      notificationRepository.save(notification);
    }
  }

}
