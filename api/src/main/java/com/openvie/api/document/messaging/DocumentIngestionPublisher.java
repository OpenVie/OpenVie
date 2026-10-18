package com.openvie.api.document.messaging;

public interface DocumentIngestionPublisher {
    void publish(DocumentIngestRequestedEvent event);
}
