# Database structure

One Postgres 16 database, `spanlab`, holds two schemas. The split is ownership. Java is the only writer of the `order` schema. The Go consumer is the only writer of the `projection` schema. The gateway writes nothing.

`order` is a SQL keyword, so the schema name is quoted everywhere: `"order".orders`.

Kafka is the event log, not a table. The JSON in `outbox_event.payload` is the body of the Kafka record. The trace columns on that row are the Kafka record headers.

## Who writes what

| Table | Writer | Reader |
| --- | --- | --- |
| `"order".orders` | Java order service, in the create transaction | Java, for `GET /api/v1/orders/{id}` |
| `"order".outbox_event` | Java, in the create transaction; the publisher updates status | Java publisher |
| `projection.processed_orders` | Go consumer | Go consumer, for `GET /api/v1/projections/{orderId}` |

Java Flyway applies the `order` schema from `order-service/src/main/resources/db/migration/V1__order_schema.sql`. The consumer applies the projection from `consumer/sql/001_processed_orders.sql` at startup.

## Order schema

```sql
CREATE SCHEMA "order";

CREATE TABLE "order".orders (
    id          uuid PRIMARY KEY,
    customer_id text NOT NULL,
    sku         text NOT NULL,
    quantity    integer NOT NULL CHECK (quantity > 0),
    amount      numeric(12, 2) NOT NULL,
    status      text NOT NULL CHECK (status IN ('CREATED')),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE "order".outbox_event (
    id           uuid PRIMARY KEY,
    aggregate_id uuid NOT NULL,
    event_type   text NOT NULL,
    payload      jsonb NOT NULL,
    status       text NOT NULL CHECK (status IN ('NEW', 'PUBLISHED')),
    traceparent  text,
    tracestate   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE INDEX outbox_event_pending
    ON "order".outbox_event (status, created_at);
```

`orders.id` is the order id returned to the client. This build only uses status `CREATED`. The check keeps a stray status out of the table while the lab has one state.

`outbox_event.id` is the event id. It is also the primary key of the projection, which is what makes a second delivery a no-op. `aggregate_id` is the order id. `event_type` for this build is `order.created`.

`traceparent` and `tracestate` are copied from the request span during the create transaction. The publisher reads them back after the HTTP call has returned. See [What spanlab does](01-overview.md).

The index `(status, created_at)` is the publisher's poll: unpublished rows, oldest first.

## Projection schema

```sql
CREATE SCHEMA projection;

CREATE TABLE projection.processed_orders (
    event_id    uuid PRIMARY KEY,
    order_id    uuid NOT NULL,
    customer_id text NOT NULL,
    sku         text NOT NULL,
    quantity    integer NOT NULL,
    amount      numeric(12, 2) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX processed_orders_by_order
    ON projection.processed_orders (order_id);
```

`event_id` is the idempotency key. The consumer inserts with `ON CONFLICT (event_id) DO NOTHING`.

`processed_orders_by_order` serves the read by order id. This build writes one event per order. The index is there so the read does not depend on that staying true.

## One committed order

The create transaction writes these two rows together. Identifiers below are an example.

`"order".orders`

| Column | Value |
| --- | --- |
| id | `6f1c0c3e-8a1b-4d2e-9c55-1b7e2a0d4f10` |
| customer_id | `cust-1001` |
| sku | `SKU-1` |
| quantity | `2` |
| amount | `19.99` |
| status | `CREATED` |
| created_at | `2026-11-06T04:15:00Z` |

`"order".outbox_event` in the same commit

| Column | Value |
| --- | --- |
| id | `9c2e7a11-0b44-4f6a-8d21-77a1c5e9b003` |
| aggregate_id | `6f1c0c3e-8a1b-4d2e-9c55-1b7e2a0d4f10` |
| event_type | `order.created` |
| payload | the JSON below |
| status | `NEW` |
| traceparent | `00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01` |
| tracestate | null |
| created_at | `2026-11-06T04:15:00Z` |
| published_at | null |

`payload`:

```json
{
  "orderId": "6f1c0c3e-8a1b-4d2e-9c55-1b7e2a0d4f10",
  "customerId": "cust-1001",
  "sku": "SKU-1",
  "quantity": 2,
  "amount": "19.99"
}
```

`amount` is `numeric(12, 2)` in the table and a decimal string in JSON, so the value does not pass through a binary float.

## After the publisher runs

The publisher sets that outbox row to `PUBLISHED` and fills `published_at`. The order row does not change.

The Kafka record body is the event envelope (`id`, `type`, `occurredAt`, `payload`). The record key is the order id. The record headers `traceparent` and `tracestate` match the columns on the outbox row.

## After the consumer runs

`projection.processed_orders`

| Column | Value |
| --- | --- |
| event_id | `9c2e7a11-0b44-4f6a-8d21-77a1c5e9b003` |
| order_id | `6f1c0c3e-8a1b-4d2e-9c55-1b7e2a0d4f10` |
| customer_id | `cust-1001` |
| sku | `SKU-1` |
| quantity | `2` |
| amount | `19.99` |
| processed_at | the time the consumer inserted the row |

If the same event id arrives again, the primary key rejects the second insert and the existing row stays.

## Outbox lifecycle

1. The create transaction inserts the row as `NEW`, with the trace columns set.
2. The publisher selects `status = 'NEW'` and locks the batch with `FOR UPDATE SKIP LOCKED`. Another publisher skips locked rows.
3. It sends the Kafka record, then sets `status = 'PUBLISHED'` and `published_at`.
4. A crash after the send and before that update leaves the row `NEW`. The next poll sends the event again. The projection primary key absorbs the duplicate.
