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

package com.openvie.api.chat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Gate G-11: No hardcoded app kinds in chat module and BizAppRecordService.
 */
class GateG11HardcodedKindsTest {

    private static final Pattern FORBIDDEN_LITERALS = Pattern.compile(
            "\"(crm|booking|signature-request)\"",
            Pattern.CASE_INSENSITIVE
    );

    @Test
    void chatModuleAndRecordServiceHaveNoHardcodedPlatformKinds() throws IOException {
        Path chatRoot = Paths.get("src/main/java/com/openvie/api/chat");
        Path recordService = Paths.get("src/main/java/com/openvie/api/bizapp/service/BizAppRecordService.java");

        assertTrue(Files.exists(chatRoot), "chat directory must exist");
        assertTrue(Files.exists(recordService), "BizAppRecordService must exist");

        try (Stream<Path> chatFiles = Files.walk(chatRoot)) {
            List<Path> filesToCheck = chatFiles.filter(p -> p.toString().endsWith(".java")).toList();

            for (Path file : filesToCheck) {
                String content = Files.readString(file);
                var matcher = FORBIDDEN_LITERALS.matcher(content);
                assertTrue(!matcher.find(),
                        "File " + file + " must not contain hardcoded platform app kind literals: "
                                + (matcher.reset().find() ? matcher.group() : ""));
            }
        }

        String recordContent = Files.readString(recordService);
        var matcher = FORBIDDEN_LITERALS.matcher(recordContent);
        assertTrue(!matcher.find(), "BizAppRecordService.java must not contain hardcoded platform app kind literals");
    }
}
