package com.cacanode.api.document.messaging;

public interface DocumentIngestionPublisher {
    void publish(DocumentIngestRequestedEvent event);
}
