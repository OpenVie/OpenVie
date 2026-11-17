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

package com.openvie.api.tenant.service.implement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.openvie.api.common.exception.custom.UnauthorizedException;
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.TenantIdentityApi.WorkspaceSummary;
import com.openvie.api.tenant.api.TenantStatus;
import com.openvie.api.tenant.api.UserStatus;
import com.openvie.api.tenant.api.WorkspaceRole;
import com.openvie.api.tenant.enums.WorkspaceVisibility;
import com.openvie.api.tenant.model.Organization;
import com.openvie.api.tenant.model.Tenant;
import com.openvie.api.tenant.model.User;
import com.openvie.api.tenant.model.WorkspaceMember;
import com.openvie.api.tenant.repository.OrganizationRepository;
import com.openvie.api.tenant.repository.TenantRepository;
import com.openvie.api.tenant.repository.UserRepository;
import com.openvie.api.tenant.repository.WorkspaceMemberRepository;
import com.openvie.api.tenant.service.TenantUserManagementService;

/** Regression coverage for workspace entries exposed by the auth API. */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TenantModuleApiImplIntegrationTest {

    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceMemberRepository memberRepository;

    private TenantModuleApiImpl service;

    @BeforeEach
    void setUp() {
        service = new TenantModuleApiImpl(
                mock(PasswordEncoder.class),
                userRepository,
                tenantRepository,
                memberRepository,
                mock(TenantUserManagementService.class));
    }

    @Test
    void listMembershipsHidesArchivedWorkspacesButKeepsActiveAndInactiveAccessible() {
        Organization organization = organization("workspace-list-org");
        User user = user(organization, "member@workspace-list.test");
        Tenant active = workspace(organization, "active-workspace", TenantStatus.ACTIVE);
        Tenant inactive = workspace(organization, "inactive-workspace", TenantStatus.INACTIVE);
        Tenant archived = workspace(organization, "archived-workspace", TenantStatus.ACTIVE);
        membership(user, active);
        membership(user, inactive);
        membership(user, archived);

        archived.setStatus(TenantStatus.ARCHIVED);
        tenantRepository.saveAndFlush(archived);

        Set<UUID> listedWorkspaceIds = service.listMemberships(user.getId()).stream()
                .map(WorkspaceSummary::workspaceId)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of(active.getId(), inactive.getId()), listedWorkspaceIds);
        assertFalse(listedWorkspaceIds.contains(archived.getId()));
        assertEquals(active.getId(), service.requireMembership(user.getId(), active.getId()).workspaceId());
        assertEquals(inactive.getId(), service.requireMembership(user.getId(), inactive.getId()).workspaceId());
        assertThrows(UnauthorizedException.class,
                () -> service.requireMembership(user.getId(), archived.getId()));
    }

    private Organization organization(String slug) {
        Organization organization = new Organization();
        organization.setName(slug);
        organization.setSlug(slug);
        return organizationRepository.saveAndFlush(organization);
    }

    private User user(Organization organization, String email) {
        User user = new User();
        user.setOrganization(organization);
        user.setEmail(email);
        user.setPasswordHash("not-used-by-this-test");
        user.setFullName("Workspace List Member");
        user.setRole(OrgRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.saveAndFlush(user);
    }

    private Tenant workspace(Organization organization, String slug, TenantStatus status) {
        Tenant workspace = new Tenant();
        workspace.setOrganization(organization);
        workspace.setName(slug);
        workspace.setSlug(slug);
        workspace.setStatus(status);
        workspace.setVisibility(WorkspaceVisibility.PUBLIC);
        return tenantRepository.saveAndFlush(workspace);
    }

    private void membership(User user, Tenant workspace) {
        WorkspaceMember membership = new WorkspaceMember();
        membership.setUser(user);
        membership.setWorkspace(workspace);
        membership.setRole(WorkspaceRole.MEMBER);
        memberRepository.saveAndFlush(membership);
    }
}
