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

package com.openvie.api.bizapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Tier-3 BizApp feature configuration: global kill-switch, outbound HTTP policies,
 * AI drafting toggle, and remote catalog settings.
 */
@Component
public class BizAppProperties {

    private final boolean enabled;
    private final boolean outboundAllowHttp;
    private final boolean aiDraftingEnabled;
    private final boolean remoteCatalogEnabled;
    private final List<String> remoteCatalogHosts;

    public BizAppProperties(
            @Value("${app.bizapps.enabled:true}") boolean enabled,
            @Value("${app.bizapps.outbound.allow-http:false}") boolean outboundAllowHttp,
            @Value("${app.bizapps.ai-drafting.enabled:false}") boolean aiDraftingEnabled,
            @Value("${app.bizapps.remote-catalog.enabled:false}") boolean remoteCatalogEnabled,
            @Value("${app.bizapps.remote-catalog.hosts:}") String remoteCatalogHosts) {
        this.enabled = enabled;
        this.outboundAllowHttp = outboundAllowHttp;
        this.aiDraftingEnabled = aiDraftingEnabled;
        this.remoteCatalogEnabled = remoteCatalogEnabled;
        this.remoteCatalogHosts = remoteCatalogHosts == null || remoteCatalogHosts.isBlank()
                ? List.of()
                : Arrays.stream(remoteCatalogHosts.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** When false, every BizApp surface answers 404 like the setup claim does. */
    public boolean enabled() {
        return enabled;
    }

    public boolean outboundAllowHttp() {
        return outboundAllowHttp;
    }

    public boolean aiDraftingEnabled() {
        return aiDraftingEnabled;
    }

    public boolean remoteCatalogEnabled() {
        return remoteCatalogEnabled;
    }

    public List<String> remoteCatalogHosts() {
        return remoteCatalogHosts;
    }
}
