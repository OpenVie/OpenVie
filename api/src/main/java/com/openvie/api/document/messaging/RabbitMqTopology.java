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

    private RabbitMqTopology() {
    }
}
