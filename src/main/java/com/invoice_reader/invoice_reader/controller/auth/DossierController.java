package com.invoice_reader.invoice_reader.controller.auth;

import com.invoice_reader.invoice_reader.dto.auth.CreateDossierRequest;
import com.invoice_reader.invoice_reader.entity.auth.Dossier;
import com.invoice_reader.invoice_reader.entity.auth.DossierGeneralParams;
import com.invoice_reader.invoice_reader.entity.auth.UserAccount;
import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.banking_entity.BankStatement;
import com.invoice_reader.invoice_reader.banking_entity.BankStatus;
import com.invoice_reader.invoice_reader.banking_repository.BankStatementRepository;
import com.invoice_reader.invoice_reader.banking_repository.BankTransactionRepository;
import com.invoice_reader.invoice_reader.banking_repository.JournalBatchRepository;
import com.invoice_reader.invoice_reader.banking_repository.JournalEntryRepository;
import com.invoice_reader.invoice_reader.centremonetique.entity.CentreMonetiqueBatch;
import com.invoice_reader.invoice_reader.centremonetique.repository.CentreMonetiqueBatchRepository;
import com.invoice_reader.invoice_reader.centremonetique.repository.CentreMonetiqueTransactionRepository;
import com.invoice_reader.invoice_reader.repository.DossierDao;
import com.invoice_reader.invoice_reader.repository.DossierGeneralParamsDao;
import com.invoice_reader.invoice_reader.repository.DynamicInvoiceDao;
import com.invoice_reader.invoice_reader.entity.dynamic.DynamicInvoice;
import com.invoice_reader.invoice_reader.repository.UserAccountDao;
import com.invoice_reader.invoice_reader.entity.invoice.InvoiceStatus;
import com.invoice_reader.invoice_reader.sales.repository.SalesInvoiceRepository;
import com.invoice_reader.invoice_reader.servises.auth.AuthService;
import com.invoice_reader.invoice_reader.servises.auth.SessionUser;
import com.invoice_reader.invoice_reader.servises.auth.SessionKeys;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.Comparator;

@RestController
@RequestMapping("/api/dossiers")
@RequiredArgsConstructor
public class DossierController {

    private final DossierDao dossierDao;
    private final UserAccountDao userAccountDao;
    private final DynamicInvoiceDao dynamicInvoiceDao;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final BankStatementRepository bankStatementRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final JournalBatchRepository journalBatchRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final CentreMonetiqueBatchRepository cmBatchRepository;
    private final CentreMonetiqueTransactionRepository cmTransactionRepository;
    private final DossierGeneralParamsDao dossierGeneralParamsDao;
    private final AuthService authService;

    @PostMapping
    public ResponseEntity<?> createDossier(@Valid @RequestBody CreateDossierRequest request, HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Long comptableId = sessionUser.isAdmin() ? request.getComptableId() : sessionUser.id();
        if (comptableId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "comptable_id_required"));
        }

        UserAccount comptable = userAccountDao.findById(comptableId)
                .filter(u -> u.getRole() == UserRole.COMPTABLE || u.getRole() == UserRole.ADMIN)
                .orElse(null);

        if (comptable == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "comptable_not_found"));
        }

        UserAccount client = resolveClient(request);
        if (client == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "client_invalid"));
        }

        if (request.getExerciseStartDate() == null || request.getExerciseEndDate() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "exercise_required"));
        }
        if (request.getExerciseStartDate().isAfter(request.getExerciseEndDate())) {
            return ResponseEntity.badRequest().body(Map.of("error", "exercise_invalid"));
        }

        Dossier dossier = new Dossier();
        dossier.setName(request.getDossierName());
        dossier.setClient(client);
        dossier.setComptable(comptable);
        dossier.setActive(true);
        dossier.setExerciseStartDate(request.getExerciseStartDate());
        dossier.setExerciseEndDate(request.getExerciseEndDate());

        Dossier saved = dossierDao.save(dossier);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", saved.getId(),
                "name", saved.getName(),
                "clientId", saved.getClient().getId(),
                "comptableId", saved.getComptable().getId(),
                "exerciseStartDate", saved.getExerciseStartDate(),
                "exerciseEndDate", saved.getExerciseEndDate()
        ));
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> listDossiers(HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        List<Dossier> dossiers;
        if (sessionUser.isAdmin()) {
            dossiers = dossierDao.findAll();
        } else if (sessionUser.isClient()) {
            dossiers = dossierDao.findByClientId(sessionUser.id());
        } else {
            dossiers = dossierDao.findByComptableId(sessionUser.id());
        }

        List<Map<String, Object>> response = dossiers.stream()
                .map(d -> {
                    String clientName = resolveUserLabel(d.getClient(), d.getClientId());
                    List<Map<String, Object>> pendingInvoices = loadPendingInvoiceItems(d.getId(), d.getName(), d.getClientId(), clientName);
                    List<Map<String, Object>> pendingBankStatements = loadPendingBankStatementItems(d.getId(), d.getName(), d.getClientId(), clientName);
                    List<Map<String, Object>> pendingCentreMonetique = loadPendingCentreMonetiqueItems(d.getId(), d.getName(), d.getClientId(), clientName);

                    long pendingInvoicesOnlyCount = pendingInvoices.size();
                    long pendingDocumentsCount = pendingInvoicesOnlyCount
                            + pendingBankStatements.size()
                            + pendingCentreMonetique.size();

                    boolean filterValidated = !sessionUser.isClient();
                    long totalInvoices = filterValidated
                            ? dynamicInvoiceDao.countByDossierIdAndClientValidatedTrue(d.getId()) + salesInvoiceRepository.countByDossierIdAndClientValidatedTrue(d.getId())
                            : dynamicInvoiceDao.countByDossierId(d.getId()) + salesInvoiceRepository.countByDossierId(d.getId());
                    long totalStatements = filterValidated
                            ? bankStatementRepository.countByDossierIdAndClientValidatedTrue(d.getId())
                            : bankStatementRepository.countByDossierId(d.getId());
                    long totalCentreMonetique = filterValidated
                            ? cmBatchRepository.countByDossierIdAndClientValidatedTrue(d.getId())
                            : cmBatchRepository.countByDossierId(d.getId());

                    DossierGeneralParams generalParams = dossierGeneralParamsDao.findByDossierId(d.getId()).orElse(null);
                    String fournisseurName = resolveFournisseurName(d, generalParams);
                    String comptableName = resolveUserLabel(d.getComptable(), d.getComptableId());

                    Map<String, Object> m = new HashMap<>();
                    m.put("id", d.getId());
                    m.put("name", d.getName());
                    m.put("clientId", d.getClientId());
                    m.put("clientName", clientName);
                    m.put("comptableId", d.getComptableId());
                    m.put("comptableName", comptableName);
                    m.put("active", d.getActive());
                    m.put("status", Boolean.TRUE.equals(d.getActive()) ? "ACTIVE" : "ARCHIVED");
                    m.put("fournisseurId", d.getClientId());
                    m.put("fournisseurEmail", fournisseurName);
                    m.put("ice", generalParams != null ? generalParams.getIce() : null);
                    m.put("invoicesCount", totalInvoices);
                    // Keep the legacy field aligned with the badge shown in the dossiers page.
                    m.put("pendingInvoicesCount", pendingDocumentsCount);
                    m.put("pendingInvoicesOnlyCount", pendingInvoicesOnlyCount);
                    m.put("pendingDocumentsCount", pendingDocumentsCount);
                    long validatedInvoicesCount = filterValidated
                            ? dynamicInvoiceDao.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.VALIDATED, d.getId()) + salesInvoiceRepository.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.VALIDATED, d.getId())
                            : dynamicInvoiceDao.countByStatusAndDossierId(InvoiceStatus.VALIDATED, d.getId()) + salesInvoiceRepository.countByStatusAndDossierId(InvoiceStatus.VALIDATED, d.getId());
                    long validatedBankStatementsCount = bankStatementRepository.countByDossierIdAndStatus(d.getId(), BankStatus.VALIDATED)
                            + bankStatementRepository.countByDossierIdAndStatus(d.getId(), BankStatus.COMPTABILISE);
                    long validatedDocumentsCount = validatedInvoicesCount + validatedBankStatementsCount;
                    m.put("validatedInvoicesCount", validatedInvoicesCount);
                    m.put("validatedBankStatementsCount", validatedBankStatementsCount);
                    m.put("validatedDocumentsCount", validatedDocumentsCount);
                    m.put("bankStatementsCount", totalStatements);
                    m.put("pendingBankStatementsCount", pendingBankStatements.size());
                    m.put("centreMonetiqueCount", totalCentreMonetique);
                    m.put("pendingCentreMonetiqueCount", pendingCentreMonetique.size());
                    m.put("pendingInvoices", pendingInvoices);
                    m.put("pendingBankStatements", pendingBankStatements);
                    m.put("pendingCentreMonetique", pendingCentreMonetique);
                    m.put("createdAt", d.getCreatedAt());
                    m.put("exerciseStartDate", d.getExerciseStartDate());
                    m.put("exerciseEndDate", d.getExerciseEndDate());
                    return m;
                })
                .toList();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/active")
    public ResponseEntity<?> setActiveDossier(@RequestBody Map<String, Object> request, HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Object rawId = request.get("dossierId");
        if (rawId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_id_required"));
        }

        Long dossierId;
        try {
            dossierId = Long.valueOf(rawId.toString());
        } catch (NumberFormatException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_id_invalid"));
        }

        Dossier dossier = resolveDossierForUser(sessionUser, dossierId);
        if (dossier == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "dossier_forbidden"));
        }

        session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, dossier.getId());

        return ResponseEntity.ok(Map.of(
                "id", dossier.getId(),
                "name", dossier.getName(),
                "clientId", dossier.getClientId(),
                "comptableId", dossier.getComptableId(),
                "exerciseStartDate", dossier.getExerciseStartDate(),
                "exerciseEndDate", dossier.getExerciseEndDate()
        ));
    }

    @GetMapping("/active")
    public ResponseEntity<?> getActiveDossier(HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Object rawId = session.getAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
        if (rawId == null) {
            if (sessionUser.isClient()) {
                Dossier fallback = dossierDao.findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(sessionUser.id())
                        .orElse(null);
                if (fallback != null) {
                    session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, fallback.getId());
                    return ResponseEntity.ok(Map.of(
                            "id", fallback.getId(),
                            "name", fallback.getName(),
                            "clientId", fallback.getClientId(),
                            "comptableId", fallback.getComptableId(),
                            "exerciseStartDate", fallback.getExerciseStartDate(),
                            "exerciseEndDate", fallback.getExerciseEndDate()
                    ));
                }
            }
            return ResponseEntity.ok(Map.of("id", null));
        }

        Long dossierId;
        try {
            dossierId = Long.valueOf(rawId.toString());
        } catch (NumberFormatException ex) {
            session.removeAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
            if (sessionUser.isClient()) {
                Dossier fallback = dossierDao.findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(sessionUser.id())
                        .orElse(null);
                if (fallback != null) {
                    session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, fallback.getId());
                    return ResponseEntity.ok(Map.of(
                            "id", fallback.getId(),
                            "name", fallback.getName(),
                            "clientId", fallback.getClientId(),
                            "comptableId", fallback.getComptableId(),
                            "exerciseStartDate", fallback.getExerciseStartDate(),
                            "exerciseEndDate", fallback.getExerciseEndDate()
                    ));
                }
            }
            return ResponseEntity.ok(Map.of("id", null));
        }

        Dossier dossier = resolveDossierForUser(sessionUser, dossierId);
        if (dossier == null) {
            session.removeAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
            if (sessionUser.isClient()) {
                Dossier fallback = dossierDao.findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(sessionUser.id())
                        .orElse(null);
                if (fallback != null) {
                    session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, fallback.getId());
                    return ResponseEntity.ok(Map.of(
                            "id", fallback.getId(),
                            "name", fallback.getName(),
                            "clientId", fallback.getClientId(),
                            "comptableId", fallback.getComptableId()
                    ));
                }
            }
            return ResponseEntity.ok(Map.of("id", null));
        }

        return ResponseEntity.ok(Map.of(
                "id", dossier.getId(),
                "name", dossier.getName(),
                "clientId", dossier.getClientId(),
                "comptableId", dossier.getComptableId(),
                "exerciseStartDate", dossier.getExerciseStartDate(),
                "exerciseEndDate", dossier.getExerciseEndDate()
        ));
    }

    @PatchMapping("/{id}/comptable")
    public ResponseEntity<?> changeComptable(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        if (!sessionUser.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }

        Object rawComptableId = body.get("comptableId");
        if (rawComptableId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "comptable_id_required"));
        }

        Long newComptableId;
        try {
            newComptableId = Long.valueOf(rawComptableId.toString());
        } catch (NumberFormatException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "comptable_id_invalid"));
        }

        Dossier dossier = dossierDao.findById(id).orElse(null);
        if (dossier == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "dossier_not_found"));
        }

        UserAccount newComptable = userAccountDao.findById(newComptableId)
                .filter(u -> u.getRole() == UserRole.COMPTABLE)
                .orElse(null);
        if (newComptable == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "comptable_not_found"));
        }

        dossier.setComptable(newComptable);
        dossierDao.save(dossier);

        return ResponseEntity.ok(Map.of(
                "id", dossier.getId(),
                "comptableId", newComptable.getId(),
                "comptableName", newComptable.getUsername()
        ));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteDossier(@PathVariable Long id, HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        if (!sessionUser.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }

        Dossier dossier = dossierDao.findById(id).orElse(null);
        if (dossier == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "dossier_not_found"));
        }

        // 1. Delete journal entries then batches (linked to bank statements)
        List<BankStatement> statements = bankStatementRepository.findByDossierIdOrderByCreatedAtDesc(id);
        for (BankStatement stmt : statements) {
            journalBatchRepository.findByStatementId(stmt.getId()).ifPresent(batch -> {
                journalEntryRepository.deleteByBatchId(batch.getId());
                journalBatchRepository.deleteByStatementId(stmt.getId());
            });
            bankTransactionRepository.deleteByStatementId(stmt.getId());
        }
        bankStatementRepository.deleteAll(statements);

        // 2. Delete CM transactions then batches
        List<CentreMonetiqueBatch> cmBatches = cmBatchRepository.findTop200ByDossierIdOrderByCreatedAtDesc(id);
        for (CentreMonetiqueBatch batch : cmBatches) {
            cmTransactionRepository.deleteByBatchId(batch.getId());
        }
        cmBatchRepository.deleteAll(cmBatches);

        // 3. Delete invoices
        dynamicInvoiceDao.deleteAll(dynamicInvoiceDao.findByDossierIdOrderByCreatedAtDesc(id));
        salesInvoiceRepository.deleteAll(salesInvoiceRepository.findByDossierIdOrderByCreatedAtDesc(id));

        // 4. Delete general params and dossier
        dossierGeneralParamsDao.findByDossierId(id).ifPresent(dossierGeneralParamsDao::delete);
        dossierDao.delete(dossier);

        return ResponseEntity.ok(Map.of("deleted", true));
    }

    private UserAccount resolveClient(CreateDossierRequest request) {
        if (request.getClientId() != null) {
            return userAccountDao.findById(request.getClientId())
                    .filter(u -> u.getRole() == UserRole.CLIENT)
                    .orElse(null);
        }

        if (request.getClientUsername() == null || request.getClientUsername().isBlank()
                || request.getClientPassword() == null || request.getClientPassword().isBlank()) {
            return null;
        }

        if (userAccountDao.existsByUsername(request.getClientUsername())) {
            return null;
        }

        UserAccount client = new UserAccount();
        client.setUsername(request.getClientUsername());
        client.setPassword(request.getClientPassword());
        client.setRole(UserRole.CLIENT);
        client.setDisplayName(request.getClientDisplayName());
        client.setActive(true);

        return userAccountDao.save(client);
    }

    private Dossier resolveDossierForUser(SessionUser sessionUser, Long dossierId) {
        if (sessionUser.isAdmin()) {
            return dossierDao.findById(dossierId).orElse(null);
        }
        if (sessionUser.isClient()) {
            return dossierDao.findByIdAndClientId(dossierId, sessionUser.id()).orElse(null);
        }
        return dossierDao.findByIdAndComptableId(dossierId, sessionUser.id()).orElse(null);
    }

    private String resolveFournisseurName(Dossier dossier, DossierGeneralParams params) {
        if (params != null && params.getCompanyName() != null && !params.getCompanyName().isBlank()) {
            return params.getCompanyName();
        }
        if (dossier.getClient() != null && dossier.getClient().getDisplayName() != null
                && !dossier.getClient().getDisplayName().isBlank()) {
            return dossier.getClient().getDisplayName();
        }
        if (dossier.getClient() != null && dossier.getClient().getUsername() != null) {
            return dossier.getClient().getUsername();
        }
        return "Fournisseur";
    }

    private String resolveUserLabel(UserAccount user, Long fallbackId) {
        if (user != null) {
            if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
                return user.getDisplayName();
            }
            if (user.getUsername() != null && !user.getUsername().isBlank()) {
                return user.getUsername();
            }
        }
        return fallbackId != null ? "User #" + fallbackId : null;
    }

    private List<Map<String, Object>> loadPendingInvoiceItems(Long dossierId, String dossierName, Long clientId, String clientName) {
        List<Map<String, Object>> items = dynamicInvoiceDao.findByDossierIdOrderByCreatedAtDesc(dossierId).stream()
                .filter(this::isPendingInvoice)
                .map(invoice -> invoiceSummary(
                        invoice.getId(),
                        invoice.getOriginalName() != null ? invoice.getOriginalName() : invoice.getFilename(),
                        invoice.getStatus() != null ? invoice.getStatus().name() : null,
                        invoice.getCreatedAt(),
                        "ACHAT",
                        "dynamic",
                        dossierId,
                        dossierName,
                        clientId,
                        clientName))
                .toList();

        List<Map<String, Object>> salesItems = salesInvoiceRepository.findByDossierIdOrderByCreatedAtDesc(dossierId).stream()
                .filter(this::isPendingInvoice)
                .map(invoice -> invoiceSummary(
                        invoice.getId(),
                        invoice.getOriginalName() != null ? invoice.getOriginalName() : invoice.getFilename(),
                        invoice.getStatus() != null ? invoice.getStatus().name() : null,
                        invoice.getCreatedAt(),
                        "VENTE",
                        "sales",
                        dossierId,
                        dossierName,
                        clientId,
                        clientName))
                .toList();

        return sortByCreatedAtDesc(concat(items, salesItems));
    }

    private List<Map<String, Object>> loadPendingBankStatementItems(Long dossierId, String dossierName, Long clientId, String clientName) {
        return sortByCreatedAtDesc(
                bankStatementRepository.findByDossierIdOrderByCreatedAtDesc(dossierId).stream()
                        .filter(this::isPendingBankStatement)
                        .map(statement -> invoiceSummary(
                                statement.getId(),
                                statement.getOriginalName() != null ? statement.getOriginalName() : statement.getFilename(),
                                statement.getStatus() != null ? statement.getStatus().name() : null,
                                statement.getCreatedAt(),
                                statement.getBankName(),
                                "bank-statement",
                                dossierId,
                                dossierName,
                                clientId,
                                clientName))
                        .toList());
    }

    private List<Map<String, Object>> loadPendingCentreMonetiqueItems(Long dossierId, String dossierName, Long clientId, String clientName) {
        return sortByCreatedAtDesc(
                cmBatchRepository.findByDossierIdOrderByCreatedAtDesc(dossierId).stream()
                        .filter(this::isPendingCentreMonetique)
                        .map(batch -> invoiceSummary(
                                batch.getId(),
                                batch.getOriginalName() != null ? batch.getOriginalName() : batch.getFilename(),
                                batch.getStatus(),
                                batch.getCreatedAt(),
                                batch.getStructure(),
                                "centre-monetique",
                                dossierId,
                                dossierName,
                                clientId,
                                clientName))
                        .toList());
    }

    private Map<String, Object> invoiceSummary(Long id,
                                               String filename,
                                               String status,
                                               LocalDateTime createdAt,
                                               String secondaryLabel,
                                               String sourceType,
                                               Long dossierId,
                                               String dossierName,
                                               Long clientId,
                                               String clientName) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", id);
        item.put("filename", filename);
        item.put("status", status);
        item.put("secondaryLabel", secondaryLabel);
        item.put("sourceType", sourceType);
        item.put("dossierId", dossierId);
        item.put("dossierName", dossierName);
        item.put("clientId", clientId);
        item.put("clientName", clientName);
        item.put("createdAt", createdAt);
        return item;
    }

    private boolean isPendingInvoice(DynamicInvoice invoice) {
        if (invoice == null || invoice.getStatus() == null) return false;
        if (!Boolean.TRUE.equals(invoice.getClientValidated())) return false;
        return invoice.getStatus() != InvoiceStatus.VALIDATED
                && invoice.getStatus() != InvoiceStatus.ACCOUNTED
                && invoice.getStatus() != InvoiceStatus.ERROR
                && invoice.getStatus() != InvoiceStatus.DUPLICATE;
    }

    private boolean isPendingInvoice(com.invoice_reader.invoice_reader.sales.entity.SalesInvoice invoice) {
        if (invoice == null || invoice.getStatus() == null) return false;
        if (!Boolean.TRUE.equals(invoice.getClientValidated())) return false;
        return invoice.getStatus() != InvoiceStatus.VALIDATED
                && invoice.getStatus() != InvoiceStatus.ACCOUNTED
                && invoice.getStatus() != InvoiceStatus.ERROR
                && invoice.getStatus() != InvoiceStatus.DUPLICATE;
    }

    private boolean isPendingBankStatement(BankStatement statement) {
        if (statement == null || statement.getStatus() == null) return false;
        if (!statement.isClientValidated()) return false;
        return statement.getStatus() != BankStatus.VALIDATED
                && statement.getStatus() != BankStatus.COMPTABILISE
                && statement.getStatus() != BankStatus.ERROR
                && statement.getStatus() != BankStatus.DUPLIQUE;
    }

    private boolean isPendingCentreMonetique(CentreMonetiqueBatch batch) {
        if (batch == null) {
            return false;
        }
        return batch.isClientValidated();
    }

    private List<Map<String, Object>> sortByCreatedAtDesc(List<Map<String, Object>> items) {
        return items.stream()
                .sorted(Comparator.comparing(
                        item -> (LocalDateTime) item.get("createdAt"),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private List<Map<String, Object>> concat(List<Map<String, Object>> first, List<Map<String, Object>> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }
}
