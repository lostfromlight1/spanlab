package com.spanlab.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// The order row. status stays CREATED. The projection the consumer
// writes is a different table and is not this entity.
@Entity
@Table(name = "orders", schema = "order")
class OrderEntity {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrderEntity() {
    }

    OrderEntity(UUID id, String customerId, String sku, int quantity, BigDecimal amount, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.sku = sku;
        this.quantity = quantity;
        this.amount = amount;
        this.status = "CREATED";
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    String getCustomerId() {
        return customerId;
    }

    String getSku() {
        return sku;
    }

    int getQuantity() {
        return quantity;
    }

    BigDecimal getAmount() {
        return amount;
    }

    String getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

// The outbox row. status moves from NEW to PUBLISHED. traceparent is the
// incoming request header, copied so the publisher can rejoin that trace
// after the HTTP thread is gone. payload is jsonb, mapped as a String.
@Entity
@Table(name = "outbox_event", schema = "order")
class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false)
    private String status;

    private String traceparent;

    private String tracestate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    OutboxEvent(UUID id, UUID aggregateId, String payload, String traceparent, String tracestate, Instant createdAt) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.eventType = "order.created";
        this.payload = payload;
        this.status = "NEW";
        this.traceparent = traceparent;
        this.tracestate = tracestate;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    UUID getAggregateId() {
        return aggregateId;
    }

    String getEventType() {
        return eventType;
    }

    String getPayload() {
        return payload;
    }

    String getTraceparent() {
        return traceparent;
    }

    String getTracestate() {
        return tracestate;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void markPublished(Instant when) {
        this.status = "PUBLISHED";
        this.publishedAt = when;
    }
}
