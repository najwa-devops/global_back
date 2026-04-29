package com.invoice_reader.invoice_reader.config;

import com.invoice_reader.invoice_reader.entity.auth.Dossier;
import com.invoice_reader.invoice_reader.entity.auth.DossierGeneralParams;
import com.invoice_reader.invoice_reader.entity.auth.UserAccount;
import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.repository.DossierDao;
import com.invoice_reader.invoice_reader.repository.DossierGeneralParamsDao;
import com.invoice_reader.invoice_reader.repository.UserAccountDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultDossierSeedMigration implements CommandLineRunner {

    private static final String DEMO_CLIENT_USERNAME = "demo.client";

    private final UserAccountDao userAccountDao;
    private final DossierDao dossierDao;
    private final DossierGeneralParamsDao dossierGeneralParamsDao;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            if (!waitForCoreTables()) {
                log.warn("Skipping default dossier seed: required tables are not ready");
                return;
            }

            if (dossierDao.count() > 0) {
                log.info("Dossier table already contains data. Skipping default dossier seed.");
                return;
            }

            UserAccount comptable = userAccountDao.findByRole(UserRole.COMPTABLE).stream().findFirst()
                    .orElseGet(() -> userAccountDao.findByRole(UserRole.ADMIN).stream().findFirst().orElse(null));
            if (comptable == null) {
                log.warn("Skipping default dossier seed: no comptable or admin account found");
                return;
            }

            UserAccount client = userAccountDao.findByRole(UserRole.CLIENT).stream().findFirst()
                    .orElseGet(this::createDemoClient);
            if (client == null) {
                log.warn("Skipping default dossier seed: unable to create demo client");
                return;
            }

            Dossier dossier = new Dossier();
            dossier.setName("Dossier Démo");
            dossier.setClient(client);
            dossier.setComptable(comptable);
            dossier.setActive(true);
            int year = LocalDate.now().getYear();
            dossier.setExerciseStartDate(LocalDate.of(year, 1, 1));
            dossier.setExerciseEndDate(LocalDate.of(year, 12, 31));

            Dossier savedDossier = dossierDao.save(dossier);

            DossierGeneralParams params = new DossierGeneralParams();
            params.setDossier(savedDossier);
            params.setCompanyName("Entreprise Démo");
            params.setIce("000000000000000");
            params.setAllowValidatedDocumentDeletion(false);
            dossierGeneralParamsDao.save(params);

            log.info("Default dossier seeded with dossier id {}.", savedDossier.getId());
        } catch (DataAccessException e) {
            log.warn("Skipping default dossier seed: {}", e.getMostSpecificCause() != null
                    ? e.getMostSpecificCause().getMessage()
                    : e.getMessage());
        } catch (Exception e) {
            log.warn("Skipping default dossier seed due to unexpected error: {}", e.getMessage());
        }
    }

    private UserAccount createDemoClient() {
        if (userAccountDao.existsByUsername(DEMO_CLIENT_USERNAME)) {
            return userAccountDao.findByUsername(DEMO_CLIENT_USERNAME)
                    .filter(user -> user.getRole() == UserRole.CLIENT)
                    .orElse(null);
        }

        UserAccount client = new UserAccount();
        client.setUsername(DEMO_CLIENT_USERNAME);
        client.setPassword("client123");
        client.setRole(UserRole.CLIENT);
        client.setDisplayName("Client Démo");
        client.setActive(true);
        return userAccountDao.save(client);
    }

    private boolean waitForCoreTables() {
        return waitForTable("user_accounts") && waitForTable("dossiers") && waitForTable("dossier_general_params");
    }

    private boolean waitForTable(String tableName) {
        for (int attempt = 1; attempt <= 20; attempt++) {
            if (tableExists(tableName)) {
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

    private boolean tableExists(String tableName) {
        Boolean exists = jdbcTemplate.execute((java.sql.Connection connection) -> {
            String catalog = connection.getCatalog();
            try (ResultSet rs = connection.getMetaData().getTables(catalog, null, tableName, new String[] { "TABLE" })) {
                if (rs.next()) {
                    return true;
                }
            }
            try (ResultSet rs = connection.getMetaData().getTables(catalog, null, tableName.toUpperCase(), new String[] { "TABLE" })) {
                return rs.next();
            }
        });
        return Boolean.TRUE.equals(exists);
    }
}
