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
