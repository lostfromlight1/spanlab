# gRPC

gRPC is a typed remote call. The catalog service exposes one method, `CheckSku`. The order service calls it before it opens the database transaction. The gateway never speaks gRPC. From your machine, every call is still HTTP on port 8080.

## The contract

[catalog.proto](../../proto/catalog/v1/catalog.proto) is the shared description:

```protobuf
service Catalog {
  rpc CheckSku (CheckSkuRequest) returns (CheckSkuResponse);
}
```

`CheckSkuRequest` has a `sku` string. `CheckSkuResponse` has a `known` bool. Gradle, with the protobuf plugin, generates Java from that file into both services. The generated package is `com.spanlab.catalog.v1`. You edit the `.proto` file, not the generated classes.

This is the same idea as a Java interface that both sides implement, except the interface is compiled from the proto and the bytes on the wire are not JSON.

## The server

[CatalogGrpcService.java](../../catalog-service/src/main/java/com/spanlab/catalog/CatalogGrpcService.java) extends the generated `CatalogImplBase` and is a `@GrpcService` bean. Spring registers every `BindableService` bean on the gRPC server. The method is still the async shape gRPC generates: you are given a `StreamObserver`, you call `onNext` with the reply, then `onCompleted`.

`SKU-1` and `SKU-2` return `known: true`. Any other string returns `known: false`. The service does not throw for an unknown SKU. The order service turns `false` into HTTP `400`.

The gRPC server is Netty on port 9090 (`spring.grpc.server.port`). HTTP health and Prometheus stay on port 8083 (`server.port`). Adding `spring-boot-starter-webmvc` does not move gRPC onto the servlet port. Servlet gRPC would be a different dependency.

## The client

[OrderApplication.java](../../order-service/src/main/java/com/spanlab/order/OrderApplication.java) imports the generated blocking stub:

```java
@ImportGrpcClients(target = "catalog", types = CatalogGrpc.CatalogBlockingStub.class)
```

`target = "catalog"` is a name, not a host. [application.yml](../../order-service/src/main/resources/application.yml) maps that name:

```yaml
spring.grpc.client.channel.catalog.target: static://catalog-service:9090
```

`static://` means a fixed address. `CatalogBlockingStub.checkSku` looks like a normal method call. It blocks the request thread until the catalog answers, the way a Spring `RestClient` call would. The stub is injected into [CatalogLookup.java](../../order-service/src/main/java/com/spanlab/order/CatalogLookup.java).

## Why the call sits outside the transaction

`OrderService.create` calls the catalog, then calls `OrderWriter.insert`. The transaction starts on `insert`. A gRPC call inside `@Transactional` would hold a Postgres connection for the whole round trip. The catalog also has nothing to do with the order row: an unknown SKU should write nothing.

If the catalog process is down, the stub throws `StatusRuntimeException` and the order service returns `503` with detail `catalog is unavailable`.
