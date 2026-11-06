# Order service

The order service is Spring Boot 4.1.2 on port 8081. It checks the SKU, commits the order and the outbox row together, and publishes that row to Kafka on a timer. It does not call Kafka from the HTTP thread.

| File | What it is |
| --- | --- |
| [OrderApplication.java](../../order-service/src/main/java/com/spanlab/order/OrderApplication.java) | Process entry. Turns on scheduling and the catalog gRPC stub. |
| [OrderController.java](../../order-service/src/main/java/com/spanlab/order/OrderController.java) | HTTP, validation errors, and the money parser. |
| [OrderService.java](../../order-service/src/main/java/com/spanlab/order/OrderService.java) | Checks, then the catalog call, then the transaction. |
| [CatalogLookup.java](../../order-service/src/main/java/com/spanlab/order/CatalogLookup.java) | The blocking gRPC call. |
| [OrderEntity.java](../../order-service/src/main/java/com/spanlab/order/OrderEntity.java) | JPA rows for the order and the outbox. |
| [OrderRepository.java](../../order-service/src/main/java/com/spanlab/order/OrderRepository.java) | `FOR UPDATE SKIP LOCKED`. |
| [OutboxPublisher.java](../../order-service/src/main/java/com/spanlab/order/OutboxPublisher.java) | Trace restore and the Kafka send. |
| [application.yml](../../order-service/src/main/resources/application.yml) | Ports, Postgres, Kafka, Jaeger, Prometheus. |
| [V1__order_schema.sql](../../order-service/src/main/resources/db/migration/V1__order_schema.sql) | Flyway DDL. |

## Read a create in this order

1. `OrderApplication` starts Spring. `@EnableScheduling` is what makes `@Scheduled` run. `@ImportGrpcClients` registers `CatalogBlockingStub` against the channel named `catalog`.
2. `OrderController.create` takes the JSON body and the two trace headers. The headers are optional so a caller without a gateway still works. The response is `201` with `OrderResponse`.
3. `OrderService.create` rejects a missing body, a blank customer or SKU, a quantity below 1, and a bad amount. `Money.parse` lives in the controller file and returns a `BigDecimal` with scale 2.
4. `CatalogLookup.known` calls the catalog. `false` becomes `400` `sku is unknown`. A gRPC failure becomes `503`.
5. `OrderWriter.insert` is the transaction. It allocates the order id and the event id, captures the trace, and saves both entities. The response is built from the order entity. Kafka is not in this method.
6. About a second later `OutboxPublisher.tick` calls `OutboxBatch.publish`. The batch locks `NEW` rows, starts `outbox.publish`, sends to `order-events`, and marks the row `PUBLISHED`.

`GET /api/v1/orders/{id}` calls `OrderWriter.get`. A missing id is `404` through `OrderProblem`, and `ApiErrors` turns that into problem+json. The title is the HTTP reason phrase, so `400` is `Bad Request`, `404` is `Not Found`, and `503` is `Service Unavailable`.

## Two beans on purpose

`OrderService` is not `@Transactional`. `OrderWriter` is. Spring starts a transaction only when the call crosses a Spring bean. If `insert` were a method on `OrderService` and `create` called `this.insert`, the annotation would do nothing.

The same split is why the catalog call is in `create`. The database connection is taken when `insert` begins, after the gRPC call has finished.

`OutboxPublisher` and `OutboxBatch` are split for the same reason. `tick` calls another bean, so `@Transactional` on `publish` runs.

## Jackson 3

The mapper type is `tools.jackson.databind.ObjectMapper`. Boot 4 uses Jackson 3. The import is not `com.fasterxml.jackson`.

## Config you will look up

`application.yml` sets the HTTP port to 8081, quotes SQL identifiers, points Kafka at `kafka:9092` with `acks: all`, samples every trace, exports traces to Jaeger, turns OTLP metrics off, and exposes `/actuator/health` and `/actuator/prometheus`. The catalog channel target is `static://catalog-service:9090`.
