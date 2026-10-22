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

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.openvie.api.notification.enums.NotificationStatus;
import com.openvie.api.notification.enums.NotificationType;
import com.openvie.api.notification.model.Notification;
import com.openvie.api.notification.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "NOTIFICATION-SERVICE")
@Service
@RequiredArgsConstructor
public class NotificationService {

  private final NotificationRepository notificationRepository;
  private final EmailService emailService;

  public void sendAndRecordInvitationEmail(
      UUID orgId,
      UUID workspaceId,
      String email,
      String organizationName,
      String workspaceName,
      String role,
      String token,
      LocalDateTime expiresAt) {
    Notification notification = new Notification();
    notification.setTenantId(workspaceId);
    notification.setType(NotificationType.USER_INVITED);
    notification.setTitle("You're invited to " + workspaceName);
    notification.setMessage("Team invitation sent to " + email);
    notification.setStatus(NotificationStatus.PENDING);
    notificationRepository.save(notification);

    try {
      emailService.sendInvitationEmail(
              orgId, email, organizationName, workspaceName, role, token, expiresAt);
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

  /**
   * Records and delivers the submitter-facing approval verdict. Both halves are
   * best-effort: the decision is already committed, so a missing mail channel
   * only marks the journal row FAILED instead of failing the workflow.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void sendAndRecordWorkflowDecisionEmail(
      UUID orgId,
      UUID workspaceId,
      UUID userId,
      String email,
      String fullName,
      String documentFileName,
      String decision,
      String reason,
      int approvalLevel,
      String publishTarget) {
    boolean approved = "APPROVE".equalsIgnoreCase(decision);
    String fileName = documentFileName == null || documentFileName.isBlank()
        ? "Your submission"
        : documentFileName;
    Notification notification = new Notification();
    notification.setTenantId(workspaceId);
    notification.setUserId(userId);
    notification.setType(NotificationType.WORKFLOW_DECIDED);
    notification.setTitle(approved ? "Document approved" : "Document rejected");
    notification.setMessage(fileName + " was " + (approved ? "approved" : "rejected")
        + " at approval level " + approvalLevel
        + (approved || reason == null || reason.isBlank() ? "" : " — reason: " + reason));
    notification.setStatus(NotificationStatus.PENDING);
    notificationRepository.save(notification);

    boolean usableRecipient = email != null && !email.isBlank();
    boolean sent = usableRecipient && emailService.sendWorkflowDecisionEmail(
        orgId, email, fullName, documentFileName, decision, reason, approvalLevel, publishTarget);
    if (!usableRecipient) {
      log.warn("Workflow decision notice skipped for user {}: no resolvable address", userId);
    }
    notification.setStatus(sent ? NotificationStatus.SENT : NotificationStatus.FAILED);
    if (sent) {
      notification.setSentAt(LocalDateTime.now());
    }
    notificationRepository.save(notification);
  }

}
