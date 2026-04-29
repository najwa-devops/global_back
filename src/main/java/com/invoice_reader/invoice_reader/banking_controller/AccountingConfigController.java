package com.invoice_reader.invoice_reader.banking_controller;

import com.invoice_reader.invoice_reader.banking_entity.AccountingConfig;
import com.invoice_reader.invoice_reader.banking_repository.AccountingConfigRepository;
import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.security.RequireRole;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({ "/api/v2/accounting-configs", "/api/accounting-configs" })
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AccountingConfigController {

    private final AccountingConfigRepository repository;

    @GetMapping
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> list() {
        List<AccountingConfig> configs = repository.findAll();
        return ResponseEntity.ok(Map.of("count", configs.size(), "configs", configs));
    }

    @PostMapping
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> create(@Valid @RequestBody AccountingConfigRequest request) {
        AccountingConfig config = new AccountingConfig();
        apply(config, request);
        AccountingConfig saved = repository.save(config);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody AccountingConfigRequest request) {
        AccountingConfig existing = repository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Configuration non trouvée"));
        }
        apply(existing, request);
        return ResponseEntity.ok(repository.save(existing));
    }

    @DeleteMapping("/{id}")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Configuration non trouvée"));
        }
        repository.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    @GetMapping("/banks")
    @RequireRole({ UserRole.ADMIN, UserRole.COMPTABLE })
    public ResponseEntity<?> banks() {
        List<String> banks = repository.findDistinctBanques();
        return ResponseEntity.ok(Map.of("count", banks.size(), "banks", banks));
    }

    private void apply(AccountingConfig target, AccountingConfigRequest source) {
        target.setJournal(source.getJournal().trim());
        target.setDesignation(source.getDesignation().trim());
        target.setBanque(source.getBanque().trim().toUpperCase());
        target.setCompteComptable(source.getCompteComptable().trim());
        target.setRib(source.getRib().trim());
        target.setTtcEnabled(Boolean.TRUE.equals(source.getTtcEnabled()));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountingConfigRequest {
        @NotBlank
        private String journal;
        @NotBlank
        private String designation;
        @NotBlank
        private String banque;
        @NotBlank
        private String compteComptable;
        @NotBlank
        private String rib;
        private Boolean ttcEnabled;
    }
}