package com.cacanode.api.tenant.model;

import com.cacanode.api.common.model.BaseEntity;
import com.cacanode.api.tenant.enums.ChatbotStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Entity
@Table(
        name = "chatbots",
        indexes = {
                @Index(name = "idx_chatbot_tenant_id", columnList = "tenant_id"),
                @Index(name = "idx_chatbot_knowledge_base_id", columnList = "knowledge_base_id"),
                @Index(name = "idx_chatbot_model_config_version_id", columnList = "model_config_version_id"),
                @Index(name = "idx_chatbot_status", columnList = "status")
        }
)
public class Chatbot extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    private KnowledgeBase knowledgeBase;

    @Column(name = "model_config_version_id", nullable = false)
    private java.util.UUID modelConfigVersionId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "default_locale", nullable = false, length = 20)
    private String defaultLocale = "vi-VN";

    @Column(name = "welcome_message", nullable = false, columnDefinition = "TEXT")
    private String welcomeMessage;

    @Column(name = "safe_instructions", nullable = false, columnDefinition = "TEXT")
    private String safeInstructions;

    @Column(name = "response_tone", nullable = false, length = 100)
    private String responseTone = "HELPFUL";

    @Column(name = "citation_policy", nullable = false, length = 100)
    private String citationPolicy = "REQUIRED_FOR_KNOWLEDGE";

    @Column(name = "general_knowledge_policy", nullable = false, length = 100)
    private String generalKnowledgePolicy = "ALLOW_WITH_DISCLOSURE";

    @Column(name = "retrieval_settings", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> retrievalSettings = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ChatbotStatus status = ChatbotStatus.ACTIVE;
}
