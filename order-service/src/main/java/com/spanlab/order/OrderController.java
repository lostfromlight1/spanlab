package com.spanlab.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// HTTP API for orders. The gateway is the caller. traceparent and
// tracestate are optional so a direct call still works; when the
// gateway sent them, they are stored on the outbox row.
@RestController
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/api/v1/orders")
    ResponseEntity<OrderResponse> create(
            @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "traceparent", required = false) String traceparent,
            @RequestHeader(value = "tracestate", required = false) String tracestate) {
        OrderResponse body = orders.create(request, traceparent, tracestate);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/api/v1/orders/{id}")
    OrderResponse get(@PathVariable UUID id) {
        return orders.get(id);
    }
}

// The JSON body. amount is a decimal string, matching numeric(12,2).
record CreateOrderRequest(String customerId, String sku, Integer quantity, String amount) {
}

record OrderResponse(
        UUID id,
        String customerId,
        String sku,
        int quantity,
        String amount,
        String status,
        Instant createdAt) {

    static OrderResponse from(OrderEntity order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getSku(),
                order.getQuantity(),
                order.getAmount().setScale(2).toPlainString(),
                order.getStatus(),
                order.getCreatedAt());
    }
}

// A domain error with an HTTP status. ApiErrors turns it into problem+json.
class OrderProblem extends RuntimeException {

    final HttpStatus status;

    OrderProblem(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    static OrderProblem badRequest(String detail) {
        return new OrderProblem(HttpStatus.BAD_REQUEST, detail);
    }

    static OrderProblem notFound(String detail) {
        return new OrderProblem(HttpStatus.NOT_FOUND, detail);
    }

    static OrderProblem unavailable(String detail) {
        return new OrderProblem(HttpStatus.SERVICE_UNAVAILABLE, detail);
    }
}

// Maps OrderProblem onto RFC 7807 problem+json. The title is the reason
// phrase, so 400 is "Bad Request" and 503 is "Service Unavailable".
@RestControllerAdvice
class ApiErrors {

    @ExceptionHandler(OrderProblem.class)
    ProblemDetail handle(OrderProblem problem) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(problem.status, problem.getMessage());
        body.setTitle(problem.status.getReasonPhrase());
        return body;
    }
}

// Parses the JSON amount. Scale is at most 2 because the column is numeric(12,2).
final class Money {

    private Money() {
    }

    static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw OrderProblem.badRequest("amount is required");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw OrderProblem.badRequest("amount must be a decimal string");
        }
        if (amount.scale() > 2) {
            throw OrderProblem.badRequest("amount has more than two decimal places");
        }
        if (amount.signum() < 0) {
            throw OrderProblem.badRequest("amount must not be negative");
        }
        return amount.setScale(2);
    }
}
