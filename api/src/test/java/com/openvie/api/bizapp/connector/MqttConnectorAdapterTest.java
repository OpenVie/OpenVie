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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class MqttConnectorAdapterTest {

    private MqttClientFactory clientFactory;
    private ConnectorCredentials credentials;
    private IMqttClient client;

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clientFactory = mock(MqttClientFactory.class);
        credentials = mock(ConnectorCredentials.class);
        client = mock(IMqttClient.class);
    }

    private MqttConnectorAdapter adapter(boolean allowHttp) {
        return new MqttConnectorAdapter(clientFactory, credentials,
                new BizAppProperties(true, allowHttp, false, false, ""));
    }

    private ConnectorContext context() {
        return new ConnectorContext(orgId, tenantId, UUID.randomUUID(), UUID.randomUUID(), jobId,
                "ot-telemetry", "devices", recordId, Map.of(),
                Map.of("allowedHosts", List.of("broker.test")));
    }

    private static BizAppConnectorSpec spec(Map<String, Object> params) {
        return new BizAppConnectorSpecParser().parse(new Block("readTemp", "external_call", params));
    }

    private static BizAppConnectorSpec secureSpec() {
        return spec(Map.of(
                "protocol", "mqtt",
                "brokerUrl", "ssl://broker.test:8883",
                "topic", "ot/dev/1",
                "qos", 1,
                "maxMessages", 2,
                "credentialRef", "broker-user"));
    }

    @Test
    void rejectsTcpBrokersUnlessAllowHttpIsEnabled() throws Exception {
        var plain = spec(Map.of(
                "protocol", "mqtt",
                "brokerUrl", "tcp://broker.test:1883",
                "topic", "ot/dev/1"));

        ConnectorException error = assertThrows(ConnectorException.class,
                () -> adapter(false).execute(plain, context()));
        assertFalse(error.retryable());
        assertTrue(error.getMessage().contains("ssl://"));

        when(clientFactory.create(anyString(), anyString())).thenReturn(client);
        doAnswer(invocation -> {
            IMqttMessageListener listener = invocation.getArgument(2);
            listener.messageArrived("ot/dev/1", new MqttMessage("{}".getBytes(StandardCharsets.UTF_8)));
            return null;
        }).when(client).subscribe(eq("ot/dev/1"), eq(1), any(IMqttMessageListener.class));

        assertEquals(200, adapter(true).execute(plain, context()).status());
    }

    @Test
    void connectsWithCredentialsAndReturnsTheLastCollectedMessage() throws Exception {
        when(credentials.resolve(orgId, tenantId, "broker-user"))
                .thenReturn(new ConnectorCredentials.Resolved("mqtt", "user:pass"));
        when(clientFactory.create(eq("ssl://broker.test:8883"), anyString())).thenReturn(client);
        doAnswer(invocation -> {
            IMqttMessageListener listener = invocation.getArgument(2);
            listener.messageArrived("ot/dev/1",
                    new MqttMessage("{\"temp\":1}".getBytes(StandardCharsets.UTF_8)));
            listener.messageArrived("ot/dev/1",
                    new MqttMessage("{\"temp\":2}".getBytes(StandardCharsets.UTF_8)));
            return null;
        }).when(client).subscribe(eq("ot/dev/1"), eq(1), any(IMqttMessageListener.class));
        when(client.isConnected()).thenReturn(true);

        ConnectorResponse response = adapter(false).execute(secureSpec(), context());

        assertEquals(200, response.status());
        assertEquals("{\"temp\":2}", response.body());
        assertEquals("broker.test", response.host());

        ArgumentCaptor<MqttConnectOptions> options = ArgumentCaptor.forClass(MqttConnectOptions.class);
        verify(client).connect(options.capture());
        assertTrue(options.getValue().isCleanSession());
        assertFalse(options.getValue().isAutomaticReconnect());
        assertEquals("user", options.getValue().getUserName());
        assertArrayEquals("pass".toCharArray(), options.getValue().getPassword());
        verify(client).disconnect();
        verify(client).close();
        ArgumentCaptor<String> clientId = ArgumentCaptor.forClass(String.class);
        verify(clientFactory).create(eq("ssl://broker.test:8883"), clientId.capture());
        assertTrue(clientId.getValue().startsWith("openvie-"));
    }

    @Test
    void timesOutRetryablyWhenNoMessageArrives() throws Exception {
        when(clientFactory.create(anyString(), anyString())).thenReturn(client);
        when(client.isConnected()).thenReturn(true);
        var quiet = spec(Map.of(
                "protocol", "mqtt",
                "brokerUrl", "ssl://broker.test:8883",
                "topic", "ot/dev/1",
                "timeout", Map.of("connectMs", 1_000, "readMs", 100)));

        ConnectorException error = assertThrows(ConnectorException.class,
                () -> adapter(false).execute(quiet, context()));
        assertTrue(error.retryable());
        assertEquals("mqtt read timed out after 100 ms", error.getMessage());
        verify(client).close();
    }

    @Test
    void rejectsNonMqttCredentialFormats() {
        when(credentials.resolve(any(), any(), any()))
                .thenReturn(new ConnectorCredentials.Resolved("api_key", "secret"));

        ConnectorException error = assertThrows(ConnectorException.class,
                () -> adapter(false).execute(secureSpec(), context()));
        assertFalse(error.retryable());
        assertTrue(error.getMessage().contains("mqtt format"));
    }

    @Test
    void rejectsBrokersOutsideTheInstallationAllowlist() {
        var foreign = spec(Map.of(
                "protocol", "mqtt",
                "brokerUrl", "ssl://evil.test:8883",
                "topic", "ot/dev/1"));

        ConnectorException error = assertThrows(ConnectorException.class,
                () -> adapter(false).execute(foreign, context()));
        assertFalse(error.retryable());
        assertTrue(error.getMessage().contains("evil.test"));
    }
}
