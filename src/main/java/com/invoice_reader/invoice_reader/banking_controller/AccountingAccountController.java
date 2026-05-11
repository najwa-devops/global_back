package com.invoice_reader.invoice_reader.banking_controller;

import com.invoice_reader.invoice_reader.entity.account_tier.Account;
import com.invoice_reader.invoice_reader.repository.AccountDao;
import com.invoice_reader.invoice_reader.banking_services.ExternalComptesCatalogService;
import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.security.RequireRole;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/accounting/accounts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
@Slf4j
public class AccountingAccountController {

    private final AccountDao accountDao;
    private final ExternalComptesCatalogService externalComptesCatalogService;

    private List<Account> loadAccountsWithFallback(boolean activeOnly, String context) {
        try {
            List<Account> externalAccounts = externalComptesCatalogService.loadAccounts();
            if (externalAccounts != null && !externalAccounts.isEmpty()) {
                return externalAccounts;
            }
            log.warn("Catalogue comptes distant vide pour {}, fallback sur la table locale accounts", context);
        } catch (Exception e) {
            log.warn("Catalogue comptes distant indisponible pour {}, fallback sur la table locale accounts: {}", context, e.getMessage());
        }

        return activeOnly
                ? accountDao.findByActiveTrueOrderByCodeAsc()
                : accountDao.findAllByOrderByCodeAsc();
    }

    private java.util.stream.Stream<AccountOption> toOptions(List<Account> accounts) {
        if (accounts == null) {
            return java.util.stream.Stream.empty();
        }
        return accounts.stream()
                .map(account -> new AccountOption(account.getCode(), account.getLibelle()));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        List<Account> accounts = loadAccountsWithFallback(activeOnly, "/accounts");

        // Source locale historique (gardée commentée, ne pas supprimer):
        // List<Account> accounts = activeOnly
        //         ? accountDao.findByActiveTrueOrderByCodeAsc()
        //         : accountDao.findAllByOrderByCodeAsc();
        return ResponseEntity.ok(Map.of("count", accounts.size(), "accounts", accounts));
    }

    @GetMapping("/charges")
    public ResponseEntity<?> chargeAccounts() {
        List<AccountOption> options = toOptions(loadAccountsWithFallback(true, "/charges"))
                .filter(a -> a.code() != null && a.code().startsWith("6"))
                .toList();
        return ResponseEntity.ok(Map.of("count", options.size(), "accounts", options));
    }

    @GetMapping("/tva")
    public ResponseEntity<?> tvaAccounts() {
        List<AccountOption> options = toOptions(loadAccountsWithFallback(true, "/tva"))
                .filter(a -> a.code() != null && (a.code().startsWith("3455") || a.code().startsWith("4455")))
                .toList();
        return ResponseEntity.ok(Map.of("count", options.size(), "accounts", options));
    }

    @GetMapping("/fournisseurs")
    public ResponseEntity<?> fournisseurAccounts() {
        List<AccountOption> options = toOptions(loadAccountsWithFallback(true, "/fournisseurs"))
                .filter(a -> a.code() != null && a.code().startsWith("441"))
                .toList();
        return ResponseEntity.ok(Map.of("count", options.size(), "accounts", options));
    }

    @GetMapping("/options")
    public ResponseEntity<?> options() {
        List<AccountOption> options = toOptions(loadAccountsWithFallback(true, "/options"))
                .toList();

        // Source locale historique (gardée commentée, ne pas supprimer):
        // List<AccountOption> options = accountDao.findByActiveTrueOrderByCodeAsc()
        //         .stream()
        //         .map(account -> new AccountOption(account.getCode(), account.getLibelle()))
        //         .toList();

        return ResponseEntity.ok(Map.of("count", options.size(), "accounts", options));
    }

    private record AccountOption(String code, String libelle) {}
}
