package com.invoice_reader.invoice_reader.banque.config;

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
public class BanqueTransactionScheMigration {

    private final DataSource dataSource;

    @PostConstruct
    public void addCmAppliedUserDisabledFlag() {
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
            if (!tableExists(connection, schema, "bank_transaction")) {
                return;
            }

            addColumnIfMissing(connection, schema, "bank_transaction",
                    "cm_applied_user_disabled",
                    "ALTER TABLE bank_transaction ADD COLUMN cm_applied_user_disabled BOOLEAN NOT NULL DEFAULT FALSE");

            log.info("BanqueTransactionScheMigration: colonne cm_applied_user_disabled vérifiée.");
        } catch (Exception e) {
            log.warn("BanqueTransactionScheMigration: migration non appliquée: {}", e.getMessage());
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
