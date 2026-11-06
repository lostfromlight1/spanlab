package com.spanlab.order;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// Reads and writes the W3C trace around the outbox.
// capture stores the incoming request header on the row.
// startPublish rebuilds a parent from that row and starts outbox.publish.
// headersFrom injects that child span onto the Kafka record, so the
// consumer's order.created span joins the same trace.
@Service
class TraceSupport {

    private final Tracer tracer;
    private final Propagator propagator;

    TraceSupport(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    Context capture(String incomingTraceparent, String incomingTracestate) {
        // Prefer the header the gateway sent. Falling back to the current
        // span covers a caller that did not send traceparent.
        if (incomingTraceparent != null && !incomingTraceparent.isBlank()) {
            return new Context(incomingTraceparent, blankToNull(incomingTracestate));
        }
        Map<String, String> carrier = new HashMap<>();
        Span current = tracer.currentSpan();
        if (current != null) {
            propagator.inject(current.context(), carrier, Map::put);
        }
        return new Context(carrier.get("traceparent"), carrier.get("tracestate"));
    }

    Span startPublish(String traceparent, String tracestate) {
        Map<String, String> carrier = new HashMap<>();
        if (traceparent != null) {
            carrier.put("traceparent", traceparent);
        }
        if (tracestate != null) {
            carrier.put("tracestate", tracestate);
        }
        return propagator.extract(carrier, Map::get).name("outbox.publish").start();
    }

    Map<String, String> headersFrom(Span span) {
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier;
    }

    Tracer.SpanInScope open(Span span) {
        return tracer.withSpan(span);
    }

    record Context(String traceparent, String tracestate) {
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

// The timer. It is its own bean so the call to OutboxBatch.publish
// crosses a Spring proxy and the transaction on publish actually starts.
@Service
class OutboxPublisher {

    private final OutboxBatch batch;

    OutboxPublisher(OutboxBatch batch) {
        this.batch = batch;
    }

    @Scheduled(fixedDelay = 1000)
    void tick() {
        batch.publish();
    }
}

// One transaction for the locked batch. A send failure throws, the
// transaction rolls back, and the rows stay NEW for the next tick.
@Service
class OutboxBatch {

    static final String TOPIC = "order-events";

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final TraceSupport traces;

    OutboxBatch(OutboxRepository outbox, KafkaTemplate<String, String> kafka, ObjectMapper json, TraceSupport traces) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.json = json;
        this.traces = traces;
    }

    @Transactional
    void publish() {
        for (OutboxEvent event : outbox.lockPending()) {
            Span span = traces.startPublish(event.getTraceparent(), event.getTracestate());
            try (Tracer.SpanInScope ignored = traces.open(span)) {
                Map<String, String> headers = traces.headersFrom(span);
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        TOPIC,
                        event.getAggregateId().toString(),
                        envelope(event));
                put(record, "traceparent", headers.get("traceparent"));
                put(record, "tracestate", headers.get("tracestate"));
                kafka.send(record).get(10, TimeUnit.SECONDS);
                // The row becomes PUBLISHED in this same transaction, after
                // Kafka has accepted the record.
                event.markPublished(Instant.now());
            } catch (Exception ex) {
                span.error(ex);
                if (ex instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("failed to publish " + event.getId(), ex);
            } finally {
                span.end();
            }
        }
    }

    private String envelope(OutboxEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", event.getId().toString());
        body.put("type", event.getEventType());
        body.put("occurredAt", event.getCreatedAt().toString());
        body.put("payload", json.readValue(event.getPayload(), new TypeReference<Map<String, Object>>() {
        }));
        return json.writeValueAsString(body);
    }

    private static void put(ProducerRecord<String, String> record, String key, String value) {
        if (value != null) {
            record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
