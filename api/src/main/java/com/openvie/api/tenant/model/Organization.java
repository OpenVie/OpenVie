package com.openvie.api.tenant.model;

import com.openvie.api.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The company or institution that owns an installation. A self-hosted
 * deployment holds exactly one organization; the single-organization rule is
 * enforced by the setup service, not by a database constraint, so the hosted
 * platform can run many organizations against the same schema.
 */
@Getter
@Setter
@Entity
@Table(name = "organizations")
public class Organization extends BaseEntity {

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", unique = true, nullable = false, length = 100)
    private String slug;

    /** When true, anyone with the URL may create an account (Slack-style join). */
    @Column(name = "allow_self_registration", nullable = false)
    private boolean allowSelfRegistration = false;

    /**
     * Future TOTP-confirmation switch for first-time SSO linking.
     * FALSE (default): auto-link by verified email. TRUE: require a
     * Keycloak-OTP-authenticated session before linking. Keycloak-native TOTP;
     * no SMTP code is involved.
     */
    @Column(name = "sso_link_require_otp", nullable = false)
    private boolean ssoLinkRequireOtp = false;
}
