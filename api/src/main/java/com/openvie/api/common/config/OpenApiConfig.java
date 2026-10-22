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

package com.openvie.api.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Configuration
@Profile({"dev", "test"})
public class OpenApiConfig {

    @Bean
    public GroupedOpenApi publicApi(@Value("${openapi.service.api-docs}") String apiDocs) {
        return GroupedOpenApi.builder()
                .group(apiDocs)
                .packagesToScan("com.openvie.api")
                .build();
    }

    @Bean
    public OpenAPI openAPI(
            @Value("${openapi.service.title}") String title,
            @Value("${openapi.service.version}") String version,
            @Value("${openapi.service.server}") String serverUrl
    ) {
        final String securitySchemeName = "bearerAuth";
        return new OpenAPI()
                .servers(List.of(new Server().url(serverUrl)))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        securitySchemeName,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                )
                )
                // No global security requirement: each controller declares
                // @SecurityRequirement(name = "bearerAuth") itself, so public
                // auth/setup endpoints show up unlocked in Swagger UI.
                .info(new Info().title(title)
                        .description("OpenVie API — multi-workspace document intelligence. "
                                + "Authenticate with a Bearer JWT from `/api/v1/auth/login` (or `sso/exchange`, `refresh`, "
                                + "`workspaces/switch`). The active workspace (tenant), user, and role are resolved from "
                                + "the token attributes on every request — no tenant header is used. Endpoints marked "
                                + "with a padlock require this scheme; public auth and setup endpoints are unlocked. "
                                + "Most failures answer with the standard error body (timestamp, status, path, error, "
                                + "message); chat resources answer with `{\"error\": {\"code\", \"message\", \"request_id\"}}`.")
                        .version(version)
                        .license(new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0"))
                );
    }

}
