package com.cacanode.api.notification.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.cacanode.api.auth.api.Login2FAChallengeType;
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

  public void sendAndRecordWelcomeEmail(
      UUID tenantId,
      UUID userId,
      String email,
      String fullName,
      String companyName,
      String verificationToken) {
    Notification notification = new Notification();
    notification.setTenantId(tenantId);
    notification.setUserId(userId);
    notification.setType(NotificationType.WELCOME_EMAIL);
    notification.setTitle("Welcome to CacaNode");
    notification.setMessage("Welcome email sent to " + email);
    notification.setStatus(NotificationStatus.PENDING);
    notificationRepository.save(notification);

    try {
      emailService.sendWelcomeEmail(email, fullName, companyName, verificationToken);
      notification.setStatus(NotificationStatus.SENT);
      notification.setSentAt(LocalDateTime.now());
    } catch (Exception e) {
      notification.setStatus(NotificationStatus.FAILED);
      log.error("Email sending failed: {}", e.getMessage());
      throw e;
    } finally {
      notificationRepository.save(notification);
    }
  }

  public void sendAndRecordLogin2FAEmail(
      UUID tenantId,
      UUID userId,
      String email,
      String fullName,
      String verificationSecret,
      Login2FAChallengeType challengeType) {
    Notification notification = new Notification();
    notification.setTenantId(tenantId);
    notification.setUserId(userId);
    notification.setType(NotificationType.LOGIN_2FA_EMAIL);
    notification.setTitle("Login Verification - CacaNode");
    notification.setMessage("Login 2FA email sent to " + email);
    notification.setStatus(NotificationStatus.PENDING);
    notificationRepository.save(notification);

    try {
      if (challengeType == Login2FAChallengeType.CODE) {
        emailService.sendLogin2FACodeEmail(email, fullName, verificationSecret);
      } else {
        emailService.sendLogin2FAEmail(email, fullName, verificationSecret);
      }
      notification.setStatus(NotificationStatus.SENT);
      notification.setSentAt(LocalDateTime.now());
    } catch (Exception e) {
      notification.setStatus(NotificationStatus.FAILED);
      log.error("Login 2FA email sending failed: {}", e.getMessage());
      throw e;
    } finally {
      notificationRepository.save(notification);
    }
  }

  public void sendAndRecordInvitationEmail(
      UUID tenantId,
      String email,
      String tenantName,
      String role,
      String token,
      LocalDateTime expiresAt) {
    Notification notification = new Notification();
    notification.setTenantId(tenantId);
    notification.setType(NotificationType.USER_INVITED);
    notification.setTitle("You're invited to " + tenantName);
    notification.setMessage("Team invitation sent to " + email);
    notification.setStatus(NotificationStatus.PENDING);
    notificationRepository.save(notification);

    try {
      emailService.sendInvitationEmail(email, tenantName, role, token, expiresAt);
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
