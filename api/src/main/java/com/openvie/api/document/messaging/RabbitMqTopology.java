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

package com.openvie.api.document.messaging;

public final class RabbitMqTopology {
    public static final String INGESTION_EXCHANGE = "openvie.ingestion.v1";
    public static final String DEAD_LETTER_EXCHANGE = "openvie.dlx.v1";
    public static final String INGESTION_QUEUE = "openvie.document.ingestion.v1";
    public static final String STATUS_QUEUE = "openvie.document.status.v1";
    public static final String INGESTION_DLQ = "openvie.document.ingestion.dlq.v1";
    public static final String STATUS_DLQ = "openvie.document.status.dlq.v1";
    public static final String INGEST_REQUESTED = "document.ingest.requested";
    public static final String INGEST_PROCESSING = "document.ingest.processing";
    public static final String INGEST_COMPLETED = "document.ingest.completed";
    public static final String INGEST_FAILED = "document.ingest.failed";
    public static final String LAKEHOUSE_CDC_QUEUE = "openvie.lakehouse.cdc.v1";
    public static final String CDC_EVENT_ROUTING_KEY = "cdc.events.#";
    public static final String CDC_EVENT_PREFIX = "cdc.events.";

    private RabbitMqTopology() {
    }
}
