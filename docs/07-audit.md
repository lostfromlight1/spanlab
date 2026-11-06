# Audit of the study docs

Reviewed [README.md](../README.md), [01-overview.md](01-overview.md), [02-features.md](02-features.md), [03-implementation-plan.md](03-implementation-plan.md), and [04-database.md](04-database.md) on 2026-11-06. The repo had those files and no services. This audit checks them against the locked lab: an order pipeline, Spring Boot 4.1.2, a Go gateway and consumer, a transactional outbox, Kafka, Postgres, and one trace, plus a short later list.

## Verdict

The four docs agree with each other and with that scope. They are a sound explanation of the lab. They were not yet a build contract: image tags, HTTP bodies, and error codes were still open. [The final plan](05-final-implementation-plan.md) closes those openings. The study docs stay the explanation.

## What holds

- The path is the same in every doc. Gateway, one Java transaction, a later publisher, Kafka, an idempotent consumer, Jaeger.
- The reason for storing `traceparent` on the outbox row is stated in the overview and used by the implementation and database docs.
- At-least-once delivery is the same story everywhere. A crash after the Kafka send and before `PUBLISHED` sends the event again. The projection primary key keeps a single row.
- Core features and the six build steps match. Ports match: host `8080` and `16686`; internal `8081`, `8082`, `5432`, `9092`, `4318`.
- The example ids are the same in the implementation doc and the database doc: order `6f1c0c3e-8a1b-4d2e-9c55-1b7e2a0d4f10`, event `9c2e7a11-0b44-4f6a-8d21-77a1c5e9b003`.
- `"order"` is called out as a keyword that must be quoted. The DDL matches the column list the study plan promised.
- The later list is the same in the features doc and the implementation doc, and both mark it outside this build.
- Spring Boot 4.1.2 starter names were checked on Maven Central: `webmvc`, `data-jpa`, `kafka`, `flyway`, `actuator`, and `opentelemetry`. The trace export property `management.opentelemetry.tracing.export.otlp.endpoint` matches the 4.1 configuration.

## Gaps, and where they are closed

1. Kafka and Jaeger had no tags. The final plan pins `apache/kafka:4.2.2` (the client line in the Boot 4.1.2 BOM; current Kafka is 4.4.0) and `jaegertracing/all-in-one:1.76.0` (newest all-in-one tag on Docker Hub at audit time).
2. `management.tracing.sampling.probability` is still a fallback. The export endpoint was verified. The sampling key was taken from Boot 4 writeups, and the implementation doc already says to switch if 4.1.2 rejects it. The final plan keeps that instruction.
3. `flyway-database-postgresql` 12.4.0 comes from the Boot 4.0.9 to 4.1.2 dependency diff, not from reading the BOM entry for that module. The final plan says to take the version Gradle resolves from the BOM.
4. The study docs never showed the `201` body, the projection body, `400`, or `404`. The final plan defines both JSON bodies and both status codes.
5. "Latest" projection was unnamed. The final plan returns the row with the greatest `processed_at`.
6. Step 1 said to confirm Postgres from another container, and the network section does not publish `5432`. The final plan gives the `docker compose exec` command.
7. The database doc says the primary key "rejects" a duplicate, which can be read as a failed insert. The implementation doc says `ON CONFLICT (event_id) DO NOTHING`. The final plan keeps `DO NOTHING`, so a duplicate is a successful no-op and the consumer still commits the offset.
8. Compose health checks and startup order were unnamed. The final plan sets them: Postgres and Kafka must be healthy; Jaeger must be started; the gateway waits for both apps.

## Left as written

- The implementation doc records Java 21.0.9, Go 1.27.3, and that Docker was stopped when the lab was sketched. Those are notes from that session. The final plan does not depend on them.
- The pending-outbox index is `(status, created_at)` on every row, which is what the study plan asked for. A partial index on `NEW` rows would be smaller. It is not required for this build.
- No service code was part of this audit. The tracker starts at step 1, not started.
