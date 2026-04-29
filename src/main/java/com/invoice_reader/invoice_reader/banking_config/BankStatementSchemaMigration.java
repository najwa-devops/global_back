package com.invoice_reader.invoice_reader.banking_config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class BankStatementSchemaMigration {

    private final DataSource dataSource;

    @Value("${banking.allow-multiple-statements-per-period:true}")
    private boolean allowMultipleStatementsPerPeriod;

    @PostConstruct
    public void dropLegacyUniqueConstraintIfNeeded() {
        if (!allowMultipleStatementsPerPeriod) {
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null) {
                return;
            }

            String normalizedProduct = product.toLowerCase(Locale.ROOT);
            if (!normalizedProduct.contains("mysql") && !normalizedProduct.contains("mariadb")) {
                return;
            }

            String schema = connection.getCatalog();
            if (!indexExists(connection, schema, "bank_statement", "uk_rib_year_month")) {
                // continue
            }

            addColumnIfMissing(connection, schema, "bank_statement", "client_validated",
                    "ALTER TABLE bank_statement ADD COLUMN client_validated BOOLEAN NOT NULL DEFAULT FALSE");
            addColumnIfMissing(connection, schema, "bank_statement", "client_validated_at",
                    "ALTER TABLE bank_statement ADD COLUMN client_validated_at DATETIME NULL");
            addColumnIfMissing(connection, schema, "bank_statement", "client_validated_by",
                    "ALTER TABLE bank_statement ADD COLUMN client_validated_by VARCHAR(50) NULL");

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("ALTER TABLE bank_statement DROP INDEX uk_rib_year_month");
            }
            log.warn("Migration DB appliquée: index unique uk_rib_year_month supprimé (doublons autorisés).");
        } catch (Exception e) {
            log.warn("Migration DB non appliquée (suppression uk_rib_year_month): {}", e.getMessage());
        }
    }

    private void addColumnIfMissing(Connection connection, String schema, String table, String columnName, String alterSql) throws Exception {
        if (columnExists(connection, schema, table, columnName)) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(alterSql);
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

    private boolean indexExists(Connection connection, String schema, String table, String indexName) throws Exception {
        String sql = """
                SELECT 1
                FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name = ?
                  AND index_name = ?
                LIMIT 1
                """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
