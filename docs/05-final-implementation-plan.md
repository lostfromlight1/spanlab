# Final implementation plan

This is the execution contract. [The study plan](03-implementation-plan.md) explains the same path. When they disagree, this file wins. Update the tracker in [06-tracker.md](06-tracker.md) when a step's checks pass.

Build one step at a time. Each step runs before the next starts.

## Locked choices

| Piece | Choice |
| --- | --- |
| Order service | Java 21, Spring Boot 4.1.2, package `com.spanlab.order` |
| Gateway and consumer | Go 1.27 |
| Database | `postgres:16`, database `spanlab`, user `spanlab`, password `spanlab` |
| Kafka | `apache/kafka:4.2.2`, KRaft, one broker. Same 4.2 line as the Kafka clients in the Boot 4.1.2 BOM. |
| Traces | `jaegertracing/all-in-one:1.76.0`. OTLP HTTP is port 4318 inside the network. UI is host port 16686. |
| Topic | `order-events`, one partition, record key = order id |
| Consumer group | `spanlab-projection` |
| Service names | `gateway`, `order-service`, `consumer` |

Java starters, versions from the 4.1.2 BOM:

- `spring-boot-starter-webmvc`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-kafka`
- `spring-boot-starter-flyway`
- `org.flywaydb:flyway-database-postgresql` at runtime, same version as the BOM's Flyway (12.4.0 on the 4.0.9 to 4.1.2 diff; use the BOM version if Gradle resolves it)
- `org.postgresql:postgresql` at runtime
- `spring-boot-starter-actuator`
- `spring-boot-starter-opentelemetry`

```yaml
spring:
  application:
    name: order-service
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

If Boot 4.1.2 rejects `management.tracing.sampling.probability`, use the 4.1 sampler property and keep the ratio at 1. Boot 4.1 uses Jackson 3. Use the Jackson 3 annotations that the 4.1.2 starters bring.

Go processes set `OTEL_SERVICE_NAME` and export OTLP HTTP to `http://jaeger:4318`. Gateway modules: `go.opentelemetry.io/otel`, `otelhttp`, `otlptracehttp`. Consumer adds `github.com/segmentio/kafka-go` and `github.com/jackc/pgx/v5`.

## API

The gateway forwards status and body unchanged. `amount` is a decimal string on every request and response.

`POST /api/v1/orders` returns `201`:

```json
{
  "id": "6f1c0c3e-8a1b-4d2e-9c55-1b7e2a0d4f10",
  "customerId": "cust-1001",
  "sku": "SKU-1",
  "quantity": 2,
  "amount": "19.99",
  "status": "CREATED",
  "createdAt": "2026-11-06T04:15:00Z"
}
```

`GET /api/v1/orders/{id}` returns that same shape.

`GET /api/v1/projections/{orderId}` returns `200`:

```json
{
  "eventId": "9c2e7a11-0b44-4f6a-8d21-77a1c5e9b003",
  "orderId": "6f1c0c3e-8a1b-4d2e-9c55-1b7e2a0d4f10",
  "customerId": "cust-1001",
  "sku": "SKU-1",
  "quantity": 2,
  "amount": "19.99",
  "processedAt": "2026-11-06T04:15:02Z"
}
```

If several projection rows exist for one order, return the one with the latest `processed_at`. This build writes one.

Validation failure is `400`. An unknown order or projection is `404`. Both are a Spring ProblemDetail body from Java, or the same shape from Go: `title`, `status`, and `detail`.

Reject a create when `customerId` or `sku` is blank, `quantity` is less than 1, or `amount` is missing, negative, or has more than two decimal places.

Gateway client timeout to either downstream is 2 seconds.

## Trace

1. Gateway server span for `POST /api/v1/orders`, via `otelhttp`.
2. Java server span for the forwarded call.
3. Create copies `traceparent` and `tracestate` onto the outbox row in the same transaction as the order.
4. Publisher restores that context and starts a child span named `outbox.publish`.
5. Kafka headers `traceparent` and `tracestate` match those columns.
6. Consumer extracts the headers and starts a child span named `order.created`.

## Network

Host ports: gateway `8080`, Jaeger UI `16686`.

Inside Compose: order service `8081`, consumer `8082`, Postgres `5432`, Kafka `9092`, Jaeger OTLP `4318`.

Kafka is advertised as `kafka:9092` to other containers. Clients on the host do not connect to the broker. Because this image drops its defaults when any `KAFKA_` variable is set, the compose file sets the full combined-mode broker: node id `1`, roles `broker,controller`, listeners `PLAINTEXT://:9092` and `CONTROLLER://:9093`, controller quorum `1@kafka:9093`, offsets and transaction replication factor `1`, min ISR `1`, and a fixed cluster id. Healthcheck: `kafka-broker-api-versions.sh --bootstrap-server localhost:9092`.

Postgres healthcheck: `pg_isready -U spanlab -d spanlab`.

`order-service` and `consumer` wait until Postgres and Kafka are healthy, and until Jaeger has started. `gateway` waits until both apps are healthy. Java health is `/actuator/health`. Go health is `/healthz`.

## Steps

### 1. Infrastructure

Write [docker-compose.yml](../docker-compose.yml) with Postgres, Kafka, and Jaeger only.

Done when the Jaeger UI loads at `http://localhost:16686`, and this prints `spanlab`:

```powershell
docker compose exec postgres psql -U spanlab -d spanlab -c "SELECT current_database();"
```

### 2. Order service write path

Add `order-service/` with the Gradle build, `application.yml`, Flyway `V1__order_schema.sql` from [the database doc](04-database.md), and:

- `POST /api/v1/orders`
- `GET /api/v1/orders/{id}`
- `GET /actuator/health`

`POST` allocates the order id and the event id and inserts both rows in one transaction. The outbox status is `NEW`. The response does not wait for Kafka. Add the service to Compose on port `8081` with no host port.

Done when a create against the order-service container returns `201`, and both rows are visible in `psql` with the same `traceparent` the request sent. A second request with `quantity: 0` returns `400` and adds no row.

### 3. Publisher

In the same process, a one-second schedule publishes a batch of at most 20 `NEW` rows.

Done when, about a second after a create, the outbox row is `PUBLISHED`, `published_at` is set, and `order-events` has one record whose key is the order id and whose headers carry the stored trace.

### 4. Consumer

Add `consumer/` and `consumer/sql/001_processed_orders.sql`. Apply that SQL at startup. Read `order-events`. Insert with `ON CONFLICT (event_id) DO NOTHING`. Commit the offset after the insert returns. Serve the projection GET and `/healthz` on `8082`, with no host port.

Done when the projection row exists for the event id, a second delivery does not change the row, and the GET returns `200`.

### 5. Gateway

Add `gateway/` on host port `8080` with the three routes from [the features doc](02-features.md) and `/healthz`.

Done when the same three calls through `localhost:8080` match the direct order-service and consumer responses, and the outgoing request carries `traceparent`.

### 6. One trace

Run the walkthrough in [the study plan](03-implementation-plan.md). The Jaeger trace for that POST contains the gateway span, the Java server span, `outbox.publish`, and `order.created`.

Done when that trace is visible and the tracker records the trace id.

## Outside this build

A dead-letter topic, Grafana, a load generator, a second Java service, gRPC reads, and Kubernetes manifests.
