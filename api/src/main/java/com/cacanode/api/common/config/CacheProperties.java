package com.cacanode.api.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cache")
public class CacheProperties {

    private boolean enabled = false;
    private String keyPrefix = "ccn:v2";
    private int ttlJitterPercent = 10;
    private boolean businessReadEnabled = false;
    private boolean workspaceEnabled = false;
    private int workspaceTtlSeconds = 300;
    private boolean userDirectoryEnabled = false;
    private int userDirectoryTtlSeconds = 30;
    private boolean documentListEnabled = false;
    private int documentListTtlSeconds = 15;
    private boolean embeddingEnabled = false;
    private boolean retrievalEnabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public int getTtlJitterPercent() {
        return ttlJitterPercent;
    }

    public void setTtlJitterPercent(int ttlJitterPercent) {
        this.ttlJitterPercent = ttlJitterPercent;
    }

    public boolean isBusinessReadEnabled() {
        return businessReadEnabled;
    }

    public void setBusinessReadEnabled(boolean businessReadEnabled) {
        this.businessReadEnabled = businessReadEnabled;
    }

    public boolean isWorkspaceEnabled() { return workspaceEnabled; }
    public void setWorkspaceEnabled(boolean value) { this.workspaceEnabled = value; }
    public int getWorkspaceTtlSeconds() { return workspaceTtlSeconds; }
    public void setWorkspaceTtlSeconds(int value) { this.workspaceTtlSeconds = value; }
    public boolean isUserDirectoryEnabled() { return userDirectoryEnabled; }
    public void setUserDirectoryEnabled(boolean value) { this.userDirectoryEnabled = value; }
    public int getUserDirectoryTtlSeconds() { return userDirectoryTtlSeconds; }
    public void setUserDirectoryTtlSeconds(int value) { this.userDirectoryTtlSeconds = value; }
    public boolean isDocumentListEnabled() { return documentListEnabled; }
    public void setDocumentListEnabled(boolean value) { this.documentListEnabled = value; }
    public int getDocumentListTtlSeconds() { return documentListTtlSeconds; }
    public void setDocumentListTtlSeconds(int value) { this.documentListTtlSeconds = value; }

    public boolean isEmbeddingEnabled() {
        return embeddingEnabled;
    }

    public void setEmbeddingEnabled(boolean embeddingEnabled) {
        this.embeddingEnabled = embeddingEnabled;
    }

    public boolean isRetrievalEnabled() {
        return retrievalEnabled;
    }

    public void setRetrievalEnabled(boolean retrievalEnabled) {
        this.retrievalEnabled = retrievalEnabled;
    }
}
