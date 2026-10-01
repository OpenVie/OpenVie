package com.cacanode.api.document.messaging;

public final class RabbitMqTopology {
    public static final String INGESTION_EXCHANGE = "cacanode.ingestion.v1";
    public static final String DEAD_LETTER_EXCHANGE = "cacanode.dlx.v1";
    public static final String INGESTION_QUEUE = "cacanode.document.ingestion.v1";
    public static final String STATUS_QUEUE = "cacanode.document.status.v1";
    public static final String INGESTION_DLQ = "cacanode.document.ingestion.dlq.v1";
    public static final String STATUS_DLQ = "cacanode.document.status.dlq.v1";
    public static final String INGEST_REQUESTED = "document.ingest.requested";
    public static final String INGEST_PROCESSING = "document.ingest.processing";
    public static final String INGEST_COMPLETED = "document.ingest.completed";
    public static final String INGEST_FAILED = "document.ingest.failed";

    private RabbitMqTopology() {
    }
}
