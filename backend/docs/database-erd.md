# AttributionIQ database ER diagram

This diagram covers the persisted tables and their declared foreign-key
relationships. It is maintained separately from the project README. The
migrations in `src/main/resources/db/migration` are the source of truth.

```mermaid
erDiagram
    FX_RATES {
        text currency PK
        numeric to_inr
        text note
    }

    SETTINGS {
        text key PK
        text value
        text note
    }

    CHANNEL_RULES {
        serial rule_id PK
        int priority
        text match_source
        text match_click_id_type
        text match_referrer_host
        text channel
        text platform
    }

    EVENTS {
        text event_id PK
        text event_name
        timestamptz event_ts
        timestamptz received_at
        text anonymous_id
        text email_hash
        text order_id
        text currency FK
        bigint session_id FK
        boolean is_bot
        boolean is_test
    }

    ORDER_WEBHOOKS {
        text order_id PK
        timestamptz updated_at PK
        timestamptz created_at
        text email_hash
        numeric total
        text currency FK
        text financial_status
    }

    ORDERS {
        text order_id PK
        timestamptz created_at
        timestamptz updated_at
        text email_hash
        numeric total
        text currency FK
        text financial_status
        boolean is_test
    }

    AD_SPEND_PULLS {
        date spend_date PK
        text platform PK
        text campaign PK
        timestamptz pulled_at PK
        numeric spend
        text currency FK
    }

    REJECTS {
        bigint id PK
        text endpoint
        text reason
        jsonb payload
        timestamptz received_at
    }

    PERSONS {
        bigint person_id PK
        bigint merged_into FK
        timestamptz created_at
    }

    PERSON_IDENTIFIERS {
        text id_type PK
        text id_value PK
        bigint person_id FK
    }

    SESSIONS {
        bigint session_id PK
        text anonymous_id
        bigint person_id FK
        timestamptz started_at
        timestamptz ended_at
        int event_count
        text channel
        text platform
    }

    ORDER_ATTRIBUTION {
        text order_id PK, FK
        text model PK
        bigint person_id FK
        bigint session_id FK
        text channel
        text platform
        text campaign
        timestamptz touch_at
        timestamptz order_at
        numeric revenue_inr
        boolean counted
    }

    SESSION_RECOMPUTE_QUEUE {
        text anonymous_id PK
        text reason
        int attempt_count
        timestamptz enqueued_at
        timestamptz available_at
    }

    PERSON_RECOMPUTE_QUEUE {
        bigint person_id PK, FK
        text reason
        int attempt_count
        timestamptz enqueued_at
        timestamptz available_at
    }

    ORDER_RECOMPUTE_QUEUE {
        text order_id PK, FK
        text reason
        int attempt_count
        timestamptz enqueued_at
        timestamptz available_at
    }

    FX_RATES ||--o{ EVENTS : "currency"
    FX_RATES ||--o{ ORDER_WEBHOOKS : "currency"
    FX_RATES ||--o{ ORDERS : "currency"
    FX_RATES ||--o{ AD_SPEND_PULLS : "currency"

    PERSONS o|--o{ PERSONS : "merged_into"
    PERSONS ||--o{ PERSON_IDENTIFIERS : "person_id"
    PERSONS o|--o{ SESSIONS : "person_id"
    PERSONS o|--o{ ORDER_ATTRIBUTION : "person_id"
    PERSONS ||--o| PERSON_RECOMPUTE_QUEUE : "person_id"

    SESSIONS o|--o{ EVENTS : "session_id"
    SESSIONS o|--o{ ORDER_ATTRIBUTION : "session_id"

    ORDERS ||--o{ ORDER_ATTRIBUTION : "order_id"
    ORDERS ||--o| ORDER_RECOMPUTE_QUEUE : "order_id"
```

## Relationship notes

- `EVENTS.anonymous_id` and `SESSIONS.anonymous_id` are related by application
  logic, not by a foreign key. Anonymous IDs are also represented in
  `PERSON_IDENTIFIERS` with `id_type = 'anon'`.
- `email_hash` on events and orders is a matching attribute, not a foreign key.
  The identity relationship is represented by `PERSON_IDENTIFIERS` with
  `id_type = 'email'`.
- `ORDER_WEBHOOKS.order_id` has no declared foreign key to `ORDERS`; it is an
  append-only source history, while `ORDERS` stores the latest accepted state.
- `SESSION_RECOMPUTE_QUEUE.anonymous_id` intentionally has no foreign key.
- `CHANNEL_RULES` is consulted by the `classify_touch(...)` function. Channel
  values on sessions, attribution rows, and spend reports are derived strings,
  not normalized channel-table foreign keys.
- `ad_spend_current` is a view over the latest row in `AD_SPEND_PULLS` for each
  spend date/platform/campaign. `channel_daily(...)` is a reporting function
  combining current spend with attributed revenue; neither is a base table.
- `SETTINGS` stores policies such as the session gap and attribution window.
  `REJECTS` stores rejected-request metadata and has no relationship to accepted
  facts.

## Main migration objects

- `V1__initial_schema.sql`: bootstrap check table, removed by V3.
- `V2__init_attr_schema.sql`: reference, ingest, identity, session, attribution,
  queue, view, function, and reporting schema.
- `V3__remove_unused_schema_check.sql`: removes the bootstrap-only table.
- `V4__durable_recompute_jobs.sql`: retry fields/indexes and order recompute queue.
