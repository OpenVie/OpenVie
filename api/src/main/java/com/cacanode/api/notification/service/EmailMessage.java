package com.cacanode.api.notification.service;

public record EmailMessage(
        String toEmail,
        String toName,
        String subject,
        String htmlContent
) {
}
