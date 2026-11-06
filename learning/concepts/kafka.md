# Kafka

Kafka in this lab is a log of records. The order service appends a record. The consumer reads it later, on its own pace. The HTTP call that created the order has already returned.

The running broker is one KRaft process, image `apache/kafka:4.2.2`, advertised as `kafka:9092`. There is no ZooKeeper. The settings live in [docker-compose.yml](../../docker-compose.yml) and again in [k8s/spanlab.yaml](../../k8s/spanlab.yaml).

## The words you will see in the code

| Word | What it means here |
| --- | --- |
| Topic | A named log. This lab has `order-events` and `order-events-dlq`. |
| Record | One message. The key is the order id. The value is a JSON envelope. |
| Partition | An ordered slice of a topic. Both topics have one partition, so order is simple. |
| Offset | The position of a record in the partition. The consumer commits an offset only after it has handled that record. |
| Consumer group | A name that remembers the offset. This consumer uses `spanlab-projection`. Restarting the process continues from the last commit. |
| Header | Extra fields on the record. The trace uses `traceparent` and `tracestate`. Retries use `x-attempts` and `x-error`. |

## Why a topic is created before the consumer starts

`kafka-init` runs once Kafka is healthy and creates both topics. The consumer waits until that job has exited. kafka-go joins the group when it starts. If the topic does not exist yet, the group can be assigned zero partitions and sit there while the first produce creates the topic later. Creating the topic first avoids that.

## The record the publisher sends

[OutboxPublisher.java](../../order-service/src/main/java/com/spanlab/order/OutboxPublisher.java) builds this value:

```json
{
  "id": "event uuid",
  "type": "order.created",
  "occurredAt": "timestamp",
  "payload": {
    "orderId": "order uuid",
    "customerId": "kaung",
    "sku": "SKU-1",
    "quantity": 1,
    "amount": "12.50"
  }
}
```

`amount` is a decimal string, matching `numeric(12,2)` in Postgres. The key is `payload.orderId`. With one partition the key does not change order, and it still tells you which order the record belongs to.

## At least once

The publisher sends, waits up to 10 seconds, then marks the outbox row `PUBLISHED` in the same database transaction that locked the row. A crash after Kafka accepted the record and before that commit leaves the row `NEW`, so the publisher sends it again.

The consumer can therefore see the same event id twice. [consumer/main.go](../../consumer/main.go) inserts with `ON CONFLICT (event_id) DO NOTHING`, then commits the offset. The second delivery is a no-op. The offset is committed only after that insert returns, so a crash before the commit delivers the record again.

## Poison payloads and retries

`apply` returns a `permanentError` when the bytes are not the envelope, or when `id` or `orderId` is missing. Those records go to `order-events-dlq` on the first failure. The offset on `order-events` is committed, so the partition moves on.

A database error is transient. The consumer republishes the same bytes to `order-events` with `x-attempts` set to 1, then 2. The third failure uses `order-events-dlq`. In both cases the original offset is committed only after the republish succeeds. If the republish itself fails, the offset stays put and Kafka will deliver the same record again.

That is why a bad payload no longer blocks the orders behind it. Before this path, `apply` logged the error and skipped the commit, and the consumer retried the same bytes forever.
