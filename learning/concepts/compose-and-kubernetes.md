# Compose and Kubernetes

Both files describe the same lab: Postgres, Kafka, Jaeger, Prometheus, Grafana, catalog, order service, consumer, and gateway. Compose is what you run on this machine. The Kubernetes file is the same processes written as API objects. It has not been applied here, because this machine's API server does not answer.

## Compose

[docker-compose.yml](../../docker-compose.yml) is the running definition. `docker compose up` builds the local images and starts the containers. A service with `depends_on` and `condition: service_healthy` waits until the dependency's health check passes.

The health check for the Java and Go apps is [ops/http-health.sh](../../ops/http-health.sh). It opens a TCP connection with Bash, sends `GET`, and looks for `HTTP/1.1 200`. The images do not have `curl`. Debian's package mirrors were unreachable when the images were built, so the check stays inside Bash.

| Service | Published to your machine | Inside the network |
| --- | --- | --- |
| gateway | 8080 | 8080 |
| jaeger | 16686 | 16686 and OTLP 4318 |
| grafana | 3000 | 3000 |
| order-service | no | 8081 |
| catalog-service | no | HTTP 8083, gRPC 9090 |
| consumer | no | 8082 |
| postgres | no | 5432 |
| kafka | no | 9092 |
| prometheus | no | 9090 |

`kafka-init` is a one-shot container. It creates `order-events` and `order-events-dlq`, then exits. The consumer starts after that exit.

`loadgen` has `profiles: ["load"]`. `docker compose up` does not start it. `docker compose --profile load run --rm loadgen` runs it once and removes the container.

Images you build are tagged `spanlab/<name>:local`. Postgres, Kafka, Jaeger, Prometheus, and Grafana are pulled images. When Docker Hub times out, the working mirror has been `docker.arvancloud.ir`, retagged to the name Compose expects.

## Kubernetes

[k8s/spanlab.yaml](../../k8s/spanlab.yaml) is one file of objects in namespace `spanlab`:

- A Deployment runs the container. A Service gives it a DNS name, such as `kafka` or `order-service`.
- Kafka sets `hostname: kafka` so the advertised listener `PLAINTEXT://kafka:9092` matches the Service name, the same way Compose sets `hostname: kafka`.
- `kafka-init` is a Job. It runs the same two `kafka-topics.sh` commands, then finishes.
- Readiness probes are HTTP `GET` on `/actuator/health` or `/healthz`. Kubernetes speaks HTTP itself, so the Bash script is not copied in.
- `imagePullPolicy: IfNotPresent` uses a local image when one is already loaded. The Java and Go images are `spanlab/...:local`, which exist after `docker compose build`. A cluster still needs those images loaded into it.

Applying the file is `kubectl apply -f k8s/spanlab.yaml` on a cluster that answers. This machine has a `kubectl` client and no answering API server, so the file is the deliverable.

Compose and Kubernetes are two ways to say "these processes, these env vars, this order." The application code does not know which one started it. It reads `ORDER_URL`, `DATABASE_URL`, and the Spring datasource URL.
