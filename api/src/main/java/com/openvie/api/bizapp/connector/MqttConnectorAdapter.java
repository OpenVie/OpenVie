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

import com.openvie.api.bizapp.BizAppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * MQTT connector transport: one bounded subscribe per job (connect → collect
 * {@code maxMessages} or time out → disconnect). Continuous listening belongs
 * to a future resident daemon; a job keeps the retry/audit semantics identical
 * to HTTP.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-CONNECTOR")
public class MqttConnectorAdapter implements ConnectorAdapter {

    private final MqttClientFactory clientFactory;
    private final ConnectorCredentials credentials;
    private final BizAppProperties properties;

    @Override
    public String protocol() {
        return "mqtt";
    }

    @Override
    public ConnectorResponse execute(BizAppConnectorSpec spec, ConnectorContext context) {
        String brokerUrl = spec.brokerUrl();
        String host = host(brokerUrl);
        List<String> installationHosts = installationHosts(context);
        List<String> effectiveHosts = effectiveHosts(spec, installationHosts);
        if (host == null || effectiveHosts.stream().noneMatch(host::equalsIgnoreCase)) {
            throw ConnectorException.permanent(
                    "connector host " + host + " is not allowed for this installation");
        }
        if (brokerUrl.toLowerCase(Locale.ROOT).startsWith("tcp://") && !properties.outboundAllowHttp()) {
            throw ConnectorException.permanent(
                    "mqtt brokerUrl must use ssl:// unless app.bizapps.outbound.allow-http=true");
        }

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(false);
        options.setConnectionTimeout(Math.max(1, spec.timeout().connectMs() / 1000));
        applyCredentials(spec, context, options);

        IMqttClient client = null;
        try {
            client = clientFactory.create(brokerUrl, clientId());
            client.connect(options);

            List<String> payloads = new ArrayList<>();
            CountDownLatch enough = new CountDownLatch(1);
            client.subscribe(spec.topic(), spec.qos(), (topic, message) -> {
                synchronized (payloads) {
                    payloads.add(text(message));
                    if (payloads.size() >= spec.maxMessages()) {
                        enough.countDown();
                    }
                }
            });
            try {
                enough.await(spec.timeout().readMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw ConnectorException.retryable("mqtt read interrupted");
            }
            String last;
            synchronized (payloads) {
                last = payloads.isEmpty() ? null : payloads.get(payloads.size() - 1);
            }
            if (last == null) {
                throw ConnectorException.retryable(
                        "mqtt read timed out after " + spec.timeout().readMs() + " ms");
            }
            return new ConnectorResponse(200, last, host);
        } catch (ConnectorException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ConnectorException.retryable("mqtt error: " + exception.getMessage());
        } finally {
            disconnect(client);
        }
    }

    private void applyCredentials(BizAppConnectorSpec spec, ConnectorContext context,
            MqttConnectOptions options) {
        if (spec.credentialRef() == null) {
            return;
        }
        ConnectorCredentials.Resolved resolved = credentials.resolve(
                context.orgId(), context.tenantId(), spec.credentialRef());
        if (resolved == null || !"mqtt".equals(resolved.format())) {
            throw ConnectorException.permanent(
                    "mqtt credential must use the mqtt format (username:password)");
        }
        String plaintext = resolved.plaintext() == null ? "" : resolved.plaintext();
        int separator = plaintext.indexOf(':');
        if (separator < 1) {
            throw ConnectorException.permanent("mqtt credential must be username:password");
        }
        options.setUserName(plaintext.substring(0, separator));
        options.setPassword(plaintext.substring(separator + 1).toCharArray());
    }

    private String text(MqttMessage message) {
        return new String(message.getPayload(), StandardCharsets.UTF_8);
    }

    private void disconnect(IMqttClient client) {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (Exception exception) {
            log.debug("mqtt disconnect failed: {}", exception.getMessage());
        }
        try {
            client.close();
        } catch (Exception exception) {
            log.debug("mqtt close failed: {}", exception.getMessage());
        }
    }

    private String clientId() {
        return "openvie-" + UUID.randomUUID().toString().substring(0, 13);
    }

    @SuppressWarnings("unchecked")
    private List<String> installationHosts(ConnectorContext context) {
        Object hosts = context.installationConfig().get("allowedHosts");
        if (!(hosts instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object host : list) {
            if (host != null) {
                result.add(String.valueOf(host).trim());
            }
        }
        return result;
    }

    private List<String> effectiveHosts(BizAppConnectorSpec spec, List<String> installationHosts) {
        if (spec.egressHosts().isEmpty()) {
            return installationHosts;
        }
        List<String> intersection = new ArrayList<>();
        for (String host : spec.egressHosts()) {
            if (installationHosts.stream().anyMatch(host::equalsIgnoreCase)) {
                intersection.add(host);
            }
        }
        return intersection;
    }

    private String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
