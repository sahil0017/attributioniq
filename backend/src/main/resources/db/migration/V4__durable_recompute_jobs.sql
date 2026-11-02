ALTER TABLE session_recompute_queue
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    ADD COLUMN available_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN last_error text;

ALTER TABLE person_recompute_queue
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    ADD COLUMN available_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN last_error text;

CREATE INDEX session_recompute_available_idx
    ON session_recompute_queue (available_at, enqueued_at);

CREATE INDEX person_recompute_available_idx
    ON person_recompute_queue (available_at, enqueued_at);

CREATE TABLE order_recompute_queue (
    order_id text PRIMARY KEY REFERENCES orders(order_id) ON DELETE CASCADE,
    reason text NOT NULL,
    enqueued_at timestamptz NOT NULL DEFAULT now(),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    available_at timestamptz NOT NULL DEFAULT now(),
    last_error text
);

CREATE INDEX order_recompute_available_idx
    ON order_recompute_queue (available_at, enqueued_at);
