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

package com.openvie.api.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

class TableOwnershipTest {
    @Test
    void runtimeSqlTouchesOnlyOwnedTables() throws IOException {
        Map<String, String> owners = new LinkedHashMap<>();
        own(owners, "ai", "model_config_versions");
        own(owners, "auth", "refresh_tokens");
        own(owners, "chat", "chat_sessions", "chat_messages", "chat_turns");
        own(owners, "common", "audit_logs", "module_event_outbox", "module_event_inbox");
        own(owners, "document", "documents", "internal_event_outbox", "internal_event_inbox");
        own(owners, "notification", "notifications", "notification_channels");
        own(owners, "tenant", "organizations", "tenants", "users", "workspace_members",
                "invitations", "knowledge_bases", "chatbots");
        own(owners, "workflow", "workflow_sla_configs");

        Path root = Path.of("src/main/java/com/openvie/api");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!relative.contains("/")) {
                    continue;
                }
                String module = relative.substring(0, relative.indexOf('/'));
                String source = stripComments(Files.readString(file));
                for (var entry : owners.entrySet()) {
                    if (entry.getValue().equals(module)) {
                        continue;
                    }
                    if (referencesTable(source, entry.getKey())) {
                        fail(relative + " references table owned by " + entry.getValue() + ": " + entry.getKey());
                    }
                }
            }
        }
    }

    private boolean referencesTable(String source, String tableName) {
        String quotedTable = Pattern.quote(tableName);
        Pattern tableAnnotation = Pattern.compile(
                "(?is)@Table\\s*\\([^)]*name\\s*=\\s*\"" + quotedTable + "\"");
        Pattern sqlReference = Pattern.compile(
                "(?is)\\b(?:from|join|insert\\s+into|update|delete\\s+from)\\s+" + quotedTable + "\\b");
        Pattern sequenceReference = Pattern.compile(
                "(?is)nextval\\s*\\(\\s*['\"]" + quotedTable + "['\"]");
        return tableAnnotation.matcher(source).find()
                || sqlReference.matcher(source).find()
                || sequenceReference.matcher(source).find();
    }

    private String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private void own(Map<String, String> owners, String owner, String... tables) {
        for (String table : tables) {
            owners.put(table, owner);
        }
    }
}
