package com.cacanode.api.tenant.api;

public enum TenantStatus {
    /**
     * paying, everything works normally
     * */
    ACTIVE,

    /**
     * account exists but chatbot is disabled
     * (admin deactivated it, or tenant paused subscription)
     * */
    INACTIVE,

    /**
     * violated terms or failed payment
     * chatbot stopped, admin dashboard still accessible
     * to resolve the issue
     * */
    SUSPENDED,

    /**
     * just registered, email not verified yet
     * */
    PENDING
}
