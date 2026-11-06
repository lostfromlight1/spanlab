# Catalog service

The catalog service answers one question: is this SKU known? The order service asks over gRPC. Your machine does not call this process directly.

| Port | What it is |
| --- | --- |
| 9090 | gRPC, `CheckSku`. Not published to the host. |
| 8083 | HTTP `/actuator/health` and `/actuator/prometheus`. |

| File | What it is |
| --- | --- |
| [catalog.proto](../../proto/catalog/v1/catalog.proto) | The method and the two messages. Shared with the order service. |
| [CatalogApplication.java](../../catalog-service/src/main/java/com/spanlab/catalog/CatalogApplication.java) | Spring Boot entry. |
| [CatalogGrpcService.java](../../catalog-service/src/main/java/com/spanlab/catalog/CatalogGrpcService.java) | `SKU-1` and `SKU-2` are known. |
| [application.yml](../../catalog-service/src/main/resources/application.yml) | HTTP port 8083 and gRPC port 9090. |

## Read the service method

`CatalogGrpcService` extends the class Gradle generates from the proto, `CatalogGrpc.CatalogImplBase`. `@GrpcService` makes it a bean, and Spring exposes every `BindableService` bean on the Netty server.

`checkSku` looks up the request SKU in a `Set`. It calls `onNext` with `known` true or false, then `onCompleted`. There is no error status for an unknown SKU. The order service maps `false` to HTTP `400`.

The set is in memory. Restarting the process does not forget `SKU-1` or `SKU-2`, because they are compiled in. There is no database in this service.

## Why there is also an HTTP port

Compose checks health with an HTTP `GET`. Prometheus scrapes an HTTP endpoint. Those stay on Tomcat at `server.port` 8083. gRPC stays on Netty at `spring.grpc.server.port` 9090. The [gRPC](../concepts/grpc.md) doc explains why those are different servers.

`application.yml` exposes `health` and `prometheus` and turns OTLP metrics off, the same choice as the order service.
