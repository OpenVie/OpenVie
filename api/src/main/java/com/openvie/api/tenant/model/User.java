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
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.UserStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Organization-level identity. Workspace authority is NOT stored here; it
 * lives on the {@link WorkspaceMember} row for the active workspace. The
 * request-scoped authority is re-resolved per request from membership, so a
 * stale JWT role can never outlive the membership that granted it.
 */
@Getter
@Setter
@Entity
@Table(
        name = "users",
        indexes = {
                @Index(name = "idx_users_org_id", columnList = "org_id"),
                @Index(name = "idx_users_email", columnList = "email")
        }
)
public class User extends BaseEntity implements UserDetails {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(name = "email", unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name")
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 50)
    private OrgRole role = OrgRole.MEMBER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private UserStatus status;

    /** Set when an administrator created the account with an initial password. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    /**
     * Keycloak `sub` linked by verified email. NULL means
     * password-only: the account never completed an SSO login.
     */
    @Column(name = "keycloak_subject", length = 255)
    private String keycloakSubject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by")
    private User invitedBy;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    // ─── UserDetails implementation ───────────────────────────────────────────

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;                          // email is the username
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.SUSPENDED; // suspended users are locked
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return !mustChangePassword;
    }

    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE;    // only active users can login
    }
}
