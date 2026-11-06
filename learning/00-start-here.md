# Start here

This folder is how you read spanlab. You already know Java. The Go here is small, and each Go file says which Java idea it matches.

The [docs/](../docs/) folder is the build contract: what was promised and what was checked. This folder is the walk through the code that is already here.

## On a new machine, then using the lab

1. [Set up a new machine](02-new-machine.md) — Docker, the first `docker compose up`, and the image mirror when Docker Hub times out.
2. [Use the lab](03-use-the-lab.md) — create an order, read it back, open the Jaeger trace, and open Grafana.

## Read the code in this order

1. [One order](01-one-order.md) — follow a single `POST` from your machine to Postgres, Kafka, and Jaeger.
2. The concept that matches the question you have:
   - [Kafka](concepts/kafka.md)
   - [Postgres and the outbox](concepts/postgres-and-outbox.md)
   - [Tracing](concepts/tracing.md)
   - [gRPC](concepts/grpc.md)
   - [Prometheus and Grafana](concepts/metrics.md)
   - [Compose and Kubernetes](concepts/compose-and-kubernetes.md)
3. The service you are about to open:
   - [Gateway](services/gateway.md) — Go, port 8080
   - [Order service](services/order-service.md) — Java, port 8081
   - [Catalog service](services/catalog-service.md) — Java, HTTP 8083 and gRPC 9090
   - [Consumer](services/consumer.md) — Go, port 8082
   - [Load generator](services/loadgen.md) — Go, no port

Open the file the service doc names. The comment at the top of that file is the same idea, written next to the code.

## What is running

`docker compose up` starts the lab. Three ports are published on your machine:

| Port | What you open |
| --- | --- |
| 8080 | The gateway. This is the only HTTP API you call. |
| 16686 | Jaeger. One trace for one order. |
| 3000 | Grafana. Request rates from the two Java processes. |

Calls between the gateway and the order service stay HTTP. The catalog check is gRPC inside the Docker network, on port 9090, and that port is not published.

## Java to Go, for the files in this repo

| You know this in Java | You will see this in the Go files |
| --- | --- |
| `public class Gateway { public static void main }` | `package main` and `func main()`. The folder is the program. |
| A class with fields | A `struct`. `type envelope struct` is a small class. |
| `throw` and `catch` | A function returns `(result, error)`. The caller writes `if err != nil`. |
| `try` / `finally` | `defer`. It runs when the function returns, including on an error return. |
| `new Thread(...).start()` or `@Async` | `go consume(...)`. That call runs on another goroutine. |
| A request-scoped object you pass down | `context.Context`. It carries cancellation and the current trace. |
| `@GetMapping("/healthz")` | `mux.HandleFunc("GET /healthz", ...)`. |
| `System.getenv` with a default | `env("ORDER_URL", "http://order-service:8081")`. |
| Jackson `@JsonProperty` | A struct tag: `` `json:"orderId"` ``. |
| An interface the compiler checks | `var _ propagation.TextMapCarrier = headerCarrier(nil)` asks the compiler to check the methods. |
| A checked-in SQL file on the classpath | `//go:embed sql/001_processed_orders.sql` copies the file into the binary. |

Go has no classes, no exceptions for ordinary failures, and no dependency injection container in these files. Constructors are just functions. A `nil` error means success.

## A request, in one line

`POST /api/v1/orders` with `sku` `SKU-1` enters the gateway, the order service asks the catalog over gRPC, one Postgres transaction writes the order and an outbox row, a timer publishes that row to Kafka, and the consumer writes a projection. Jaeger shows that as one trace. Grafana shows how often the Java processes were called.
