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

package com.openvie.api.chat.query;

import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.MarketplaceTemplateSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Invariant I-15: System operates with ANY custom app kind without hardcoded kind lists.
 */
class GenericAppKindResolutionTest {

    @Test
    void customAppKindResolvedViaNameAndEntityAliases() {
        InstalledAppSummary customApp = new InstalledAppSummary(
                UUID.randomUUID(),
                "asset-registry",
                "Quản lý tài sản",
                1,
                "Theo dõi tài sản thiết bị văn phòng",
                List.of("assets"),
                List.of("registry:assets"),
                "ACTIVE",
                List.of(new BizAppRuntimeApi.EntityDetail(
                        "assets", "Tài sản",
                        List.of(new BizAppRuntimeApi.FieldDetail("name", "string", true, "Tên tài sản"))
                ))
        );

        Map<String, String> aliases = BizAppIntentRouter.buildAliasToKindMap(List.of(customApp), List.of());
        Set<String> knownKinds = Set.of("asset-registry");

        // 1. Direct kind
        assertEquals("asset-registry", BizAppIntentRouter.resolveAppKind("asset-registry", knownKinds, aliases));

        // 2. Full Vietnamese name with diacritics
        assertEquals("asset-registry", BizAppIntentRouter.resolveAppKind("Tôi muốn xem ứng dụng Quản lý tài sản", knownKinds, aliases));

        // 3. Unaccented Vietnamese name
        assertEquals("asset-registry", BizAppIntentRouter.resolveAppKind("Thêm thiết bị vào quan ly tai san", knownKinds, aliases));

        // 4. Entity label alias
        assertEquals("asset-registry", BizAppIntentRouter.resolveAppKind("Thêm tài sản mới", knownKinds, aliases));

        // 5. Unknown app resolves to null
        assertNull(BizAppIntentRouter.resolveAppKind("Ứng dụng không tồn tại xyz", knownKinds, aliases));
    }

    @Test
    void deterministicGateMatchesGenericWriteVerbsAndDynamicAliases() {
        InstalledAppSummary warehouseApp = new InstalledAppSummary(
                UUID.randomUUID(), "warehouse-mgr", "Quản lý kho", 1, "", List.of("items"), List.of(), "ACTIVE"
        );
        Map<String, String> aliases = BizAppIntentRouter.buildAliasToKindMap(List.of(warehouseApp), List.of());

        // Generic write verb
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("Thêm bản ghi mới", aliases.keySet()));
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("Cập nhật thông tin", aliases.keySet()));
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("Xoá tài liệu khỏi kho", aliases.keySet()));

        // Generic app vocabulary
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("Xem danh mục ứng dụng", aliases.keySet()));

        // Regular document question without app verbs does not match
        assertFalse(BizAppIntentRouter.matchesDeterministicGate("Chính sách nghỉ phép năm của nhân viên thế nào?", aliases.keySet()));
    }
}
