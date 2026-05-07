package com.invoice_reader.invoice_reader.banking_controller;

import com.invoice_reader.invoice_reader.entity.account_tier.Account;
import com.invoice_reader.invoice_reader.repository.AccountDao;
import com.invoice_reader.invoice_reader.banking_services.ExternalComptesCatalogService;
import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.security.RequireRole;
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
public class AccountingAccountController {

    private final AccountDao accountDao;
    private final ExternalComptesCatalogService externalComptesCatalogService;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        // Source active: table distante "Comptes" (numero, libelle) sur le serveur externe.
        // Fallback: table locale si la source distante est indisponible.
        List<Account> accounts;
        try {
            accounts = externalComptesCatalogService.loadAccounts();
        } catch (Exception e) {
            accounts = activeOnly
                    ? accountDao.findByActiveTrueOrderByCodeAsc()
                    : accountDao.findAllByOrderByCodeAsc();
        }

        // Source locale historique (gardée commentée, ne pas supprimer):
        // List<Account> accounts = activeOnly
        //         ? accountDao.findByActiveTrueOrderByCodeAsc()
        //         : accountDao.findAllByOrderByCodeAsc();
        return ResponseEntity.ok(Map.of("count", accounts.size(), "accounts", accounts));
    }

    @GetMapping("/charges")
    public ResponseEntity<?> chargeAccounts() {
        List<Account> all;
        try {
            all = externalComptesCatalogService.loadAccounts();
        } catch (Exception e) {
            all = accountDao.findByActiveTrueOrderByCodeAsc();
        }
        List<AccountOption> options = all.stream()
                .filter(a -> a.getCode() != null && a.getCode().startsWith("6"))
                .map(a -> new AccountOption(a.getCode(), a.getLibelle()))
                .toList();
        return ResponseEntity.ok(Map.of("count", options.size(), "accounts", options));
    }

    @GetMapping("/tva")
    public ResponseEntity<?> tvaAccounts() {
        List<Account> all;
        try {
            all = externalComptesCatalogService.loadAccounts();
        } catch (Exception e) {
            all = accountDao.findByActiveTrueOrderByCodeAsc();
        }
        List<AccountOption> options = all.stream()
                .filter(a -> a.getCode() != null && (a.getCode().startsWith("3455") || a.getCode().startsWith("4455")))
                .map(a -> new AccountOption(a.getCode(), a.getLibelle()))
                .toList();
        return ResponseEntity.ok(Map.of("count", options.size(), "accounts", options));
    }

    @GetMapping("/fournisseurs")
    public ResponseEntity<?> fournisseurAccounts() {
        List<Account> all;
        try {
            all = externalComptesCatalogService.loadAccounts();
        } catch (Exception e) {
            all = accountDao.findByActiveTrueOrderByCodeAsc();
        }
        List<AccountOption> options = all.stream()
                .filter(a -> a.getCode() != null && a.getCode().startsWith("441"))
                .map(a -> new AccountOption(a.getCode(), a.getLibelle()))
                .toList();
        return ResponseEntity.ok(Map.of("count", options.size(), "accounts", options));
    }

    @GetMapping("/options")
    public ResponseEntity<?> options() {
        List<AccountOption> options;
        try {
            options = externalComptesCatalogService.loadAccounts()
                .stream()
                .map(account -> new AccountOption(account.getCode(), account.getLibelle()))
                .toList();
        } catch (Exception e) {
            options = accountDao.findByActiveTrueOrderByCodeAsc()
                    .stream()
                    .map(account -> new AccountOption(account.getCode(), account.getLibelle()))
                    .toList();
        }

        // Source locale historique (gardée commentée, ne pas supprimer):
        // List<AccountOption> options = accountDao.findByActiveTrueOrderByCodeAsc()
        //         .stream()
        //         .map(account -> new AccountOption(account.getCode(), account.getLibelle()))
        //         .toList();

        return ResponseEntity.ok(Map.of("count", options.size(), "accounts", options));
    }

    private record AccountOption(String code, String libelle) {}
}
