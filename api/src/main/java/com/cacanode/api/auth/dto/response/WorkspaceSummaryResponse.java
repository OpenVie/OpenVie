package com.cacanode.api.auth.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class WorkspaceSummaryResponse {
    private UUID id;
    private String name;
    private String slug;
    private String role;
    private boolean isDefault;
}
