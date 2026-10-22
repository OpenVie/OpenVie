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

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openvie.api.document.messaging.RabbitMqTopology;

@Configuration
public class RabbitMQConfig {

    @Bean
    public TopicExchange ingestionExchange() {
        return ExchangeBuilder.topicExchange(RabbitMqTopology.INGESTION_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return ExchangeBuilder.topicExchange(RabbitMqTopology.DEAD_LETTER_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue documentIngestionQueue() {
        return QueueBuilder.durable(RabbitMqTopology.INGESTION_QUEUE)
                .deadLetterExchange(RabbitMqTopology.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    public Queue documentStatusQueue() {
        return QueueBuilder.durable(RabbitMqTopology.STATUS_QUEUE)
                .deadLetterExchange(RabbitMqTopology.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    public Queue documentIngestionDeadLetterQueue() {
        return QueueBuilder.durable(RabbitMqTopology.INGESTION_DLQ)
                .build();
    }

    @Bean
    public Queue documentStatusDeadLetterQueue() {
        return QueueBuilder.durable(RabbitMqTopology.STATUS_DLQ).build();
    }

    @Bean
    public Binding documentIngestionBinding(Queue documentIngestionQueue, TopicExchange ingestionExchange) {
        return BindingBuilder.bind(documentIngestionQueue)
                .to(ingestionExchange)
                .with(RabbitMqTopology.INGEST_REQUESTED);
    }

    @Bean
    public Binding documentStatusProcessingBinding(Queue documentStatusQueue, TopicExchange ingestionExchange) {
        return statusBinding(documentStatusQueue, ingestionExchange, RabbitMqTopology.INGEST_PROCESSING);
    }

    @Bean
    public Binding documentStatusCompletedBinding(Queue documentStatusQueue, TopicExchange ingestionExchange) {
        return statusBinding(documentStatusQueue, ingestionExchange, RabbitMqTopology.INGEST_COMPLETED);
    }

    @Bean
    public Binding documentStatusFailedBinding(Queue documentStatusQueue, TopicExchange ingestionExchange) {
        return statusBinding(documentStatusQueue, ingestionExchange, RabbitMqTopology.INGEST_FAILED);
    }

    @Bean
    public Binding documentIngestionDeadLetterBinding(Queue documentIngestionDeadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(documentIngestionDeadLetterQueue)
                .to(deadLetterExchange)
                .with("#");
    }

    @Bean
    public Binding documentStatusDeadLetterBinding(
            Queue documentStatusDeadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(documentStatusDeadLetterQueue)
                .to(deadLetterExchange)
                .with("document.ingest.*");
    }

    private Binding statusBinding(Queue queue, TopicExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(routingKey);
    }
}
