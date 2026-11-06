-- Schema for the order service. Flyway runs this file once.
-- "order" is quoted because it is a SQL keyword.
-- amount is numeric(12,2). JSON carries the same value as a decimal string.
-- traceparent on the outbox row is how the publisher rejoins the request trace.
CREATE SCHEMA "order";

CREATE TABLE "order".orders (
    id          uuid PRIMARY KEY,
    customer_id text NOT NULL,
    sku         text NOT NULL,
    quantity    integer NOT NULL CHECK (quantity > 0),
    amount      numeric(12, 2) NOT NULL,
    status      text NOT NULL CHECK (status IN ('CREATED')),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE "order".outbox_event (
    id           uuid PRIMARY KEY,
    aggregate_id uuid NOT NULL,
    event_type   text NOT NULL,
    payload      jsonb NOT NULL,
    status       text NOT NULL CHECK (status IN ('NEW', 'PUBLISHED')),
    traceparent  text,
    tracestate   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE INDEX outbox_event_pending
    ON "order".outbox_event (status, created_at);
