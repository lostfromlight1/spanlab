# Use the lab

The stack is already up. If `docker compose ps` does not show the gateway as healthy, follow [Set up a new machine](02-new-machine.md) first. Every call from your machine goes to port 8080.

These commands are PowerShell. `curl.exe` is the real curl. PowerShell's `curl` is an alias for `Invoke-WebRequest` and does not take the same flags. Keep the JSON in single quotes. A backslash before a double quote ends the string early and the command fails.

## Create an order

`SKU-1` and `SKU-2` are known. Anything else is rejected.

```powershell
curl.exe -sS -D - --data-raw '{"customerId":"cust-1001","sku":"SKU-1","quantity":2,"amount":"19.99"}' -H "Content-Type: application/json" http://localhost:8080/api/v1/orders
```

A healthy response is `201` and a JSON body. Copy `id`. That is the order id.

The first create after a cold start can return `504`. The order service is opening its database pool and the Kafka producer on that first minute. Run the same command again. The second call should be `201`.

`amount` is a decimal string. `"19.99"` is valid. `19.999` has three decimal places and is `400`.

## Read it back

The order row exists as soon as the `201` returns:

```powershell
curl.exe -sS http://localhost:8080/api/v1/orders/<orderId>
```

The projection is written after the publisher sends to Kafka, about a second later. If this returns `404`, wait and run it again:

```powershell
curl.exe -sS http://localhost:8080/api/v1/projections/<orderId>
```

Both bodies should show `sku` `SKU-1`, quantity `2`, and amount `19.99`.

## Requests that should fail

Unknown SKU. Nothing is written.

```powershell
curl.exe -sS -D - --data-raw '{"customerId":"cust-1001","sku":"SKU-9","quantity":1,"amount":"19.99"}' -H "Content-Type: application/json" http://localhost:8080/api/v1/orders
```

Expect `400` and detail `sku is unknown`.

Quantity zero:

```powershell
curl.exe -sS -D - --data-raw '{"customerId":"cust-1001","sku":"SKU-1","quantity":0,"amount":"19.99"}' -H "Content-Type: application/json" http://localhost:8080/api/v1/orders
```

Expect `400` and detail `quantity must be at least 1`.

## See the trace

Open http://localhost:16686. In Search, set Service to `gateway` and click Find Traces. Open the newest `POST /api/v1/orders`.

One trace contains:

- gateway `POST /api/v1/orders`
- gateway `HTTP POST` to the order service
- order-service `http post /api/v1/orders`
- `outbox.publish`
- consumer `order.created`

`order.created` hangs under `outbox.publish`. That is the outbox row carrying the trace across the gap after HTTP returned. [Tracing](concepts/tracing.md) explains the header.

## See the graphs

Open http://localhost:3000/d/spanlab/spanlab. Anonymous access is on, so the board loads without a login. The admin password, if a page asks, is `spanlab`.

The panel **HTTP requests** draws one line for `order-service` and one for `catalog-service`. It stays empty until those processes have been called inside the selected time range. Create a few orders, set the range to Last 15 minutes, and the lines move. [Prometheus and Grafana](concepts/metrics.md) explains the query.

## Ten orders at once

```powershell
docker compose --profile load run --rm loadgen
```

The program prints `created 10 orders` and the container is removed. Each post is a normal `SKU-1` order, so Jaeger gains ten traces and the projection table gains ten rows.

## Look at the rows

Postgres is not published on your machine. Ask the container:

```powershell
docker compose exec postgres psql -U spanlab -d spanlab -c 'SELECT id, sku, status FROM "order".orders ORDER BY created_at DESC LIMIT 5;'
```

The outbox row for a published order has status `PUBLISHED`:

```powershell
docker compose exec postgres psql -U spanlab -d spanlab -c 'SELECT id, status, traceparent FROM "order".outbox_event ORDER BY created_at DESC LIMIT 5;'
```

## Stop

```powershell
docker compose down
```
