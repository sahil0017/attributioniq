CREATE OR REPLACE FUNCTION channel_daily(
    p_from date,
    p_to date,
    p_model text DEFAULT 'last_click'
)
    RETURNS TABLE (
        day date,
        channel text,
        orders bigint,
        revenue_inr numeric,
        spend_inr numeric,
        roas numeric
    )
    LANGUAGE plpgsql
    STABLE
AS $$
BEGIN
    IF p_from IS NULL OR p_to IS NULL OR p_from > p_to THEN
        RAISE EXCEPTION 'Invalid reporting date range';
    END IF;

    IF p_model IS NULL OR p_model NOT IN ('last_click', 'first_click') THEN
        RAISE EXCEPTION 'Unsupported attribution model: %', p_model;
    END IF;

    RETURN QUERY
        WITH rev AS (
            SELECT
                (a.order_at AT TIME ZONE 'UTC')::date AS day,
                a.channel,
                count(*)::bigint AS orders,
                sum(a.revenue_inr) AS revenue_inr
            FROM order_attribution a
            WHERE a.model = p_model
              AND a.counted
              AND a.order_at >= (p_from::timestamp AT TIME ZONE 'UTC')
              AND a.order_at < ((p_to + 1)::timestamp AT TIME ZONE 'UTC')
            GROUP BY 1, 2
        ),
        spend AS (
            SELECT
                s.spend_date AS day,
                s.channel,
                sum(s.spend_inr) AS spend_inr
            FROM ad_spend_current s
            WHERE s.spend_date BETWEEN p_from AND p_to
            GROUP BY 1, 2
        ),
        combined AS (
            SELECT
                coalesce(r.day, s.day) AS day,
                coalesce(r.channel, s.channel) AS channel,
                coalesce(r.orders, 0)::bigint AS orders,
                coalesce(r.revenue_inr, 0::numeric) AS revenue_inr,
                coalesce(s.spend_inr, 0::numeric) AS spend_inr
            FROM rev r
            FULL OUTER JOIN spend s
              ON s.day = r.day AND s.channel = r.channel
        )
    SELECT
        c.day,
        c.channel,
        c.orders,
        c.revenue_inr,
        c.spend_inr,
        round(c.revenue_inr / nullif(c.spend_inr, 0), 2) AS roas
    FROM combined c
    ORDER BY c.day, c.channel;
END;
$$;
