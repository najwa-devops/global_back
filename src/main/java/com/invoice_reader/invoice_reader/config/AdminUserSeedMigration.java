package com.invoice_reader.invoice_reader.config;

import com.invoice_reader.invoice_reader.entity.auth.UserAccount;
import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.repository.UserAccountDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminUserSeedMigration implements CommandLineRunner {

    private final UserAccountDao userAccountDao;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            if (!waitForUserAccountsTable()) {
                log.warn("Skipping admin user seed: table 'user_accounts' not found");
                return;
            }

            String adminUsername = "admin";

            if (userAccountDao.existsByUsername(adminUsername)) {
                log.info("Default admin user already exists. Skipping seed.");
                return;
            }

            UserAccount adminUser = new UserAccount();
            adminUser.setUsername(adminUsername);
            adminUser.setPassword("admin123");
            adminUser.setRole(UserRole.ADMIN);
            adminUser.setDisplayName("Administrator");
            adminUser.setActive(true);

            userAccountDao.save(adminUser);
            log.info("Default admin user created with username '{}'.", adminUsername);
        } catch (DataAccessException e) {
            log.warn("Skipping admin user seed: {}", e.getMostSpecificCause() != null
                    ? e.getMostSpecificCause().getMessage()
                    : e.getMessage());
        } catch (Exception e) {
            log.warn("Skipping admin user seed due to unexpected error: {}", e.getMessage());
        }
    }

    private boolean waitForUserAccountsTable() {
        for (int attempt = 1; attempt <= 20; attempt++) {
            if (userAccountsTableExists()) {
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

    private boolean userAccountsTableExists() {
        Boolean exists = jdbcTemplate.execute((java.sql.Connection connection) -> {
            String catalog = connection.getCatalog();
            try (ResultSet rs = connection.getMetaData().getTables(catalog, null, "user_accounts", new String[] { "TABLE" })) {
                if (rs.next()) {
                    return true;
                }
            }
            try (ResultSet rs = connection.getMetaData().getTables(catalog, null, "USER_ACCOUNTS", new String[] { "TABLE" })) {
                return rs.next();
            }
        });
        return Boolean.TRUE.equals(exists);
    }
}
