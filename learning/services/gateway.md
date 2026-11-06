# Gateway

The gateway is the only HTTP process your machine calls. It is a Go program on port 8080. It starts the trace and forwards three routes. It does not know what an order is.

| File | What it is |
| --- | --- |
| [main.go](../../gateway/main.go) | Routes, the 2 second client, and the forward function. |
| [tracing.go](../../gateway/tracing.go) | OTLP export to Jaeger. The consumer has a copy of this file. |

## Read `main` in this order

1. `signal.NotifyContext` cancels `ctx` on Ctrl+C. `defer stop()` releases the signal handler when `main` returns. In Java this is a shutdown hook plus a `finally`.
2. `setupTracing` exports spans to Jaeger and installs the W3C propagator. `defer shutdown` flushes the last spans.
3. `orderURL` and `consumerURL` come from the environment. Compose sets them to the Docker DNS names. The default in code is the same name, so a forgotten env var still points at the compose network.
4. The `http.Client` timeout is 2 seconds. `otelhttp.NewTransport` creates a client span and injects `traceparent` on the outgoing call.
5. The mux is the route table. `POST /api/v1/orders` and `GET /api/v1/orders/{id}` go to the order service. `GET /api/v1/projections/{orderId}` goes to the consumer. `GET /healthz` returns `ok`.
6. `otelhttp.NewHandler` wraps the mux and names the server span `METHOD path`, for example `POST /api/v1/orders`.
7. `go func()` waits until `ctx` is cancelled, then shuts the server down. `ListenAndServe` blocks the main goroutine. `http.ErrServerClosed` is the normal shutdown error and is ignored.

## `forward`

`forward` returns an `http.HandlerFunc`. That is a function stored as a value, the way you would pass a lambda to a Java router.

It builds a new request with the caller's method, the downstream URL, and the caller's body. It clones the incoming headers, then `Inject` writes the current trace onto that clone. The explicit inject matters: the transport also injects, and the clone keeps the caller's other headers.

`client.Do` either returns a response or a timeout. On timeout the gateway writes `504` problem+json and does not invent a body. On success it copies the downstream `Content-Type`, status code, and body unchanged. A `400` from Java stays a `400`.

`writeProblem` builds JSON with string concatenation. The titles and details in this file have no quotes, so that stays valid. `itoa` avoids importing `strconv` for a single integer.

## What this is in Java

A `@RestController` with a `RestClient` that has a 2 second timeout, plus a servlet filter that starts a span. Spring would inject the client. Here the client is a local variable closed over by `forward`.
