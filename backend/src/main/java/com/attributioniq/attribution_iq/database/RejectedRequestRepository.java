package com.attributioniq.attribution_iq.database;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RejectedRequestRepository {
    private final JdbcTemplate jdbc;

    public RejectedRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String endpoint, String reason, String method, String contentType, String requestId) {
        jdbc.update("""
                INSERT INTO rejects (endpoint, reason, payload)
                VALUES (?, ?, jsonb_build_object(
                    'method', ?::text,
                    'content_type', ?::text,
                    'request_id', ?::text
                ))
                """, endpoint, reason, method, contentType, requestId);
    }
}
