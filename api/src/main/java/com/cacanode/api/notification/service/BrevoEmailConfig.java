package com.cacanode.api.notification.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import brevo.ApiClient;
import brevo.auth.ApiKeyAuth;
import brevoApi.TransactionalEmailsApi;

@Configuration
public class BrevoEmailConfig {

    @Bean
    public TransactionalEmailsApi transactionalEmailsApi(@Value("${spring.brevo.api-key:}") String apiKeyValue) {
        ApiClient defaultClient = brevo.Configuration.getDefaultApiClient();
        ApiKeyAuth apiKey = (ApiKeyAuth) defaultClient.getAuthentication("api-key");
        apiKey.setApiKey(apiKeyValue);
        return new TransactionalEmailsApi(defaultClient);
    }
}
