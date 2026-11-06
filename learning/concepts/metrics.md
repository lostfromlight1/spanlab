# Prometheus and Grafana

Jaeger answers "what happened to this order?" Grafana answers "how often are the services being called?"

## Prometheus

Prometheus pulls numbers. It does not receive them from the application. Every 5 seconds it calls:

- `http://order-service:8081/actuator/prometheus`
- `http://catalog-service:8083/actuator/prometheus`

That list is [ops/prometheus.yml](../../ops/prometheus.yml). Spring exposes the endpoint because each Java service has `micrometer-registry-prometheus` and `management.endpoints.web.exposure.include` contains `prometheus`. A counter you will see is `http_server_requests_seconds_count`. The gRPC client and server add `grpc_client_*` and `grpc_server_*`.

OTLP metric export is `enabled: false` on both Java services. The OpenTelemetry starter's default was `http://localhost:4318/v1/metrics`, which nothing is listening on. Trace export to Jaeger stays on. Prometheus is the metrics path.

The Prometheus UI is not published. Grafana reads Prometheus at `http://prometheus:9090` inside the Docker network.

## Grafana

Grafana on port 3000 is the page you open. Anonymous access is enabled so the lab opens without a login. The admin password is `spanlab`.

Two files under [ops/grafana/provisioning/](../../ops/grafana/provisioning/) are mounted into the container:

- `datasources/datasources.yml` registers Prometheus and Jaeger.
- `dashboards/spanlab.json` is the dashboard `spanlab`. The provider YAML next to it tells Grafana to load that folder.

The panel **HTTP requests** graphs:

```text
sum by (job) (rate(http_server_requests_seconds_count[1m]))
```

`rate` is the per-second increase over one minute. `sum by (job)` draws one line for `order-service` and one line for `catalog-service`. Open http://localhost:3000/d/spanlab/spanlab after a few orders. The lines stay at zero until the services have been called inside the time range.

The Jaeger datasource is there so you can jump from a graph to a trace. The trace itself is still read in the Jaeger UI on port 16686.
