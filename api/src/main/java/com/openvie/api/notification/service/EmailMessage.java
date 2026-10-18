package com.openvie.api.notification.service;

public record EmailMessage(
        String toEmail,
        String toName,
        String subject,
        String htmlContent
) {
}
