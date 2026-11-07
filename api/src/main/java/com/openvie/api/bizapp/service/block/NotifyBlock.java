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

package com.openvie.api.bizapp.service.block;

import com.openvie.api.notification.api.NotificationRuntimeApi;
import com.openvie.api.tenant.api.TenantIdentityApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional email block: renders a small record summary and sends it
 * through the organization's configured channel. Recipients resolve from the
 * manifest `to` list — `creator` (the record's own author via
 * {@link TenantIdentityApi}), `record.&lt;field&gt;`, `config.&lt;key&gt;`, or an
 * explicit address.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-BLOCK")
public class NotifyBlock implements BizAppBlockExecutor {

    public static final String TYPE = "notify";

    private final NotificationRuntimeApi notificationRuntimeApi;
    private final TenantIdentityApi tenantIdentityApi;

    @Override
    public String supports() {
        return TYPE;
    }

    @Override
    public BlockResult execute(BlockSpec spec, BlockContext context) {
        List<?> recipients = spec.params() == null ? List.of() : list(spec.params().get("to"));
        String subject = String.valueOf(spec.params().getOrDefault("subject",
                "OpenVie — update in %s".formatted(context.appKind())));
        StringBuilder body = new StringBuilder()
                .append("<h1>").append(escape(String.valueOf(spec.params()
                        .getOrDefault("title", "BizApp update")))).append("</h1>")
                .append("<p>App <strong>").append(escape(context.appKind())).append("</strong>")
                .append(" · entity <strong>").append(escape(context.entity())).append("</strong></p>")
                .append(recordSummary(context.payload(), context.fieldLabels()));
        int sent = 0;
        for (Object recipient : recipients) {
            String to = resolveRecipient(String.valueOf(recipient), context);
            if (to != null && notificationRuntimeApi.sendRawEmail(context.orgId(), to, subject, body.toString())) {
                sent++;
            } else if (to == null) {
                log.warn("notify block {} skipped an unresolvable recipient {}", spec.blockId(), recipient);
            }
        }
        return new BlockResult("notify " + spec.blockId() + " -> " + sent + " email(s)");
    }

    private String resolveRecipient(String token, BlockContext context) {
        if ("creator".equalsIgnoreCase(token)) {
            // `creator` is the record owner, not whoever triggered the hook.
            UUID createdBy = context.recordCreatedBy() != null ? context.recordCreatedBy() : context.actorId();
            TenantIdentityApi.IdentitySnapshot identity = tenantIdentityApi.findUserById(createdBy);
            return identity == null ? null : identity.email();
        }
        if (token.startsWith("record.") && context.payload() != null) {
            Object val = context.payload().get(token.substring("record.".length()));
            if (val instanceof String s && s.contains("@")) {
                return s.trim();
            }
        }
        if (token.startsWith("config.") && context.config() != null) {
            Object val = context.config().get(token.substring("config.".length()));
            if (val instanceof String s && s.contains("@")) {
                return s.trim();
            }
        }
        return token.contains("@") ? token : null;
    }

    private StringBuilder recordSummary(Map<String, Object> payload, Map<String, String> fieldLabels) {
        StringBuilder rows = new StringBuilder(
                "<div style=\"margin:20px 0;background:rgba(255,255,255,0.03);border:1px solid rgba(255,255,255,0.08);border-radius:10px;padding:14px 18px;\">"
                + "<table style=\"width:100%;border-collapse:collapse;\">");
        if (payload != null) {
            payload.forEach((key, value) -> rows.append("<tr>")
                    .append("<td style=\"padding:7px 0;color:#8e9992;font-size:13px;width:38%;\">")
                    .append(escape(fieldLabels.getOrDefault(key, key == null ? "" : key))).append("</td>")
                    .append("<td style=\"padding:7px 0;color:#f2f5f0;font-size:13px;font-weight:600;\">")
                    .append(escape(String.valueOf(value))).append("</td>")
                    .append("</tr>"));
        }
        return rows.append("</table></div>");
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }
}
