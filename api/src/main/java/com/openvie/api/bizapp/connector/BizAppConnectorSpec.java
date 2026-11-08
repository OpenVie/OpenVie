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

package com.openvie.api.bizapp.connector;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Typed view of one {@code external_call} block's {@code params}.
 *
 * <p>Everything is optional except the protocol-specific addressing (url for
 * HTTP, brokerUrl+topic for MQTT); defaults keep the original block shape valid
 * so bundled templates installed before this layer keep running unchanged.
 */
public record BizAppConnectorSpec(
        String protocol,
        String url,
        String method,
        String bodyTemplate,
        Map<String, String> responseMap,
        String credentialRef,
        String brokerUrl,
        String topic,
        int qos,
        int maxMessages,
        Auth auth,
        Sink sink,
        Schedule schedule,
        Timeout timeout,
        Retry retry,
        List<String> egressHosts) {

    public BizAppConnectorSpec {
        responseMap = responseMap == null ? Map.of() : Map.copyOf(responseMap);
        egressHosts = egressHosts == null ? List.of() : List.copyOf(egressHosts);
    }

    /** Outbound request authentication; {@code header} is the vault default. */
    public record Auth(
            String type,
            String secretRef,
            String header,
            String timestampHeader,
            String tokenUrl,
            String scope,
            String credentialRef) {
    }

    /** Where the mapped response lands: a field update or a deduped record append. */
    public record Sink(String type, String entity, Map<String, String> mapping, List<String> dedupeKey) {
        public Sink {
            mapping = mapping == null ? Map.of() : Map.copyOf(mapping);
            dedupeKey = dedupeKey == null ? List.of() : List.copyOf(dedupeKey);
        }
    }

    /** Poll definition for scheduler-triggered connectors. */
    public record Schedule(Duration every, String entity, Map<String, Object> select, int limit) {
        public Schedule {
            select = select == null ? Map.of() : Map.copyOf(select);
        }
    }

    public record Timeout(int connectMs, int readMs) {
    }

    public record Retry(int maxAttempts, Duration backoff) {
    }
}
