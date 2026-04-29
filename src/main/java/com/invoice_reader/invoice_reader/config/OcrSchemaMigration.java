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

/**
 * Migration DDL idempotente pour les nouvelles colonnes OCR Upgrade.
 * Ajoute les colonnes OCR communes sur les tables dynamic_invoice et sales_invoice
 * si elles n'existent pas encore.
 * Pattern identique à BankStatementSchemaMigration.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OcrSchemaMigration {

    private final DataSource dataSource;

    @PostConstruct
    public void addOcrUpgradeColumns() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null) return;
            String normalizedProduct = product.toLowerCase(Locale.ROOT);
            if (!normalizedProduct.contains("mysql") && !normalizedProduct.contains("mariadb")) return;

            String schema = connection.getCatalog();

            addColumnIfMissing(connection, schema, "dynamic_invoice", "cleaned_ocr_text",
                    "ALTER TABLE dynamic_invoice ADD COLUMN cleaned_ocr_text TEXT NULL");
            addColumnIfMissing(connection, schema, "dynamic_invoice", "scanned",
                    "ALTER TABLE dynamic_invoice ADD COLUMN scanned BOOLEAN NULL DEFAULT FALSE");
            addColumnIfMissing(connection, schema, "dynamic_invoice", "document_type",
                    "ALTER TABLE dynamic_invoice ADD COLUMN document_type VARCHAR(50) NULL");
            addColumnIfMissing(connection, schema, "dynamic_invoice", "amounts_valid",
                    "ALTER TABLE dynamic_invoice ADD COLUMN amounts_valid BOOLEAN NULL");
            addColumnIfMissing(connection, schema, "dynamic_invoice", "validation_message",
                    "ALTER TABLE dynamic_invoice ADD COLUMN validation_message VARCHAR(500) NULL");
            addColumnIfMissing(connection, schema, "dynamic_invoice", "duplicate_level",
                    "ALTER TABLE dynamic_invoice ADD COLUMN duplicate_level VARCHAR(20) NULL DEFAULT 'NONE'");
            addColumnIfMissing(connection, schema, "dynamic_invoice", "duplicate_of_id",
                    "ALTER TABLE dynamic_invoice ADD COLUMN duplicate_of_id BIGINT NULL");

            addCommonOcrColumns(connection, schema, "sales_invoice");

            log.info("OcrSchemaMigration: vérification des colonnes OCR Upgrade terminée.");
        } catch (Exception e) {
            log.warn("OcrSchemaMigration: migration non appliquée: {}", e.getMessage());
        }
    }

    private void addColumnIfMissing(Connection connection, String schema, String table,
                                     String columnName, String alterSql) throws Exception {
        if (!columnExists(connection, schema, table, columnName)) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(alterSql);
                log.info("OcrSchemaMigration: colonne ajoutée — {}.{}", table, columnName);
            }
        }
    }

    private void addCommonOcrColumns(Connection connection, String schema, String table) throws Exception {
        addColumnIfMissing(connection, schema, table, "cleaned_ocr_text",
                "ALTER TABLE " + table + " ADD COLUMN cleaned_ocr_text TEXT NULL");
        addColumnIfMissing(connection, schema, table, "scanned",
                "ALTER TABLE " + table + " ADD COLUMN scanned BOOLEAN NULL DEFAULT FALSE");
        addColumnIfMissing(connection, schema, table, "document_type",
                "ALTER TABLE " + table + " ADD COLUMN document_type VARCHAR(50) NULL");
        addColumnIfMissing(connection, schema, table, "amounts_valid",
                "ALTER TABLE " + table + " ADD COLUMN amounts_valid BOOLEAN NULL");
        addColumnIfMissing(connection, schema, table, "validation_message",
                "ALTER TABLE " + table + " ADD COLUMN validation_message VARCHAR(500) NULL");
        addColumnIfMissing(connection, schema, table, "duplicate_level",
                "ALTER TABLE " + table + " ADD COLUMN duplicate_level VARCHAR(20) NULL DEFAULT 'NONE'");
        addColumnIfMissing(connection, schema, table, "duplicate_of_id",
                "ALTER TABLE " + table + " ADD COLUMN duplicate_of_id BIGINT NULL");
    }

    private boolean columnExists(Connection connection, String schema, String table,
                                  String columnName) throws Exception {
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
