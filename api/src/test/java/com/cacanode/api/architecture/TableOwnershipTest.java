package com.cacanode.api.architecture;

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

        Path root = Path.of("src/main/java/com/cacanode/api");
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
