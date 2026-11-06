# Set up a new machine

You need Docker to run the lab. Java and Go are already inside the images, so you can follow an order without installing either one. Install them when you want to read a stack trace or change a file and rebuild.

## Install

| Tool | Why | Version this lab was built with |
| --- | --- | --- |
| Git | Clone the repo | any current Git |
| Docker Desktop | Builds the images and runs every process | Engine 29 |
| Java 21 | Optional. Only for reading or compiling the order and catalog services on the host | 21.0.9 |
| Go | Optional. Only for reading or compiling the gateway, consumer, and load generator on the host | 1.27.3 |

Start Docker Desktop and wait until it says the engine is running. On Windows the shortcut is often `Docker Desktop` in the Start menu. One machine in this lab launched it from `%LocalAppData%\Programs\DockerDesktop\Docker Desktop.exe` because it was not under `Program Files`.

## Clone and start

From the directory where you keep code:

```powershell
git clone <the spanlab repo url> spanlab
cd spanlab
docker compose up --build -d
```

The first start is slow. Gradle downloads dependencies and builds the order service and the catalog service inside Docker. Later starts reuse those images.

Wait until the app containers are healthy:

```powershell
docker compose ps
```

`gateway`, `order-service`, `catalog-service`, and `consumer` should say `healthy`. `grafana` and `prometheus` say `Up` and have no health check. `kafka-init` exits after it creates the topics. That exit is success.

Then open:

| URL | What you should see |
| --- | --- |
| http://localhost:8080/healthz | the word `ok` |
| http://localhost:16686 | the Jaeger UI |
| http://localhost:3000 | Grafana. The board is http://localhost:3000/d/spanlab/spanlab |

The next page, [Use the lab](03-use-the-lab.md), creates an order and finds its trace.

## If an image pull times out

Docker Hub sometimes fails with `TLS handshake timeout` while fetching an oauth token. Pull the same image from the mirror, then tag it with the name Compose expects.

Library images (`postgres`, `golang`, `debian`, `eclipse-temurin`, `gradle`):

```powershell
docker pull docker.arvancloud.ir/library/postgres:16
docker tag docker.arvancloud.ir/library/postgres:16 postgres:16
```

The same two commands for `golang:1.27-bookworm`, `debian:bookworm-slim`, `eclipse-temurin:21-jre`, and `gradle:8.14.3-jdk21`.

Other images, using the owner name instead of `library`:

```powershell
docker pull docker.arvancloud.ir/prom/prometheus:v3.2.1
docker tag docker.arvancloud.ir/prom/prometheus:v3.2.1 prom/prometheus:v3.2.1

docker pull docker.arvancloud.ir/grafana/grafana:11.6.0
docker tag docker.arvancloud.ir/grafana/grafana:11.6.0 grafana/grafana:11.6.0
```

Also tag `apache/kafka:4.2.2` and `jaegertracing/all-in-one:1.76.0` the same way if those pulls fail. Then run `docker compose up --build -d` again.

## What you do not install

The images do not run `apt-get`. Debian's package mirrors were unreachable when the Dockerfiles were written, so health checks use Bash instead of `curl`. Leave the Dockerfiles without `apt`.

`kubectl` is optional. [k8s/spanlab.yaml](../k8s/spanlab.yaml) describes the same stack and is not applied by `docker compose up`. Apply it only on a cluster whose API server answers.

## Stop

```powershell
docker compose down
```

That stops the containers and keeps the Postgres volume, so orders from the last run are still there on the next `up`. `docker compose down -v` deletes that volume too.
