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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.common.exception.custom.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class BizAppConnectorSpecParserTest {

    private final BizAppConnectorSpecParser parser = new BizAppConnectorSpecParser();

    private static Block block(Map<String, Object> params) {
        return new Block("readTemp", "external_call", params);
    }

    @Test
    void appliesContractDefaults() {
        BizAppConnectorSpec spec = parser.parse(block(Map.of(
                "url", "https://ot-gateway.test/temp",
                "credentialRef", "gateway-key")));

        assertEquals("http", spec.protocol());
        assertEquals("POST", spec.method());
        assertEquals(1, spec.qos());
        assertEquals(1, spec.maxMessages());
        assertEquals(10_000, spec.timeout().connectMs());
        assertEquals(15_000, spec.timeout().readMs());
        assertEquals(3, spec.retry().maxAttempts());
        assertEquals(Duration.ofSeconds(10), spec.retry().backoff());
        assertTrue(spec.responseMap().isEmpty());
        assertNull(spec.sink());
        assertNull(spec.schedule());
        assertNull(spec.auth());
        assertTrue(spec.egressHosts().isEmpty());
    }

    @Test
    void rejectsUnknownProtocol() {
        BadRequestException error = assertThrows(BadRequestException.class, () -> parser.parse(block(
                Map.of("protocol", "coap", "url", "https://ot-gateway.test"))));
        assertEquals("external_call protocol must be http or mqtt", error.getMessage());
    }

    @Test
    void requiresUrlForHttp() {
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> parser.parse(block(Map.of())));
        assertEquals("external_call (http) requires url", error.getMessage());
    }

    @Test
    void requiresBrokerAndTopicForMqtt() {
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> parser.parse(block(Map.of("protocol", "mqtt", "topic", "ot/dev/1"))));
        assertEquals("external_call (mqtt) requires brokerUrl and topic", error.getMessage());
    }

    @Test
    void rejectsNonPointerResponseMap() {
        BadRequestException error = assertThrows(BadRequestException.class, () -> parser.parse(
                block(Map.of("url", "https://ot-gateway.test", "responseMap", Map.of("id", "id")))));
        assertEquals("external_call responseMap pointers must start with /", error.getMessage());
    }

    @Test
    void rejectsNonPointerSinkMapping() {
        BadRequestException error = assertThrows(BadRequestException.class, () -> parser.parse(
                block(Map.of("url", "https://ot-gateway.test", "sink", Map.of(
                        "type", "field", "mapping", Map.of("temperature", "temp"))))));
        assertEquals("external_call sink.mapping pointers must start with /", error.getMessage());
    }

    @Test
    void rejectsDedupeKeyOutsideMapping() {
        Map<String, Object> sink = Map.of(
                "type", "record",
                "entity", "telemetry",
                "mapping", Map.of("deviceId", "/device"),
                "dedupeKey", List.of("deviceId", "ts"));
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> parser.parse(block(Map.of("url", "https://ot-gateway.test", "sink", sink))));
        assertEquals("external_call sink.dedupeKey must be a subset of sink.mapping keys",
                error.getMessage());
    }

    @Test
    void enforcesScheduleBounds() {
        BadRequestException tooFast = assertThrows(BadRequestException.class, () -> parser.parse(block(
                Map.of("url", "https://ot-gateway.test", "schedule", Map.of(
                        "every", "PT1S", "entity", "devices")))));
        assertEquals("external_call schedule.every must be between PT30S and P7D",
                tooFast.getMessage());

        BadRequestException tooSlow = assertThrows(BadRequestException.class, () -> parser.parse(block(
                Map.of("url", "https://ot-gateway.test", "schedule", Map.of(
                        "every", "P8D", "entity", "devices")))));
        assertEquals("external_call schedule.every must be between PT30S and P7D",
                tooSlow.getMessage());

        BizAppConnectorSpec spec = parser.parse(block(Map.of(
                "url", "https://ot-gateway.test",
                "schedule", Map.of("every", "PT1M", "entity", "devices"))));
        assertEquals(Duration.ofMinutes(1), spec.schedule().every());
        assertEquals(50, spec.schedule().limit());
    }

    @Test
    void rejectsUnknownAuthType() {
        BadRequestException error = assertThrows(BadRequestException.class, () -> parser.parse(block(
                Map.of("url", "https://ot-gateway.test", "auth", Map.of("type", "mtls")))));
        assertEquals("external_call auth.type must be header, hmac or oauth2", error.getMessage());
    }

    @Test
    void enforcesTimeoutBounds() {
        BadRequestException error = assertThrows(BadRequestException.class, () -> parser.parse(block(
                Map.of("url", "https://ot-gateway.test",
                        "timeout", Map.of("connectMs", 50, "readMs", 15_000)))));
        assertEquals("external_call timeout values must be 100..60000 ms", error.getMessage());
    }

    @Test
    void enforcesRetryAttemptBudget() {
        BadRequestException error = assertThrows(BadRequestException.class, () -> parser.parse(block(
                Map.of("url", "https://ot-gateway.test",
                        "retry", Map.of("maxAttempts", 11)))));
        assertEquals("external_call retry.maxAttempts must be 1..10", error.getMessage());
    }

    @Test
    void parsesAFullMqttRecordSinkDeclaration() {
        Map<String, Object> params = new HashMap<>();
        params.put("protocol", "mqtt");
        params.put("brokerUrl", "ssl://broker.test:8883");
        params.put("topic", "ot/dev/1");
        params.put("qos", 0);
        params.put("maxMessages", 5);
        params.put("credentialRef", "mqtt-user");
        params.put("timeout", Map.of("connectMs", 2_000, "readMs", 3_000));
        params.put("retry", Map.of("maxAttempts", 2, "backoff", "PT5S"));
        params.put("sink", Map.of(
                "type", "record",
                "entity", "telemetry",
                "mapping", Map.of("deviceId", "/device", "temperature", "/temp"),
                "dedupeKey", List.of("deviceId")));
        params.put("schedule", Map.of(
                "every", "PT5M", "entity", "devices",
                "select", Map.of("enabled", true), "limit", 10));
        params.put("egress", Map.of("allowedHosts", List.of("broker.test")));

        BizAppConnectorSpec spec = parser.parse(block(params));

        assertEquals("mqtt", spec.protocol());
        assertEquals("ssl://broker.test:8883", spec.brokerUrl());
        assertEquals("ot/dev/1", spec.topic());
        assertEquals(0, spec.qos());
        assertEquals(5, spec.maxMessages());
        assertEquals(2_000, spec.timeout().connectMs());
        assertEquals(Duration.ofSeconds(5), spec.retry().backoff());
        assertEquals("record", spec.sink().type());
        assertEquals(List.of("deviceId"), spec.sink().dedupeKey());
        assertEquals(Duration.ofMinutes(5), spec.schedule().every());
        assertEquals(Map.of("enabled", true), spec.schedule().select());
        assertEquals(List.of("broker.test"), spec.egressHosts());
    }

    @Test
    void parsesHmacAndOauth2Auth() {
        BizAppConnectorSpec hmac = parser.parse(block(Map.of(
                "url", "https://ot-gateway.test",
                "credentialRef", "gateway-key",
                "auth", Map.of("type", "hmac", "secretRef", "signing-secret"))));
        assertEquals("hmac", hmac.auth().type());
        assertEquals("X-OpenVie-Signature", hmac.auth().header());
        assertEquals("X-OpenVie-Timestamp", hmac.auth().timestampHeader());

        BizAppConnectorSpec oauth = parser.parse(block(Map.of(
                "url", "https://ot-gateway.test",
                "auth", Map.of("type", "oauth2",
                        "tokenUrl", "https://idp.test/token",
                        "credentialRef", "idp-client"))));
        assertEquals("oauth2", oauth.auth().type());
        assertEquals("https://idp.test/token", oauth.auth().tokenUrl());
    }
}
