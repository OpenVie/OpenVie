package com.cacanode.api.notification.service;

public interface EmailProvider {

    String providerName();

    void send(EmailMessage message) throws EmailDeliveryException;
}
