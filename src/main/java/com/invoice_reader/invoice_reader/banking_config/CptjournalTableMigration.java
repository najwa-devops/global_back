package com.invoice_reader.invoice_reader.banking_config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CptjournalTableMigration {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureCptjournalTableExists() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS Cptjournal (
                        Numero      BIGINT         NOT NULL,
                        ndosjrn     VARCHAR(50),
                        nmois       INT,
                        Mois        VARCHAR(30),
                        ncompt      VARCHAR(20),
                        ecriture    VARCHAR(500),
                        debit       DECIMAL(15,2),
                        credit      DECIMAL(15,2),
                        valider     VARCHAR(5),
                        datcompl    DATE,
                        dat         INT,
                        annee       INT,
                        mnt_rester  DECIMAL(15,2),
                        PRIMARY KEY (Numero)
                    )
                    """);
            log.info("Table Cptjournal vérifiée/créée avec succès.");
        } catch (Exception e) {
            log.warn("Migration DB non appliquée (Cptjournal): {}", e.getMessage());
        }
    }
}
