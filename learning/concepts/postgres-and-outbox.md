# Postgres and the outbox

Postgres is the system of record. Three tables matter. Two are written by the order service. One is written by the consumer.

| Table | Who writes it | What a row means |
| --- | --- | --- |
| `"order".orders` | Order service, in the create transaction | The order the client was told about. |
| `"order".outbox_event` | Order service, same transaction, then the publisher | A `NEW` event that still needs to reach Kafka, or a `PUBLISHED` one. |
| `projection.processed_orders` | Consumer | The consumer has applied this event id. |

The schema name `order` is a SQL keyword, so it is quoted. Hibernate is configured with `globally_quoted_identifiers: true` for the same reason. The DDL is [V1__order_schema.sql](../../order-service/src/main/resources/db/migration/V1__order_schema.sql). Flyway runs it once. The consumer's SQL is [001_processed_orders.sql](../../consumer/sql/001_processed_orders.sql) and uses `IF NOT EXISTS` because the consumer runs it on every start.

`amount` is `numeric(12,2)` in both order tables and in the projection. JSON carries it as a decimal string such as `"12.50"`. [OrderController.java](../../order-service/src/main/java/com/spanlab/order/OrderController.java) rejects a blank amount, more than two decimal places, and a negative amount.

## Why the order and the outbox row share one transaction

The create method does not call Kafka. [OrderService.java](../../order-service/src/main/java/com/spanlab/order/OrderService.java) validates, calls the catalog, then `OrderWriter.insert` saves both rows. `@Transactional` on `insert` is a different Spring bean from `OrderService`, so the transaction actually starts. Calling a `@Transactional` method on the same class would skip the proxy and open no transaction.

If the commit fails, the client gets an error and Kafka has nothing to deliver. If the commit succeeds, the client has an order id and the publisher will keep trying until Kafka accepts the record.

Sending to Kafka inside the create transaction would be the wrong shape. A failed send would roll the order back, so the client would lose an order that Kafka might already have stored. A committed order with a failed send would leave no row for the publisher to retry.

## What the publisher locks

```sql
SELECT * FROM "order".outbox_event
WHERE status = 'NEW'
ORDER BY created_at
LIMIT 20
FOR UPDATE SKIP LOCKED
```

`FOR UPDATE` locks the selected rows until the transaction ends. `SKIP LOCKED` means a second publisher, if you ran one, would take the next unlocked rows instead of waiting. This lab runs one publisher. The batch is one transaction: a send failure rolls the batch back and the rows stay `NEW`.

After a successful send, `markPublished` sets `status` to `PUBLISHED` and `published_at` to now. The index `outbox_event_pending` is on `(status, created_at)` so the `NEW` scan stays cheap.

## The projection

The consumer inserts the event id as the primary key. `ON CONFLICT (event_id) DO NOTHING` makes a duplicate delivery a no-op. The GET returns the latest row for an order id, which is the same row when each order has one event.

The order table is the write model. The projection is the read model the consumer owns. They can be a second apart. That gap is the point of the lab.
