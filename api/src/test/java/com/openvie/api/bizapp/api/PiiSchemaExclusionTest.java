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

package com.openvie.api.bizapp.api;

import com.openvie.api.bizapp.dto.BizAppManifest;
import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.service.BizAppSchemaDigest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Gate G-14 and Invariant I-14: PII fields are never exposed to LLM.
 */
class PiiSchemaExclusionTest {

    @Test
    void piiFieldsAreExcludedFromSchemaDigest() {
        Field idNumber = new Field(
                "idNumber", "string", null, true, 50, null, List.of(), true,
                new BizAppManifest.Localized("Số CCCD", "National ID")
        );
        Field fullName = new Field(
                "fullName", "string", null, true, 100, null, List.of(), false,
                new BizAppManifest.Localized("Họ và tên", "Full Name")
        );
        Entity employees = new Entity("employees", new BizAppManifest.Localized("Nhân viên", "Employees"), List.of(idNumber, fullName), Map.of());
        Manifest manifest = new Manifest("bizapp.v1", "hr-app", new BizAppManifest.Localized("Nhân sự", "HR App"), 1, "icon", Map.of(), List.of(employees), List.of(), null);

        var digest = BizAppSchemaDigest.digest(manifest, "vi-VN");
        assertNotNull(digest);
        assertEquals("hr-app", digest.kind());
        assertEquals(1, digest.entities().size());

        var entity = digest.entities().get(0);
        assertEquals("employees", entity.name());
        assertEquals(1, entity.fields().size(), "PII field must be stripped");
        assertEquals("fullName", entity.fields().get(0).name());
        assertTrue(entity.fields().stream().noneMatch(f -> "idNumber".equals(f.name())), "idNumber (pii:true) must not be in schema");
    }

    @Test
    void digestSortsEntitiesAndFieldsDeterministically() {
        Field b = new Field("b_field", "string", false, 10, null, List.of());
        Field a = new Field("a_field", "string", false, 10, null, List.of());
        Entity zEntity = new Entity("z_entity", new BizAppManifest.Localized("Z", "Z"), List.of(b, a), Map.of());
        Entity aEntity = new Entity("a_entity", new BizAppManifest.Localized("A", "A"), List.of(b, a), Map.of());
        Manifest manifest = new Manifest("bizapp.v1", "test-app", new BizAppManifest.Localized("Test", "Test"), 1, "icon", Map.of(), List.of(zEntity, aEntity), List.of(), null);

        var digest1 = BizAppSchemaDigest.digest(manifest, "en-US");
        var digest2 = BizAppSchemaDigest.digest(manifest, "en-US");

        assertEquals("a_entity", digest1.entities().get(0).name());
        assertEquals("z_entity", digest1.entities().get(1).name());
        assertEquals("a_field", digest1.entities().get(0).fields().get(0).name());
        assertEquals("b_field", digest1.entities().get(0).fields().get(1).name());
        assertEquals(digest1, digest2, "Digests must be deterministically identical across runs");
    }
}
