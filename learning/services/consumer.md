# Consumer

The consumer is a Go process on port 8082. It reads `order-events`, writes one projection row per event id, and serves that row over HTTP. The gateway is the only caller of that HTTP route.

| File | What it is |
| --- | --- |
| [main.go](../../consumer/main.go) | The Kafka loop, the dead-letter path, and the projection GET. |
| [tracing.go](../../consumer/tracing.go) | The same OTLP setup as the gateway, with service name `consumer`. |
| [001_processed_orders.sql](../../consumer/sql/001_processed_orders.sql) | Embedded and executed on every start. |

## Read `main` in this order

1. Tracing starts, the same way as the gateway. `defer` closes the exporter on shutdown.
2. `pgxpool.New` is a Postgres connection pool. The URL comes from `DATABASE_URL`.
3. `schemaSQL` is the SQL file compiled into the binary with `//go:embed`. `IF NOT EXISTS` matters because this `Exec` runs every time the process starts.
4. The reader joins group `spanlab-projection` on topic `order-events`. `StartOffset: kafka.FirstOffset` applies when the group has no committed offset yet. After the first commit, the group continues from that offset.
5. The writer can publish to `order-events` or `order-events-dlq`. `AllowAutoTopicCreation: false` means a missing topic is an error. `kafka-init` has already created both.
6. `go consume(...)` is the loop on another goroutine. `main` then serves HTTP. `GET /healthz` and `GET /api/v1/projections/{orderId}` are the only routes.

## The loop

`FetchMessage` does not commit. The offset moves only when `CommitMessages` is called.

`Extract` reads `traceparent` from the Kafka headers into the context. `tracer.Start` names the span `order.created` and marks it as a consumer span. That span is a child of `outbox.publish`.

`apply` unmarshals the envelope. Bad JSON, or a missing event id or order id, returns `permanentError`. The insert uses `ON CONFLICT (event_id) DO NOTHING`. A database error returns as a normal `error`, which the retry path treats as transient.

`headerCarrier` exists so the OpenTelemetry propagator can read Kafka headers. `Set` is empty because this process does not inject headers onto the record it consumed. The compiler line `var _ propagation.TextMapCarrier = headerCarrier(nil)` fails the build if a method is missing. That is the Go form of "this type implements the interface."

## When apply fails

`deadLetter` adds one to `x-attempts`.

- A `permanentError`, or an attempt count of 3 or more, republishes the same key and value to `order-events-dlq`.
- Otherwise it republishes to `order-events` so the loop will see it again.

The new record carries `x-attempts` and `x-error`. The original offset is committed after that publish returns. If the publish fails, the function returns without committing, and Kafka delivers the same record again.

[Kafka](../concepts/kafka.md) is the longer version of this policy.

## The GET

`getProjection` selects the latest `processed_at` for the order id. No row is `404` problem+json. `amount::text` keeps the decimal string. The JSON field names come from the struct tags on `projection`.
