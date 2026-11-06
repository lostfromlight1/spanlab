-- Projection owned by the consumer. Embedded into the Go binary and
-- executed on every start, so every statement is IF NOT EXISTS.
-- event_id is the primary key. A duplicate delivery inserts nothing.
CREATE SCHEMA IF NOT EXISTS projection;

CREATE TABLE IF NOT EXISTS projection.processed_orders (
    event_id    uuid PRIMARY KEY,
    order_id    uuid NOT NULL,
    customer_id text NOT NULL,
    sku         text NOT NULL,
    quantity    integer NOT NULL,
    amount      numeric(12, 2) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS processed_orders_by_order
    ON projection.processed_orders (order_id);
