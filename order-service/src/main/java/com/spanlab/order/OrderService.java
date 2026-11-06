package com.spanlab.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.grpc.StatusRuntimeException;
import tools.jackson.databind.ObjectMapper;

// Validates a create, asks the catalog, then hands the write to OrderWriter.
// This class is not @Transactional on purpose. The gRPC call finishes
// before OrderWriter.insert opens a database transaction.
@Service
class OrderService {

    private final OrderWriter writer;
    private final CatalogLookup catalog;

    OrderService(OrderWriter writer, CatalogLookup catalog) {
        this.writer = writer;
        this.catalog = catalog;
    }

    OrderResponse create(CreateOrderRequest request, String traceparent, String tracestate) {
        if (request == null) {
            throw OrderProblem.badRequest("body is required");
        }
        String customerId = required(request.customerId(), "customerId");
        String sku = required(request.sku(), "sku");
        if (request.quantity() == null || request.quantity() < 1) {
            throw OrderProblem.badRequest("quantity must be at least 1");
        }
        BigDecimal amount = Money.parse(request.amount());
        try {
            if (!catalog.known(sku)) {
                throw OrderProblem.badRequest("sku is unknown");
            }
        } catch (StatusRuntimeException ex) {
            // The catalog process did not answer. An unknown SKU is the
            // branch above and stays a 400.
            throw OrderProblem.unavailable("catalog is unavailable");
        }
        return writer.insert(customerId, sku, request.quantity(), amount, traceparent, tracestate);
    }

    OrderResponse get(UUID id) {
        return writer.get(id);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw OrderProblem.badRequest(name + " is required");
        }
        return value.trim();
    }
}

// The transactional write. It is a separate Spring bean so @Transactional
// runs. A @Transactional method called through this.method() on the same
// class would not start a transaction.
@Service
class OrderWriter {

    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final TraceSupport traces;

    OrderWriter(OrderRepository orders, OutboxRepository outbox, ObjectMapper json, TraceSupport traces) {
        this.orders = orders;
        this.outbox = outbox;
        this.json = json;
        this.traces = traces;
    }

    @Transactional
    OrderResponse insert(
            String customerId,
            String sku,
            int quantity,
            BigDecimal amount,
            String traceparent,
            String tracestate) {
        Instant now = Instant.now();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        TraceSupport.Context trace = traces.capture(traceparent, tracestate);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("customerId", customerId);
        payload.put("sku", sku);
        payload.put("quantity", quantity);
        payload.put("amount", amount.toPlainString());

        OrderEntity order = new OrderEntity(orderId, customerId, sku, quantity, amount, now);
        OutboxEvent event = new OutboxEvent(
                eventId,
                orderId,
                json.writeValueAsString(payload),
                trace.traceparent(),
                trace.tracestate(),
                now);
        orders.save(order);
        outbox.save(event);
        // Both rows commit together when this method returns. Kafka is
        // the publisher's job, after this response has been sent.
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    OrderResponse get(UUID id) {
        return orders.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> OrderProblem.notFound("order not found"));
    }
}
