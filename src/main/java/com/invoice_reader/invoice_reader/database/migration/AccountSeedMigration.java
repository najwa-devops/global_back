package com.invoice_reader.invoice_reader.database.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountSeedMigration implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Value("${account.seed.sql-path:C:/ptt/ayman/scan_comptes_clean.sql}")
    private String seedSqlPath;

    @Override
    public void run(String... args) {
        try {
            if (!waitForAccountsTable()) {
                log.warn("Skipping PCM Maroc seed: table 'accounts' not found");
                return;
            }

            if (seedFromCleanSqlFile()) {
                return;
            }

            seedFallbackAccounts();
        } catch (DataAccessException e) {
            log.warn("Skipping PCM Maroc seed: {}", e.getMostSpecificCause() != null
                    ? e.getMostSpecificCause().getMessage()
                    : e.getMessage());
        } catch (Exception e) {
            log.warn("Skipping PCM Maroc seed due to unexpected error: {}", e.getMessage());
        }
    }

    private boolean seedFromCleanSqlFile() {
        for (String candidate : List.of(
                seedSqlPath,
                "C:/ptt/ayman/accounts_insert.sql",
                "C:/ptt/ayman/scan_comptes_clean.sql"
        )) {
            try {
                Path path = Path.of(candidate);
                if (!Files.exists(path)) {
                    continue;
                }

                Resource resource = new FileSystemResource(path);
                jdbcTemplate.execute((java.sql.Connection connection) -> {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(resource, StandardCharsets.UTF_8));
                    return null;
                });

                log.info("Seed SQL exécuté depuis {}", candidate);
                return true;
            } catch (Exception e) {
                log.warn("Unable to seed accounts from {}: {}", candidate, e.getMessage());
            }
        }

        log.info("No external seed SQL file found, falling back to bundled seed");
        return false;
    }

    private void seedFallbackAccounts() {
        List<AccountRow> accounts = List.of(
                new AccountRow("111000000", "Capital social", 1),
                new AccountRow("114000000", "Réserves", 1),
                new AccountRow("115000000", "Report à nouveau", 1),
                new AccountRow("116000000", "Résultat de l’exercice", 1),
                new AccountRow("441100000", "Fournisseurs", 4),
                new AccountRow("345000000", "État – TVA", 4),
                new AccountRow("511000000", "Valeurs à encaisser", 5),
                new AccountRow("611000000", "Achats revendus", 6),
                new AccountRow("712000000", "Ventes de produits finis", 7)
        );

        String sql = """
            INSERT INTO accounts (numero, libelle, classe, liv, taux, created_at, updated_at)
            VALUES (?, ?, ?, true, 0, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                libelle = VALUES(libelle),
                classe = VALUES(classe),
                liv = true,
                updated_at = NOW()
            """;

        int updated = 0;
        for (AccountRow row : accounts) {
            updated += jdbcTemplate.update(sql, row.code(), row.libelle(), row.classe());
        }

        log.info("PCM Maroc fallback seed applied for {} comptes (rows affected={})", accounts.size(), updated);
    }

    private boolean waitForAccountsTable() {
        for (int attempt = 1; attempt <= 20; attempt++) {
            if (accountsTableExists()) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean accountsTableExists() {
        Boolean exists = jdbcTemplate.execute((java.sql.Connection connection) -> {
            String catalog = connection.getCatalog();
            try (ResultSet rs = connection.getMetaData().getTables(catalog, null, "accounts", new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
            try (ResultSet rs = connection.getMetaData().getTables(catalog, null, "ACCOUNTS", new String[]{"TABLE"})) {
                return rs.next();
            }
        });
        return Boolean.TRUE.equals(exists);
    }

    private record AccountRow(String code, String libelle, int classe) {}
}
