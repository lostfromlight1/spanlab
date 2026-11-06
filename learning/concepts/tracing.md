# Tracing

A trace is one id shared by every span that belongs to the same order. A span is one timed piece of work: the gateway handling the HTTP call, the order service writing the row, the publisher sending to Kafka, the consumer applying the record.

Jaeger stores those spans. The UI is http://localhost:16686. Each process exports over OTLP HTTP to `http://jaeger:4318/v1/traces`. Sampling is 1.0, so every order is kept.

## The header

`traceparent` is a W3C header. It looks like:

```text
00-e90dd9657569f2600f1c17915eb4aef9-5fd2f9f14e40c53c-01
```

The middle hex string is the trace id. The next hex string is the span id of the caller. `tracestate` is an optional vendor list. The gateway injects these headers on the call to the order service. The order service reads them in [OrderController.java](../../order-service/src/main/java/com/spanlab/order/OrderController.java).

## Why the outbox row stores the header

The publisher runs on a timer, after the request thread is gone. A span started from that timer with no parent would be a new trace.

`TraceSupport.capture` copies the incoming `traceparent` when the header is present. That exact string is stored on `"order".outbox_event`. When the publisher wakes, `startPublish` rebuilds the parent from those columns and starts a span named `outbox.publish`. The Kafka headers are injected from that span, so they are the child context, not a byte-for-byte copy of the stored request header.

The consumer extracts those Kafka headers and starts `order.created` as a child of `outbox.publish`. In Jaeger you see one trace id and this shape:

```text
gateway POST /api/v1/orders
  gateway HTTP POST
    order-service http post /api/v1/orders
      outbox.publish
        order.created
```

The stored column matches the request that created the order. The Kafka header matches the publish span. Both share the trace id.

## Where the code lives

The two Java classes are in [OutboxPublisher.java](../../order-service/src/main/java/com/spanlab/order/OutboxPublisher.java): `TraceSupport` and `OutboxBatch`. Spring Boot's OpenTelemetry starter creates the `Tracer` and `Propagator` beans. The endpoint is `management.opentelemetry.tracing.export.otlp.endpoint` in [application.yml](../../order-service/src/main/resources/application.yml).

The Go processes do the same work by hand, because they have no Spring. [gateway/tracing.go](../../gateway/tracing.go) and [consumer/tracing.go](../../consumer/tracing.go) are copies. Each one builds an OTLP HTTP exporter, names the service, samples everything, and installs the W3C propagator. `otelhttp` in the gateway creates the server span and the client span. The consumer starts `order.created` itself.

OTLP metrics from the order service are turned off. Those metrics used to be sent to `localhost:4318` and failed every minute. Prometheus scrapes the HTTP endpoint instead. Traces still go to Jaeger. [Prometheus and Grafana](metrics.md) covers the graphs.
