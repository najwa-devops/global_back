package com.invoice_reader.invoice_reader.database.migration;

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
public class AccountSchemaMigration {

    private final DataSource dataSource;

    @PostConstruct
    public void addTaxCodeColumn() {
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
            if (!tableExists(connection, schema, "accounts")) {
                return;
            }

            addColumnIfMissing(connection, schema, "accounts", "code_taxe",
                    "ALTER TABLE accounts ADD COLUMN code_taxe VARCHAR(20) NULL AFTER taux");

            log.info("AccountSchemaMigration: colonne code_taxe vérifiée.");
        } catch (Exception e) {
            log.warn("AccountSchemaMigration: migration non appliquée: {}", e.getMessage());
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
