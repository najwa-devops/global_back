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
public class BanqueReleveOcrTextMigration {

    private final DataSource dataSource;

    @PostConstruct
    public void ensureOcrTextColumnsLongText() {
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
            ensureLongTextColumn(connection, schema, "bank_statement", "raw_ocr_text");
            ensureLongTextColumn(connection, schema, "bank_statement", "cleaned_ocr_text");
        } catch (Exception e) {
            log.warn("Migration DB non appliquée (LONGTEXT OCR bank_statement): {}", e.getMessage());
        }
    }

    private void ensureLongTextColumn(Connection connection, String schema, String table, String column)
            throws Exception {
        String dataType = getColumnType(connection, schema, table, column);
        if (dataType == null) {
            return;
        }

        String normalizedType = dataType.toLowerCase(Locale.ROOT);
        if ("longtext".equals(normalizedType)) {
            return;
        }

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " LONGTEXT NULL");
        }
        log.info("Migration DB appliquée: colonne {}.{} convertie en LONGTEXT.", table, column);
    }

    private String getColumnType(Connection connection, String schema, String table, String column) throws Exception {
        String sql = """
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                  AND column_name = ?
                LIMIT 1
                """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
