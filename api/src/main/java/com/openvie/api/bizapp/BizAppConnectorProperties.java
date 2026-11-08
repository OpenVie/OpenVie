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
 * Connector worker/scheduler cadence, kept separate from {@link BizAppProperties}
 * so existing constructor call sites stay untouched.
 */
@Component
public class BizAppConnectorProperties {

    private final boolean enabled;
    private final long scanDelayMs;
    private final long workerDelayMs;
    private final int batchSize;

    public BizAppConnectorProperties(
            @Value("${app.bizapps.connectors.enabled:true}") boolean enabled,
            @Value("${app.bizapps.connectors.scan-delay-ms:15000}") long scanDelayMs,
            @Value("${app.bizapps.connectors.worker-delay-ms:5000}") long workerDelayMs,
            @Value("${app.bizapps.connectors.batch-size:20}") int batchSize) {
        this.enabled = enabled;
        this.scanDelayMs = scanDelayMs;
        this.workerDelayMs = workerDelayMs;
        this.batchSize = batchSize;
    }

    public boolean enabled() {
        return enabled;
    }

    public long scanDelayMs() {
        return scanDelayMs;
    }

    public long workerDelayMs() {
        return workerDelayMs;
    }

    public int batchSize() {
        return batchSize;
    }
}
