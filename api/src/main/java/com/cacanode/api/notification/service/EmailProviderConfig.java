package com.cacanode.api.notification.service;

import com.sendgrid.SendGrid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Email provider wiring for the retained auth/invitation/document mail path.
 * Credentials are read from configuration only; {@link MailConfigurationValidator}
 * fails startup when the selected provider is missing its key.
 */
@Configuration
public class EmailProviderConfig {

    @Bean
    public SendGrid sendGrid(@Value("${spring.sendgrid.api-key:}") String sendgridApiKey) {
        return new SendGrid(sendgridApiKey);
    }
}
