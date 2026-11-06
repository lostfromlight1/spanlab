# Implementation plan

Build in the order below. Each step runs before the next one is added. This document is the plan for that work. It does not add the services.

The trace stays connected for the reason in [What spanlab does](01-overview.md): the create transaction stores `traceparent` on the outbox row, and the publisher restores it after the HTTP call has returned.

## Prerequisites

- Java 21 (this machine has 21.0.9)
- Go 1.27 (this machine has 1.27.3)
- Docker Desktop, running. The daemon was not running when this lab was sketched. Start Docker before `docker compose up`.

## Stack

Versions below were checked against Maven Central and the Spring Boot 4.1 dependency set in October 2026.

| Piece | Choice |
| --- | --- |
| Order service | Java 21, Spring Boot 4.1.2 |
| Gateway and consumer | Go 1.27 |
| Database | Postgres 16 |
| Event log | Apache Kafka, KRaft, one broker. Pin `apache/kafka` to a current 4.x tag when Compose is written. Boot 4.1.2 manages Kafka clients on the 4.2 line. |
| Traces | Jaeger all-in-one, OTLP HTTP on port 4318, UI on port 16686. Pin `jaegertracing/all-in-one` to a current 2.x tag when Compose is written. |

Spring Boot 4.1 renamed the web starter and split Kafka, Flyway, and OpenTelemetry into their own starters. The order service uses these artifacts, with versions from the 4.1.2 BOM:

- `org.springframework.boot:spring-boot-starter-webmvc` (Spring MVC and Tomcat; this replaces `spring-boot-starter-web`)
- `org.springframework.boot:spring-boot-starter-data-jpa`
- `org.springframework.boot:spring-boot-starter-kafka`
- `org.springframework.boot:spring-boot-starter-flyway`
- `org.flywaydb:flyway-database-postgresql`, runtime, aligned to the Flyway version the 4.1.2 BOM manages (12.4.0 on the 4.0.9 to 4.1.2 dependency diff)
- `org.postgresql:postgresql`, runtime
- `org.springframework.boot:spring-boot-starter-actuator`
- `org.springframework.boot:spring-boot-starter-opentelemetry`

Boot 4.1 exports traces with this property:

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: http://jaeger:4318/v1/traces
```

`management.opentelemetry.tracing.export.otlp.endpoint` is the 4.1 export setting. Sampling at `1.0` keeps every span, which this lab wants. If the 4.1.2 build rejects `management.tracing.sampling.probability`, switch to the sampler key in the 4.1 configuration changelog and keep the ratio at 1.

Boot 4.1 uses Jackson 3. Request and response types in the order service should follow the Jackson 3 annotations that Boot 4.1 brings, rather than the Jackson 2 imports from older Spring Boot 3 services.

Go modules, versions resolved when the modules are created:

- Gateway: `go.opentelemetry.io/otel`, `go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp`, and the OTLP HTTP trace exporter `go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp`
- Consumer: the same OpenTelemetry modules, `github.com/segmentio/kafka-go`, and `github.com/jackc/pgx/v5`

Both Go processes export OTLP HTTP to `http://jaeger:4318`, the same collector port Java uses.

## Repository layout

```text
spanlab/
  docker-compose.yml
  README.md
  docs/
  order-service/
    build.gradle
    src/main/java/
    src/main/resources/application.yml
    src/main/resources/db/migration/V1__order_schema.sql
  gateway/
    go.mod
  consumer/
    go.mod
    sql/001_processed_orders.sql
```

Java Flyway owns the `order` schema. The consumer applies `consumer/sql/001_processed_orders.sql` at startup and owns the `projection` schema. The gateway has no tables.

## Network

Published on the host:

- gateway `8080`
- Jaeger UI `16686`

Reachable only on the Compose network:

- order service `8081`
- consumer `8082`
- Postgres `5432`
- Kafka `9092`
- Jaeger OTLP `4318`

## Build steps

### 1. Compose the infrastructure

Postgres 16, Kafka in KRaft mode with replication factor 1, and Jaeger with OTLP enabled. Topic `order-events` can be auto-created by the broker. No application containers yet. Confirm Jaeger's UI loads and Postgres accepts a connection from another container.

### 2. Java order service, synchronous write

Flyway applies [the order schema](04-database.md). Endpoints, on port 8081 inside the network:

- `POST /api/v1/orders`
- `GET /api/v1/orders/{id}`
- `GET /actuator/health`

`POST` generates an order id and an event id, then inserts `orders` and `outbox_event` in one transaction. The outbox row stores the current `traceparent` and `tracestate`. Status of the new outbox row is `NEW`. The HTTP response does not wait for Kafka.

The Kafka record, sent in the next step, will use this envelope. `amount` stays a decimal string.

```json
{
  "id": "9c2e7a11-0b44-4f6a-8d21-77a1c5e9b003",
  "type": "order.created",
  "occurredAt": "2026-11-06T04:15:00Z",
  "payload": {
    "orderId": "6f1c0c3e-8a1b-4d2e-9c55-1b7e2a0d4f10",
    "customerId": "cust-1001",
    "sku": "SKU-1",
    "quantity": 2,
    "amount": "19.99"
  }
}
```

The Kafka record key is the order id, so later event types for the same order stay in order.

### 3. Publisher in the same Java process

A scheduled loop, about once a second:

1. In a transaction, select a small batch of `outbox_event` rows with `status = 'NEW'`, ordered by `created_at`, using `FOR UPDATE SKIP LOCKED`.
2. For each row, extract the stored trace context and start a span named `outbox.publish`.
3. Send the envelope to `order-events`. Put `traceparent` and `tracestate` on the Kafka record headers.
4. Set the row to `PUBLISHED` and set `published_at`.
5. Commit.

Send, then mark, then commit. A crash after the send and before the commit leaves the row `NEW`, so the next poll sends it again. The consumer treats that second record as the same event.

### 4. Go consumer

Read `order-events` in consumer group `spanlab-projection`. For each record:

1. Extract `traceparent` from the headers and start a span named `order.created`.
2. Insert into `projection.processed_orders` with `event_id` as the primary key. On a conflict, do nothing.
3. End the span and commit the Kafka offset after the insert succeeds.

Expose `GET /api/v1/projections/{orderId}` and `GET /healthz` on port 8082. The read looks up the latest projection for that order id.

### 5. Go gateway

Listen on `8080`.

| Route | Forwards to |
| --- | --- |
| `POST /api/v1/orders` | order service |
| `GET /api/v1/orders/{id}` | order service |
| `GET /api/v1/projections/{orderId}` | consumer |

`otelhttp` starts the server span. The outgoing request injects `traceparent`. `GET /healthz` is the Compose health check.

### 6. Walk through one trace

With the stack up:

```powershell
curl.exe -s -D - -X POST http://localhost:8080/api/v1/orders `
  -H "Content-Type: application/json" `
  -d "{\"customerId\":\"cust-1001\",\"sku\":\"SKU-1\",\"quantity\":2,\"amount\":\"19.99\"}"
```

Copy the order id from the body. Read the order immediately:

```powershell
curl.exe -s http://localhost:8080/api/v1/orders/<orderId>
```

Wait about a second, then read the projection:

```powershell
curl.exe -s http://localhost:8080/api/v1/projections/<orderId>
```

Open `http://localhost:16686`. Find the gateway service and open the `POST /api/v1/orders` trace. It should contain the Java server span, `outbox.publish`, and `order.created`.

## Later, outside this build

After this path is visible in Jaeger, the next slices are a dead-letter topic, Grafana, a Go load generator, a second Java service with gRPC reads, and Kubernetes manifests. None of those are part of the six steps above.
