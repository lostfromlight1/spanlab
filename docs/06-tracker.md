# Tracker

Execution follows [the final implementation plan](05-final-implementation-plan.md). One step is `in progress` at a time. Mark a step `done` only when its acceptance checks have passed.

| Step | Status | Acceptance |
| --- | --- | --- |
| 1. Infrastructure | done | Jaeger UI loads. `psql` prints `spanlab`. |
| 2. Order service write path | done | `201` create. Both rows share the request `traceparent`. `quantity: 0` returns `400` and writes nothing. |
| 3. Publisher | done | Outbox row becomes `PUBLISHED`. Kafka record key is the order id and headers carry the trace. |
| 4. Consumer | done | Projection row exists. A second delivery does not change it. GET returns `200`. |
| 5. Gateway | done | The three routes through port `8080` match the downstream responses. Outgoing `traceparent` is set. |
| 6. One trace | done | Jaeger shows gateway, Java server, `outbox.publish`, and `order.created` on one trace. |
| 7. Dead letter | done | Bad JSON is on `order-events-dlq` and the consumer commits. A later order still projects. |
| 8. HTTP health | done | Compose reports order-service, catalog-service, consumer, and gateway healthy from `GET` status `200`. |
| 9. Grafana | done | http://localhost:3000/d/spanlab/spanlab shows HTTP request rate for both Java services. |
| 10. Load generator | done | `docker compose --profile load run --rm loadgen` prints `created 10 orders`. |
| 11. Catalog gRPC | done | `SKU-1` creates an order. `SKU-9` returns `400` with detail `sku is unknown`. |
| 12. Kubernetes | done | `k8s/spanlab.yaml` matches the Compose stack. It was not applied: this machine's API server does not answer. |

## Notes

- 2026-11-06: Step 1 done. Jaeger UI at http://localhost:16686. `psql` returned `spanlab`. Images: `postgres:16`, `apache/kafka:4.2.2`, `jaegertracing/all-in-one:1.76.0`.
- 2026-11-06: Steps 2-6 done. Trace id `e90dd9657569f2600f1c17915eb4aef9`. Jaeger shows gateway `POST /api/v1/orders`, order-service `http post /api/v1/orders`, `outbox.publish`, and consumer `order.created`. `order.created` is a child of `outbox.publish`.
- Health checks send `GET /actuator/health` or `GET /healthz` with Bash `/dev/tcp` and require `HTTP/1.1 200`. `apt` still cannot install curl.
- `kafka-init` creates `order-events` and `order-events-dlq` before the consumer starts. The consumer SQL uses `IF NOT EXISTS` because it runs on every start.
- Trace export stays on `http://jaeger:4318/v1/traces`. OTLP metrics stay off. Prometheus scrapes `/actuator/prometheus`.
- 2026-11-06: Steps 7-12 done. Poison payload `not-json` was dead-lettered at offset 4. Order `a5dbf47a-470e-48f1-9bae-99891533b5b5` projected. Grafana dashboard `spanlab` shows catalog-service and order-service request rates. Load generator created 10 orders. `k8s/spanlab.yaml` was not applied.
