# Load generator

The load generator is the smallest Go program in the repo. It posts orders to the gateway and exits. It has no port, no trace exporter, and no database.

The only file is [main.go](../../loadgen/main.go).

## How you run it

It is behind the Compose profile `load`, so `docker compose up` does not start it.

```text
docker compose --profile load run --rm loadgen
```

`COUNT` defaults to 10. `GATEWAY_URL` defaults to `http://gateway:8080`. A successful run prints `created 10 orders`.

## Read `main` in this order

1. `env` reads a variable and falls back when it is empty. That is `System.getenv` plus a default.
2. `COUNT` must be a positive integer. `strconv.Atoi` is `Integer.parseInt`. A bad value prints to stderr and `os.Exit(1)`, which is `System.exit(1)`.
3. The HTTP client timeout is 5 seconds, longer than the gateway's 2 second downstream timeout, so a slow order service surfaces as a `504` from the gateway rather than a client timeout here.
4. The body is fixed JSON: customer `loadgen`, `SKU-1`, quantity 1, amount `1.00`. `[]byte` is the body. `bytes.NewReader` lets `http.Client` read it again on each post. A `string` would also work. The reader is created inside the loop by wrapping the same bytes.
5. `client.Post` is the whole call. The code closes the body on every response, including errors, so connections return to the pool. A status other than `201` stops the program. It does not retry.

Each post is a normal order. The catalog accepts `SKU-1`, the outbox publishes, and the consumer projects the row. Ten posts are ten traces.

## What this is in Java

A `main` with `HttpClient` and a for-loop. There is no Spring, no interface, and no test framework. When a Go file in the gateway or the consumer feels dense, come back here. The language in those files is the same language as this loop.
