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

/**
 * Tier-3 BizApp feature configuration: a global kill-switch plus the shared
 * empty configuration used before any app sets its own.
 */
@Component
public class BizAppProperties {

    private final boolean enabled;

    public BizAppProperties(@Value("${app.bizapps.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /** When false, every BizApp surface answers 404 like the setup claim does. */
    public boolean enabled() {
        return enabled;
    }
}
