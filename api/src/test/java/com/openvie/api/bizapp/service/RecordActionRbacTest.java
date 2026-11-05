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

package com.openvie.api.bizapp.service;

import com.openvie.api.bizapp.dto.BizAppManifest;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Task W7.5 and Invariant I-17: Role-based RBAC according to manifest declarations.
 */
class RecordActionRbacTest {

    private final BizAppRecordService recordService = new BizAppRecordService(
            null, null, null, null, null, null, null, null
    );

    @Test
    void defaultRbacRestrictsWriteToAdmins() {
        Manifest defaultManifest = new Manifest(
                "bizapp.v1", "app", new BizAppManifest.Localized("App", "App"),
                1, "icon", Map.of(), List.of(), List.of(), null
        );

        assertTrue(recordService.canWrite(defaultManifest, "ORG_OWNER"));
        assertTrue(recordService.canWrite(defaultManifest, "WORKSPACE_ADMIN"));
        assertFalse(recordService.canWrite(defaultManifest, "MEMBER"));

        assertTrue(recordService.canDelete(defaultManifest, "ORG_OWNER"));
        assertTrue(recordService.canDelete(defaultManifest, "WORKSPACE_ADMIN"));
        assertFalse(recordService.canDelete(defaultManifest, "MEMBER"));

        assertTrue(recordService.canApprove(defaultManifest, "ORG_OWNER"));
        assertTrue(recordService.canApprove(defaultManifest, "WORKSPACE_ADMIN"));
        assertFalse(recordService.canApprove(defaultManifest, "MEMBER"));
    }

    @Test
    void explicitMemberRbacGrantsPermissionToMemberRole() {
        Manifest memberWritableManifest = new Manifest(
                "bizapp.v1", "app", new BizAppManifest.Localized("App", "App"),
                1, "icon",
                Map.of(
                        "write", List.of("MEMBER"),
                        "delete", List.of("WORKSPACE_ADMIN"),
                        "approve", List.of("ORG_OWNER")
                ),
                List.of(), List.of(), null
        );

        // Member can write because MEMBER is in rbac.write
        assertTrue(recordService.canWrite(memberWritableManifest, "MEMBER"));
        assertTrue(recordService.canWrite(memberWritableManifest, "ORG_OWNER"));

        // Member cannot delete because MEMBER is not in rbac.delete
        assertFalse(recordService.canDelete(memberWritableManifest, "MEMBER"));
        assertTrue(recordService.canDelete(memberWritableManifest, "WORKSPACE_ADMIN"));
        assertTrue(recordService.canDelete(memberWritableManifest, "ORG_OWNER"));

        // Member cannot approve
        assertFalse(recordService.canApprove(memberWritableManifest, "MEMBER"));
        assertTrue(recordService.canApprove(memberWritableManifest, "WORKSPACE_ADMIN"));
        assertTrue(recordService.canApprove(memberWritableManifest, "ORG_OWNER"));
    }
}
