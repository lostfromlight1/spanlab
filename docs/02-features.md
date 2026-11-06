# Features

This build is the core path: create an order, read it, read the projection, and see one trace. The later list at the bottom is named so the shape of the lab stays clear. Those items are outside this build.

## Core

### Create an order through the gateway

`POST /api/v1/orders` on the gateway is the only write you call.

```json
{
  "customerId": "cust-1001",
  "sku": "SKU-1",
  "quantity": 2,
  "amount": "19.99"
}
```

`amount` is a decimal string so `19.99` stays exact. The gateway forwards the body and the `traceparent` header to the Java service. The response is `201` with the new order id.

### Read the order through the gateway

`GET /api/v1/orders/{id}` is served by Java, through the gateway. It reads the `orders` row. This succeeds as soon as the create transaction commits, before Kafka or the consumer has done anything.

### Read the projection through the gateway

`GET /api/v1/projections/{orderId}` is served by the Go consumer, through the gateway. It reads `processed_orders`. The row appears after the outbox publisher has sent `order.created` and the consumer has applied it. Expect about a second of delay, then retry.

The projection is how you see that the asynchronous half of the path ran.

### The order and the outbox commit together

Creating an order inserts `orders` and `outbox_event` in one Postgres transaction. If the commit succeeds, both rows are there. If it fails, neither is. The event is a record of a committed order, written in the same commit.

### The publisher can run on more than one instance

The publisher polls for rows in status `NEW` and locks them with `FOR UPDATE SKIP LOCKED`. A second order-service instance skips rows the first instance already locked. Each event is sent by one publisher at a time.

### A duplicate event is safe

The consumer inserts the projection with the event id as the primary key. A second delivery of the same event id changes nothing. That covers the gap where Kafka already has the record and the outbox row is still `NEW`, and it covers a consumer restart that reads the record again.

### One trace in Jaeger

A successful create produces one trace with these spans:

- the gateway span for `POST /api/v1/orders`
- the Java server span for that same call
- `outbox.publish`, parented by the `traceparent` saved on the outbox row
- `order.created` in the consumer, parented by the publish span

Open Jaeger, find the gateway service, and open that trace. The consumer span is on it even though the HTTP response returned earlier.

### Run it with Docker Compose

`docker compose up` starts Postgres, Kafka, Jaeger, Prometheus, Grafana, the catalog service, the order service, the consumer, and the gateway. The gateway (`8080`), the Jaeger UI (`16686`), and Grafana (`3000`) are published to your machine. `docker compose --profile load run --rm loadgen` posts ten `SKU-1` orders through the gateway.

Each application process exposes a health check so Compose can wait:

- Java: `/actuator/health`
- Go gateway and consumer: `/healthz`

## Included after the core path

- `order-events-dlq`. Bad JSON is published there and the offset is committed. A database error is republished to `order-events` with `x-attempts` and goes to the dead-letter topic after three tries.
- Grafana on port `3000` reads Prometheus and Jaeger. Prometheus scrapes `/actuator/prometheus` on the order and catalog services.
- `loadgen` creates orders in a loop. It runs only with the `load` profile.
- `catalog-service` answers `CheckSku` on gRPC port `9090`. `SKU-1` and `SKU-2` are known. The order service calls it before the database transaction. An unknown SKU is `400`.
- `k8s/spanlab.yaml` is the same stack as Compose. Apply it when a cluster is available.
