package com.invoice_reader.invoice_reader.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class TierSchemaMigration {

    private final DataSource dataSource;

    @PostConstruct
    public void addCodeTierColumn() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null) {
                return;
            }

            String normalized = product.toLowerCase(Locale.ROOT);
            if (!normalized.contains("mysql") && !normalized.contains("mariadb")) {
                return;
            }

            String schema = connection.getCatalog();
            if (!tableExists(connection, schema, "tiers")) {
                return;
            }

            addColumnIfMissing(connection, schema, "tiers", "code_tier",
                    "ALTER TABLE tiers ADD COLUMN code_tier VARCHAR(80) NULL AFTER tier_number");
            addColumnIfMissing(connection, schema, "tiers", "default_charge_account_2",
                    "ALTER TABLE tiers ADD COLUMN default_charge_account_2 VARCHAR(9) NULL AFTER default_charge_account");
            addColumnIfMissing(connection, schema, "tiers", "tva_account_2",
                    "ALTER TABLE tiers ADD COLUMN tva_account_2 VARCHAR(9) NULL AFTER tva_account");
            addColumnIfMissing(connection, schema, "tiers", "default_tva_rate_2",
                    "ALTER TABLE tiers ADD COLUMN default_tva_rate_2 DOUBLE NULL AFTER default_tva_rate");

            log.info("TierSchemaMigration: colonnes auxiliaires vérifiées.");
        } catch (Exception e) {
            log.warn("TierSchemaMigration: migration non appliquée: {}", e.getMessage());
        }
    }

    private void addColumnIfMissing(Connection connection, String schema, String table,
                                    String columnName, String alterSql) throws Exception {
        if (columnExists(connection, schema, table, columnName)) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(alterSql);
        }
    }

    private boolean tableExists(Connection connection, String schema, String table) throws Exception {
        String sql = """
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_name = ?
                LIMIT 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean columnExists(Connection connection, String schema, String table, String columnName) throws Exception {
        String sql = """
                SELECT 1
                FROM information_schema.COLUMNS
                WHERE table_schema = ?
                  AND table_name = ?
                  AND column_name = ?
                LIMIT 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
