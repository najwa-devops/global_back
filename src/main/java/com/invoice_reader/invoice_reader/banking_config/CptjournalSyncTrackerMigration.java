package com.invoice_reader.invoice_reader.banking_config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CptjournalSyncTrackerMigration {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTrackerTableExists() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS cptjournal_sync_tracker (
                        statement_id BIGINT NOT NULL PRIMARY KEY,
                        synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        } catch (Exception e) {
            log.warn("Migration DB non appliquée (cptjournal_sync_tracker): {}", e.getMessage());
        }
    }
}
