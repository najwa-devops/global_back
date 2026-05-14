package com.invoice_reader.invoice_reader.banque.controller;

import com.invoice_reader.invoice_reader.banque.service.*;
import com.invoice_reader.invoice_reader.banque.repository.AccountingEntryRepository;
import com.invoice_reader.invoice_reader.banque.repository.CptjournalJdbcRepository;
import com.invoice_reader.invoice_reader.banque.repository.CptjournalSyncTrackerRepository;
import com.invoice_reader.invoice_reader.banque.entity.BanqueReleve;
import com.invoice_reader.invoice_reader.banque.entity.BanqueStatus;
import com.invoice_reader.invoice_reader.banque.entity.BanqueTransaction;
import com.invoice_reader.invoice_reader.banque.entity.JournalBatch;
import com.invoice_reader.invoice_reader.banque.repository.JournalBatchRepository;
import com.invoice_reader.invoice_reader.banque.repository.JournalEntryRepository;
import com.invoice_reader.invoice_reader.banque.repository.BanqueReleveRepository;
import com.invoice_reader.invoice_reader.banque.repository.BanqueTransactionRepository;
import com.invoice_reader.invoice_reader.database.entity.auth.Dossier;
import com.invoice_reader.invoice_reader.database.entity.auth.DossierGeneralParams;
import com.invoice_reader.invoice_reader.database.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.database.dao.DossierDao;
import com.invoice_reader.invoice_reader.database.dao.DossierGeneralParamsDao;
import com.invoice_reader.invoice_reader.banque.liaison.dto.CmExpansionDTO;
import com.invoice_reader.invoice_reader.banque.liaison.service.CentreMonetiqueLiaisonService;
import com.invoice_reader.invoice_reader.security.RequireRole;
import com.invoice_reader.invoice_reader.auth.service.AuthService;
import com.invoice_reader.invoice_reader.auth.service.SessionKeys;
import com.invoice_reader.invoice_reader.auth.service.SessionUser;
import jakarta.servlet.http.HttpSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ✅ API REST RELEVÉS BANCAIRES - VERSION
 * 
 * Utilise BanqueReleveProcessingService pour une extraction améliorée
 */
@RestController
@RequestMapping({ "/api/v2/bank-statements", "/api/bank-statements" })
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
@RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
public class BanqueReleveController {

    private static final String DEFAULT_COMPTE = "349700000";

    private final BanqueReleveRepository repository;
    private final BanqueTransactionRepository transactionRepository;
    private final AccountingEntryRepository accountingEntryRepository;
    private final JournalBatchRepository journalBatchRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final CptjournalJdbcRepository cptjournalJdbcRepository;
    private final CptjournalSyncTrackerRepository cptjournalSyncTrackerRepository;
    private final BanqueReleveProcessingService processingService;
    private final BanqueReleveValidatorService validatorService;
    private final ComptabilisationWorkflowService comptabilisationWorkflowService;
    private final BanqueTransactionAccountLearningService accountLearningService;
    private final BanqueFileStorageService bankFileStorageService;
    private final AuthService authService;
    private final DossierDao dossierDao;
    private final DossierGeneralParamsDao dossierGeneralParamsDao;
    private final CentreMonetiqueLiaisonService centreMonetiqueLiaisonService;
    private static final Pattern DUPLICATE_OF_PATTERN = Pattern.compile("DUPLIQUE_OF:(\\d+)");
    private static final Pattern OCR_DATE_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{1,2}(?:\\s*[\\/\\-.]\\s*|\\s+)\\d{1,2}(?:(?:\\s*[\\/\\-.]\\s*|\\s+)\\d{2,4})?)(?!\\d)");
    private static final DateTimeFormatter STATEMENT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ==================== UPLOAD & TRAITEMENT ====================

    @PostMapping("/upload")
    public ResponseEntity<?> uploadAndProcess(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "bankType", required = false) String bankType,
            @RequestParam(name = "allowedBanks", required = false) List<String> allowedBanks,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        log.info("📤 Upload relevé: {} (BanqueType={}, AllowedBanks={})",
                file.getOriginalFilename(), bankType, allowedBanks);

        try {
            SessionUser sessionUser = authService.requireSessionUser(session);
            Long resolvedDossierId = null;
            if (sessionUser != null) {
                resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);
                if (sessionUser.isClient() && resolvedDossierId == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
                }
            }

            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Fichier vide"));
            }

            String originalName = file.getOriginalFilename();
            String contentType = file.getContentType();

            log.info("Type MIME: {}, Nom: {}", contentType, originalName);

            // Validation
            boolean validMime = isValidMimeType(contentType);
            boolean validExt = isValidExtension(originalName);

            if (!validMime && !validExt) {
                log.warn("Upload rejeté - MIME: {}, Ext: {}", contentType, originalName);
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Type de fichier non supporté",
                        "receivedType", contentType != null ? contentType : "unknown",
                        "receivedName", originalName != null ? originalName : "unknown",
                        "supportedTypes", List.of(
                                "image/png", "image/jpeg", "application/pdf",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")));
            }

            // Vérifier doublon AVANT création du relevé
            try {
                Optional<BanqueReleve> duplicate = processingService.detectDuplicateFromUpload(
                        file.getBytes(), originalName);
                if (duplicate.isPresent()) {
                    BanqueReleve existing = duplicate.get();
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                            "error", "Doublon détecté: même RIB/période/soldes déjà présents",
                            "duplicateOfId", existing.getId(),
                            "rib", existing.getRib(),
                            "month", existing.getMonth(),
                            "year", existing.getYear()));
                }
            } catch (Exception e) {
                log.warn("Vérification doublon upload ignorée: {}", e.getMessage());
            }

            // Stocker le fichier
            BanqueFileStorageService.StoredBankFile storedFile = bankFileStorageService.storeBankStatement(file);
            log.info("✅ Fichier stocké en base: {}", storedFile.filename());

            // Créer l'entité
            BanqueReleve statement = new BanqueReleve();
            statement.setFilename(storedFile.filename());
            statement.setOriginalName(storedFile.originalName());
            statement.setFilePath(storedFile.filePath());
            statement.setFileSize(storedFile.size()); 
            statement.setFileContentType(storedFile.contentType());
            statement.setFileData(null);
            statement.setStatus(BanqueStatus.PENDING);
            statement.setDossierId(resolvedDossierId);
            statement.setApplyTtcRule(true);
            statement.setApplyFraisRule(true);
            statement.setApplyAgiosRule(true);
            statement.setApplyPackageRule(true);

            // Les dates de période sont déterminées par OCR/extraction metadata.
            statement.setMonth(null);
            statement.setYear(null);

            if (sessionUser != null && sessionUser.isAdmin()) {
                statement.clientValidate(sessionUser.username());
            }
            BanqueReleve saved = repository.save(statement);
            log.info("✅ Relevé créé: ID={}", saved.getId());

            List<String> cleanedAllowedBanks = normalizeAllowedBanks(allowedBanks);

            // Traiter de manière asynchrone avec
            log.info("🚀 Lancement traitement asynchrone (Banque: {}, Autorisées: {})", bankType,
                    cleanedAllowedBanks);
            processingService.processStatementAsync(saved.getId(), bankType, cleanedAllowedBanks);

            return ResponseEntity.accepted().body(toResponse(saved));

        } catch (Exception e) {
            log.error("❌ Erreur upload: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Erreur interne"));
        }
    }

    @PostMapping({ "/{id}/process", "/{id}/retry-failed" })
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> reprocess(
            @PathVariable("id") Long id,
            @RequestParam(name = "allowedBanks", required = false) List<String> allowedBanks) {
        log.info("🔌 Reprocess request received. Raw allowedBanks param: {}", allowedBanks);
        List<String> cleanedAllowedBanks = normalizeAllowedBanks(allowedBanks);
        log.info("🔄 Retraitement relevé: {} (Final cleaned allowedBanks: {})", id, cleanedAllowedBanks);

        try {
            BanqueReleve processed = processingService.reprocessStatement(id, cleanedAllowedBanks);
            syncCmAppliedFromExpansions(processed.getId());
            return ResponseEntity.ok(Map.of(
                    "message", "Reprise terminée",
                    "allowedBanks", cleanedAllowedBanks,
                    "statementId", processed.getId(),
                    "status", processed.getStatus() != null ? processed.getStatus().name() : "UNKNOWN"));
        } catch (Exception e) {
            log.error("❌ Erreur retraitement: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== CRUD ====================

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getById(@PathVariable("id") Long id) {
        log.info("📖 Récupération relevé: {}", id);

        return repository.findById(id)
                .map(statement -> {
                    log.info("✅ Relevé trouvé: {} transactions", statement.getTransactionCount());
                    return ResponseEntity.ok(toDetailedResponse(statement));
                })
                .orElseGet(() -> {
                    log.warn("⚠️ Relevé {} non trouvé", id);
                    return ResponseEntity.notFound().build();
                });
    }

    @GetMapping("/{id}/final")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getFinalById(@PathVariable("id") Long id) {
        return repository.findById(id)
                .map(statement -> ResponseEntity.ok(buildFinalResponse(statement, true)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/ocr-text")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getOcrText(@PathVariable("id") Long id) {
        return repository.findById(id)
                .map(statement -> ResponseEntity.ok(Map.of(
                        "id", id,
                        "filename", statement.getFilename(),
                        "rawOcrText", statement.getRawOcrText() != null ? statement.getRawOcrText() : "",
                        "cleanedOcrText", statement.getCleanedOcrText() != null ? statement.getCleanedOcrText() : "")))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "rib", required = false) String rib,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "limit", defaultValue = "1000") int limit,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {

        try {
            SessionUser sessionUser = authService.requireSessionUser(session);
            Long resolvedDossierId = null;
            if (sessionUser != null) {
                resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);
                if (sessionUser.isClient() && resolvedDossierId == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
                }
            }

            List<BanqueReleve> statements;

            if (resolvedDossierId != null) {
                // Filtrage par dossier actif (CLIENT, ADMIN, COMPTABLE avec dossier actif)
                if (status != null) {
                    BanqueStatus bankStatus = BanqueStatus.fromExternalValue(status);
                    if (bankStatus == null) {
                        return ResponseEntity.badRequest().body(Map.of("error", "Statut invalide: " + status));
                    }
                    statements = repository.findByDossierIdAndStatusOrderByCreatedAtDesc(resolvedDossierId, bankStatus);
                } else {
                    statements = repository.findByDossierIdOrderByCreatedAtDesc(resolvedDossierId);
                }
            } else {
                // ADMIN/COMPTABLE sans dossier actif : afficher tout (fallback legacy)
                if (status != null) {
                    BanqueStatus bankStatus = BanqueStatus.fromExternalValue(status);
                    if (bankStatus == null) {
                        return ResponseEntity.badRequest().body(
                                Map.of("error", "Statut invalide: " + status));
                    }
                    statements = repository.findByStatusOrderByCreatedAtDesc(bankStatus);
                } else if (rib != null) {
                    statements = repository.findByRibOrderByYearDescMonthDesc(rib);
                } else if (year != null && month != null) {
                    statements = repository.findByYearAndMonthOrderByRib(year, month);
                } else if (year != null) {
                    statements = repository.findByYearOrderByMonthDesc(year);
                } else {
                    statements = repository.findAllOrderByCreatedAtDesc();
                }
            }

            // Admin et comptable ne voient que les relevés validés par le client
            if (sessionUser != null && !sessionUser.isClient()) {
                statements = statements.stream()
                        .filter(statement -> Boolean.TRUE.equals(statement.getClientValidated()))
                        .toList();
            }

            List<Map<String, Object>> mappedStatements = statements.stream()
                    .limit(limit)
                    .map(this::toResponse)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "count", mappedStatements.size(),
                    "statements", mappedStatements));

        } catch (Exception e) {
            log.error("❌ Erreur listage: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("error", "Erreur lors du listage: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @Transactional
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> delete(@PathVariable("id") Long id,
                                    @RequestParam(value = "dossierId", required = false) Long dossierId,
                                    HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        Long resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }

        Optional<BanqueReleve> statementOpt = repository.findById(id);

        if (statementOpt.isEmpty()) {
            log.info("Relevé {} déjà supprimé", id);
            return ResponseEntity.noContent().build();
        }

        BanqueReleve statement = statementOpt.get();
        if (!canAccessStatementInDossier(sessionUser, statement, resolvedDossierId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (sessionUser != null && sessionUser.isClient() && Boolean.TRUE.equals(statement.getClientValidated())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "client_validated"));
        }
        BanqueStatus stmtStatus = statement.getStatus();
        // COMPTABILISE → ADMIN only + flag allowAccountedDocumentDeletion
        if (stmtStatus == BanqueStatus.COMPTABILISE) {
            if (sessionUser == null || !sessionUser.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "accounted_document_deletion_admin_only"));
            }
            if (!isAccountedDocumentDeletionAllowed(resolvedDossierId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "accounted_document_deletion_disabled"));
            }
        } else if ((Boolean.TRUE.equals(statement.getClientValidated()) || stmtStatus == BanqueStatus.VALIDATED)
                && !isValidatedDocumentDeletionAllowed(resolvedDossierId)) {
            // VALIDATED or client-validated (not comptabilisé) → flag allowValidatedDocumentDeletion
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "validated_document_deletion_disabled"));
        }

        processingService.deleteStatement(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/client-validate")
    @Transactional
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> clientValidate(@PathVariable("id") Long id,
                                            @RequestParam(value = "dossierId", required = false) Long dossierId,
                                            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        Long resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }

        return repository.findById(id)
                .map(statement -> {
                    if (!canAccessStatementInDossier(sessionUser, statement, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
                    }
                    if (Boolean.TRUE.equals(statement.getClientValidated())) {
                        return ResponseEntity.ok(Map.of(
                                "message", "Relevé déjà validé par le client",
                                "statement", toResponse(statement)));
                    }

                    statement.clientValidate(sessionUser != null ? sessionUser.username() : "client");
                    BanqueReleve saved = repository.save(statement);
                    return ResponseEntity.ok(Map.of(
                            "message", "Relevé validé par le client",
                            "statement", toResponse(saved)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/all")
    @Transactional
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> deleteAll() {
        log.info("🗑️ Suppression de TOUS les relevés bancaires");

        List<BanqueReleve> statements = repository.findAllOrderByCreatedAtDesc();
        for (BanqueReleve statement : statements) {
            journalBatchRepository.findByStatementId(statement.getId()).ifPresent(batch -> {
                journalEntryRepository.deleteByBatchId(batch.getId());
                journalBatchRepository.deleteByStatementId(statement.getId());
            });
            transactionRepository.deleteByStatementId(statement.getId());
        }

        accountingEntryRepository.deleteAllBankEntries();
        cptjournalSyncTrackerRepository.deleteAll();
        cptjournalJdbcRepository.deleteAll();

        transactionRepository.deleteAllInBatch();
        repository.deleteAllInBatch();
        return ResponseEntity.noContent().build();
    }

    // ==================== VALIDATION ====================

    @PostMapping("/{id}/validate")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> validate(
            @PathVariable("id") Long id,
            @RequestParam(name = "userId", defaultValue = "system") String userId) {
        log.info("✅ Validation relevé {} par {}", id, userId);

        return repository.findById(id)
                .map(statement -> {
                    if (statement.getStatus() == BanqueStatus.VALIDATED) {
                        return ResponseEntity.ok(Map.of(
                                "message", "Relevé déjà validé",
                                "statement", toResponse(statement)));
                    }

                    if (!statement.canBeValidated()) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Relevé non prêt pour validation",
                                "currentStatus", statement.getStatus().name(),
                                "requiredStatus", "READY_TO_VALIDATE ou TREATED"));
                    }

                    statement.validate(userId);
                    BanqueReleve saved = repository.save(statement);

                    return ResponseEntity.ok(Map.of(
                            "message", "Relevé validé",
                            "statement", toResponse(saved)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/validate-full")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> validateFully(@PathVariable("id") Long id) {
        return repository.findById(id)
                .map(statement -> {
                    var result = validatorService.validateFully(statement);
                    repository.save(statement);

                    return ResponseEntity.ok(Map.of(
                            "statementId", result.statementId,
                            "isFullyValid", result.isFullyValid,
                            "balanceValidation", result.balanceValidation,
                            "continuityValidation", result.continuityValidation,
                            "transactionStats", Map.of(
                                    "total", result.totalTransactions,
                                    "valid", result.validTransactions,
                                    "errors", result.errorTransactions),
                            "errors", result.errors,
                            "warnings", result.warnings));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/status")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> updateStatus(
            @PathVariable("id") Long id,
            @RequestBody UpdateBankStatementStatusRequest request) {
        BanqueStatus requested = BanqueStatus.fromExternalValue(request.getStatus());
        if (requested == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Statut invalide",
                    "allowed", List.of("PENDING", "PROCESSING", "A_VERIFIER", "READY_TO_VALIDATE", "VALIDATED", "COMPTABILISE",
                            "ERROR", "VIDE", "DUPLIQUE")));
        }

        return repository.findById(id)
                .map(statement -> {
                    if (statement.getStatus() == BanqueStatus.COMPTABILISE && requested != BanqueStatus.COMPTABILISE) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                                "error", "Relevé comptabilisé, modification du statut impossible",
                                "currentStatus", statement.getStatus().name()));
                    }
                    try {
                        if (requested == BanqueStatus.COMPTABILISE) {
                            String userId = request.getUpdatedBy() == null || request.getUpdatedBy().isBlank()
                                    ? "system"
                                    : request.getUpdatedBy();
                            long existingEntries = accountingEntryRepository.countBySourceStatementId(id);
                            if (statement.getStatus() == BanqueStatus.COMPTABILISE && existingEntries > 0) {
                                int syncedRows = comptabilisationWorkflowService
                                        .syncCptjournalFromExistingAccountingEntries(id);
                                return ResponseEntity.ok(Map.of(
                                        "message", syncedRows > 0
                                                ? "Relevé déjà comptabilisé, synchronisation Cptjournal effectuée"
                                                : "Relevé déjà comptabilisé",
                                        "statement", toResponse(statement),
                                        "insertedEntries", syncedRows));
                            }

                            ComptabilisationWorkflowService.SimulationResult simulation =
                                    comptabilisationWorkflowService.simulate(id);
                            ComptabilisationWorkflowService.ConfirmationResult confirmation =
                                    comptabilisationWorkflowService.confirm(simulation.simulationId(), userId);
                            BanqueReleve refreshed = repository.findById(id).orElse(statement);

                            return ResponseEntity.ok(Map.of(
                                    "message", "Statut mis à jour",
                                    "statement", toResponse(refreshed),
                                    "insertedEntries", confirmation.insertedEntries(),
                                    "simulationId", confirmation.simulationId()));
                        } else {
                            statement.setStatus(requested);
                            if (requested != BanqueStatus.COMPTABILISE) {
                                statement.setAccountedAt(null);
                                statement.setAccountedBy(null);
                            }
                            BanqueReleve saved = repository.save(statement);
                            return ResponseEntity.ok(Map.of(
                                    "message", "Statut mis à jour",
                                    "statement", toResponse(saved)));
                        }
                    } catch (IllegalStateException e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/ttc-rule")
    @Transactional
    public ResponseEntity<?> updateTtcRule(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyTtcRule(id, request);
    }

    @PutMapping("/{id}/ttc-toggle")
    public ResponseEntity<?> updateTtcToggle(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyTtcRule(id, request);
    }

    @PostMapping("/{id}/ttc-rule")
    @Transactional
    public ResponseEntity<?> updateTtcRulePost(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyTtcRule(id, request);
    }

    @PostMapping("/{id}/ttc-toggle")
    public ResponseEntity<?> updateTtcTogglePost(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyTtcRule(id, request);
    }

    @GetMapping("/{id}/ttc-rule")
    @Transactional
    public ResponseEntity<?> updateTtcRuleGet(
            @PathVariable("id") Long id,
            @RequestParam(name = "enabled") boolean enabled,
            @RequestParam(name = "reprocess", defaultValue = "true") boolean reprocess) {
        UpdateTtcRuleRequest request = new UpdateTtcRuleRequest();
        request.setEnabled(enabled);
        request.setReprocess(reprocess);
        return applyTtcRule(id, request);
    }

    @GetMapping("/{id}/ttc-toggle")
    public ResponseEntity<?> updateTtcToggleGet(
            @PathVariable("id") Long id,
            @RequestParam(name = "enabled") boolean enabled,
            @RequestParam(name = "reprocess", defaultValue = "true") boolean reprocess) {
        UpdateTtcRuleRequest request = new UpdateTtcRuleRequest();
        request.setEnabled(enabled);
        request.setReprocess(reprocess);
        return applyTtcRule(id, request);
    }

    @PutMapping("/{id}/frais-rule")
    @Transactional
    public ResponseEntity<?> updateFraisRule(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyFraisRule(id, request);
    }

    @PostMapping("/{id}/frais-rule")
    @Transactional
    public ResponseEntity<?> updateFraisRulePost(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyFraisRule(id, request);
    }

    @GetMapping("/{id}/frais-rule")
    @Transactional
    public ResponseEntity<?> updateFraisRuleGet(
            @PathVariable("id") Long id,
            @RequestParam(name = "enabled") boolean enabled,
            @RequestParam(name = "reprocess", defaultValue = "true") boolean reprocess) {
        UpdateTtcRuleRequest request = new UpdateTtcRuleRequest();
        request.setEnabled(enabled);
        request.setReprocess(reprocess);
        return applyFraisRule(id, request);
    }

    @PutMapping("/{id}/agios-rule")
    @Transactional
    public ResponseEntity<?> updateAgiosRule(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyAgiosRule(id, request);
    }

    @PostMapping("/{id}/agios-rule")
    @Transactional
    public ResponseEntity<?> updateAgiosRulePost(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyAgiosRule(id, request);
    }

    @GetMapping("/{id}/agios-rule")
    @Transactional
    public ResponseEntity<?> updateAgiosRuleGet(
            @PathVariable("id") Long id,
            @RequestParam(name = "enabled") boolean enabled,
            @RequestParam(name = "reprocess", defaultValue = "true") boolean reprocess) {
        UpdateTtcRuleRequest request = new UpdateTtcRuleRequest();
        request.setEnabled(enabled);
        request.setReprocess(reprocess);
        return applyAgiosRule(id, request);
    }

    @PutMapping("/{id}/package-rule")
    @Transactional
    public ResponseEntity<?> updatePackageRule(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyPackageRule(id, request);
    }

    @PostMapping("/{id}/package-rule")
    @Transactional
    public ResponseEntity<?> updatePackageRulePost(
            @PathVariable("id") Long id,
            @RequestBody UpdateTtcRuleRequest request) {
        return applyPackageRule(id, request);
    }

    @GetMapping("/{id}/package-rule")
    @Transactional
    public ResponseEntity<?> updatePackageRuleGet(
            @PathVariable("id") Long id,
            @RequestParam(name = "enabled") boolean enabled,
            @RequestParam(name = "reprocess", defaultValue = "true") boolean reprocess) {
        UpdateTtcRuleRequest request = new UpdateTtcRuleRequest();
        request.setEnabled(enabled);
        request.setReprocess(reprocess);
        return applyPackageRule(id, request);
    }

    private ResponseEntity<?> applyTtcRule(Long id, UpdateTtcRuleRequest request) {
        return repository.findById(id)
                .map(statement -> {
                    boolean enabled = Boolean.TRUE.equals(request.getEnabled());
                    if (statement.getStatus() == BanqueStatus.COMPTABILISE) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Relevé comptabilisé, modification TTC impossible",
                                "statement", toResponse(statement)));
                    }
                    statement.setApplyTtcRule(enabled);
                    BanqueReleve processed = statement;
                    if (Boolean.TRUE.equals(request.getReprocess())) {
                        statement.setStatus(BanqueStatus.PROCESSING);
                        statement.setValidationErrors(null);
                    }
                    BanqueReleve saved = repository.save(statement);

                    if (Boolean.TRUE.equals(request.getReprocess())) {
                        try {
                            processed = processingService.reprocessStatement(id);
                            syncCmAppliedFromExpansions(processed.getId());
                        } catch (IllegalStateException e) {
                            return ResponseEntity.badRequest().body(Map.of(
                                    "error", e.getMessage(),
                                    "statement", toResponse(saved)));
                        } catch (Exception e) {
                            log.error("Échec lancement retraitement TTC pour relevé {}: {}", id, e.getMessage(), e);
                            Map<String, Object> response = buildRuleUpdateResponse(
                                    enabled ? "Règle TTC activée (retraitement non lancé)"
                                            : "Règle TTC désactivée (retraitement non lancé)",
                                    saved);
                            response.put("warning", "Échec lancement retraitement TTC: " + e.getMessage());
                            return ResponseEntity.ok(response);
                        }
                    }

                    return ResponseEntity.ok(buildRuleUpdateResponse(
                            enabled
                                    ? "Règle TTC activée" + (Boolean.TRUE.equals(request.getReprocess()) ? " et retraitement lancé" : "")
                                    : "Règle TTC désactivée" + (Boolean.TRUE.equals(request.getReprocess()) ? " et retraitement lancé" : ""),
                            processed));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private ResponseEntity<?> applyFraisRule(Long id, UpdateTtcRuleRequest request) {
        return repository.findById(id)
                .map(statement -> {
                    boolean enabled = Boolean.TRUE.equals(request.getEnabled());
                    if (statement.getStatus() == BanqueStatus.COMPTABILISE) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Relevé comptabilisé, modification règle frais impossible",
                                "statement", toResponse(statement)));
                    }
                    statement.setApplyFraisRule(enabled);
                    BanqueReleve processed = statement;
                    if (Boolean.TRUE.equals(request.getReprocess())) {
                        statement.setStatus(BanqueStatus.PROCESSING);
                        statement.setValidationErrors(null);
                    }
                    BanqueReleve saved = repository.save(statement);

                    if (Boolean.TRUE.equals(request.getReprocess())) {
                        try {
                            processed = processingService.reprocessStatement(id);
                            syncCmAppliedFromExpansions(processed.getId());
                        } catch (IllegalStateException e) {
                            return ResponseEntity.badRequest().body(Map.of(
                                    "error", e.getMessage(),
                                    "statement", toResponse(saved)));
                        } catch (Exception e) {
                            log.error("Échec lancement retraitement règle frais pour relevé {}: {}", id, e.getMessage(), e);
                            Map<String, Object> response = buildRuleUpdateResponse(
                                    enabled ? "Règle frais activée (retraitement non lancé)"
                                            : "Règle frais désactivée (retraitement non lancé)",
                                    saved);
                            response.put("warning", "Échec lancement retraitement règle frais: " + e.getMessage());
                            return ResponseEntity.ok(response);
                        }
                    }

                    return ResponseEntity.ok(buildRuleUpdateResponse(
                            enabled
                                    ? "Règle frais activée" + (Boolean.TRUE.equals(request.getReprocess()) ? " et retraitement lancé" : "")
                                    : "Règle frais désactivée" + (Boolean.TRUE.equals(request.getReprocess()) ? " et retraitement lancé" : ""),
                            processed));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private ResponseEntity<?> applyAgiosRule(Long id, UpdateTtcRuleRequest request) {
        return repository.findById(id)
                .map(statement -> {
                    boolean enabled = Boolean.TRUE.equals(request.getEnabled());
                    if (statement.getStatus() == BanqueStatus.COMPTABILISE) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Relevé comptabilisé, modification règle agios impossible",
                                "statement", toResponse(statement)));
                    }
                    statement.setApplyAgiosRule(enabled);
                    BanqueReleve processed = statement;
                    if (Boolean.TRUE.equals(request.getReprocess())) {
                        statement.setStatus(BanqueStatus.PROCESSING);
                        statement.setValidationErrors(null);
                    }
                    BanqueReleve saved = repository.save(statement);

                    if (Boolean.TRUE.equals(request.getReprocess())) {
                        try {
                            processed = processingService.reprocessStatement(id);
                            syncCmAppliedFromExpansions(processed.getId());
                        } catch (IllegalStateException e) {
                            return ResponseEntity.badRequest().body(Map.of(
                                    "error", e.getMessage(),
                                    "statement", toResponse(saved)));
                        } catch (Exception e) {
                            log.error("Échec lancement retraitement règle agios pour relevé {}: {}", id, e.getMessage(), e);
                            Map<String, Object> response = buildRuleUpdateResponse(
                                    enabled ? "Règle agios activée (retraitement non lancé)"
                                            : "Règle agios désactivée (retraitement non lancé)",
                                    saved);
                            response.put("warning", "Échec lancement retraitement règle agios: " + e.getMessage());
                            return ResponseEntity.ok(response);
                        }
                    }

                    return ResponseEntity.ok(buildRuleUpdateResponse(
                            enabled
                                    ? "Règle agios activée" + (Boolean.TRUE.equals(request.getReprocess()) ? " et retraitement lancé" : "")
                                    : "Règle agios désactivée" + (Boolean.TRUE.equals(request.getReprocess()) ? " et retraitement lancé" : ""),
                            processed));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private ResponseEntity<?> applyPackageRule(Long id, UpdateTtcRuleRequest request) {
        return repository.findById(id)
                .map(statement -> {
                    boolean enabled = Boolean.TRUE.equals(request.getEnabled());
                    if (statement.getStatus() == BanqueStatus.COMPTABILISE) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Relevé comptabilisé, modification règle package impossible",
                                "statement", toResponse(statement)));
                    }
                    statement.setApplyPackageRule(enabled);
                    BanqueReleve processed = statement;
                    if (Boolean.TRUE.equals(request.getReprocess())) {
                        statement.setStatus(BanqueStatus.PROCESSING);
                        statement.setValidationErrors(null);
                    }
                    BanqueReleve saved = repository.save(statement);

                    if (Boolean.TRUE.equals(request.getReprocess())) {
                        try {
                            processed = processingService.reprocessStatement(id);
                            syncCmAppliedFromExpansions(processed.getId());
                        } catch (IllegalStateException e) {
                            return ResponseEntity.badRequest().body(Map.of(
                                    "error", e.getMessage(),
                                    "statement", toResponse(saved)));
                        } catch (Exception e) {
                            log.error("Échec lancement retraitement règle package pour relevé {}: {}", id, e.getMessage(), e);
                            Map<String, Object> response = buildRuleUpdateResponse(
                                    enabled ? "Règle package activée (retraitement non lancé)"
                                            : "Règle package désactivée (retraitement non lancé)",
                                    saved);
                            response.put("warning", "Échec lancement retraitement règle package: " + e.getMessage());
                            return ResponseEntity.ok(response);
                        }
                    }

                    return ResponseEntity.ok(buildRuleUpdateResponse(
                            enabled
                                    ? "Règle package activée" + (Boolean.TRUE.equals(request.getReprocess()) ? " et retraitement lancé" : "")
                                    : "Règle package désactivée" + (Boolean.TRUE.equals(request.getReprocess()) ? " et retraitement lancé" : ""),
                            processed));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> buildRuleUpdateResponse(String message, BanqueReleve statement) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", message);
        response.put("statement", toResponse(statement));
        return response;
    }

    // ==================== STATISTIQUES ====================

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public ResponseEntity<?> stats() {
        try {
            var stats = processingService.getStatistics();

            return ResponseEntity.ok(Map.of(
                    "total", stats.totalStatements,
                    "pending", stats.pendingStatements,
                    "processing", stats.processingStatements,
                    "treated", stats.treatedStatements,
                    "readyToValidate", stats.readyStatements,
                    "validated", stats.validatedStatements,
                    "accounted", stats.accountedStatements,
                    "error", stats.errorStatements,
                    "totalRibs", stats.totalRibs));
        } catch (Exception e) {
            log.error("❌ Erreur stats: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("error", "Erreur stats: " + e.getMessage()));
        }
    }

    @GetMapping("/bank-options")
    public ResponseEntity<?> bankOptions() {
        return ResponseEntity.ok(Map.of(
                "count", BanqueAliasResolver.bankChoices().size(),
                "options", BanqueAliasResolver.bankChoices()));
    }

    // ==================== FICHIERS ====================

    @GetMapping("/files/{filename}")
    @CrossOrigin("*")
    public ResponseEntity<Resource> getFile(@PathVariable("filename") String filename) {
        try {
            Optional<BanqueReleve> statementOpt = repository.findFirstByFilenameOrderByIdDesc(filename);
            if (statementOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            BanqueReleve statement = statementOpt.get();
            byte[] data = loadStatementFileBytes(statement);
            if (data == null || data.length == 0) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new ByteArrayResource(data);
            String contentType = statement.getFileContentType() != null
                    ? statement.getFileContentType()
                    : "application/octet-stream";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .body(resource);

        } catch (Exception e) {
            log.error("❌ Erreur récupération fichier: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private byte[] loadStatementFileBytes(BanqueReleve statement) {
        if (statement == null) {
            return null;
        }
        if (statement.getFileData() != null && statement.getFileData().length > 0) {
            return statement.getFileData();
        }
        String filePath = statement.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return null;
            }
            return Files.readAllBytes(path);
        } catch (Exception e) {
            log.warn("Impossible de lire le fichier relevé {} depuis {}: {}", statement.getId(), filePath, e.getMessage());
            return null;
        }
    }

    // ==================== HELPERS ====================

    private boolean isValidExtension(String filename) {
        if (filename == null)
            return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") || lower.endsWith(".pdf") ||
                lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    private boolean isValidMimeType(String contentType) {
        if (contentType == null)
            return false;
        String ct = contentType.toLowerCase();
        return ct.equals("image/jpeg") || ct.equals("image/jpg") ||
                ct.equals("image/png") || ct.equals("application/pdf") ||
                ct.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") ||
                ct.equals("application/vnd.ms-excel") ||
                ct.contains("spreadsheet") || ct.equals("application/octet-stream");
    }

    private List<String> normalizeAllowedBanks(List<String> allowedBanks) {
        List<String> cleanedAllowedBanks = new ArrayList<>();
        if (allowedBanks == null) {
            return cleanedAllowedBanks;
        }
        for (String bank : allowedBanks) {
            if (bank == null) {
                continue;
            }
            if (bank.contains(",")) {
                cleanedAllowedBanks.addAll(Arrays.asList(bank.split(",")));
            } else {
                cleanedAllowedBanks.add(bank);
            }
        }
        return cleanedAllowedBanks.stream()
                .map(BanqueAliasResolver::normalizeAllowedBankCode)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private Map<String, Object> toResponse(BanqueReleve statement) {
        return buildListResponse(statement);
    }

    private Map<String, Object> toDetailedResponse(BanqueReleve statement) {
        BanqueReleve source = resolveDisplaySource(statement);
        int fraisRuleAppliedCount = countAppliedRulesByPrefix(source, "FRAIS_");
        int agiosRuleAppliedCount = countAppliedRulesByPrefix(source, "AGIOS_");
        int packageRuleAppliedCount = countAppliedRulesByPrefix(source, "PACKAGE_");
        int ttcRuleAppliedCount = countAppliedRulesByPrefix(source, "COMMISSION_");
        Set<Long> cmLinkedTxIds = resolveCmLinkedTransactionIds(statement.getId());

        Map<String, Object> response = buildListResponse(statement);
        response.put("accountHolder", source.getAccountHolder());
        response.put("balanceDifference", source.getBalanceDifference());
        response.put("validationErrors", statement.getValidationErrors());
        response.put("filePath", statement.getFilePath());
        response.put("fileSize", statement.getFileSize());
        response.put("validatedAt", statement.getValidatedAt());
        response.put("validatedBy", statement.getValidatedBy());
        response.put("rawOcrText", source.getRawOcrText());
        response.put("cleanedOcrText", source.getCleanedOcrText());
        Map<String, String> accountLabelsByCode = accountLearningService.findAccountLibelles(
                source.getTransactions().stream()
                        .map(this::resolveDisplayedCompte)
                        .toList());

        Map<String, List<BanqueTransaction>> splitTransactionsByGroup = source.getTransactions().stream()
                .filter(this::isSplitTransaction)
                .filter(t -> t.getFraisSplitGroupId() != null && !t.getFraisSplitGroupId().isBlank())
                .collect(java.util.stream.Collectors.groupingBy(BanqueTransaction::getFraisSplitGroupId, LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));

        List<Map<String, Object>> transactions = source.getTransactions().stream()
                .sorted(Comparator
                        .comparing((BanqueTransaction t) -> t.getTransactionIndex() != null ? t.getTransactionIndex() : Integer.MAX_VALUE)
                        .thenComparingInt(t -> splitRoleOrder(t.getFraisSplitRole()))
                        .thenComparing(t -> t.getId() != null ? t.getId() : Long.MAX_VALUE))
                .map(t -> {
                    DisplayAmount displayAmount = resolveDisplayAmount(t, splitTransactionsByGroup);
                    String[] ocrDates = resolveDisplayDates(source, t);
                    Map<String, Object> txMap = new LinkedHashMap<>();
                    txMap.put("id", t.getId());
                    txMap.put("date", ocrDates[0]);
                    txMap.put("dateOperation", ocrDates[0]);
                    txMap.put("dateValeur", ocrDates[1]);
                    txMap.put("libelle", t.getLibelle());
                    txMap.put("description", t.getLibelle());
                    txMap.put("debit", displayAmount.debit());
                    txMap.put("credit", displayAmount.credit());
                    txMap.put("balance", t.getBalance() != null ? t.getBalance() : 0);
                    txMap.put("confidenceScore", t.getConfidenceScore() != null ? t.getConfidenceScore() : 0);
                    txMap.put("flags", t.getFlags() != null ? t.getFlags() : List.of());
                    txMap.put("transactionIndex", t.getTransactionIndex());
                    String displayedCompte = resolveDisplayedCompte(t);
                    txMap.put("compte", displayedCompte);
                    txMap.put("compteLibelle", accountLabelsByCode.getOrDefault(displayedCompte, ""));
                    txMap.put("isLinked", displayIsLinked(t.getIsLinked(), displayedCompte));
                    txMap.put("cmApplied", resolveEffectiveCmApplied(t, cmLinkedTxIds));
                    txMap.put("fraisRuleApplied", Boolean.TRUE.equals(t.getFraisRuleApplied()));
                    txMap.put("fraisSplitRole", t.getFraisSplitRole());
                    txMap.put("fraisSplitGroupId", t.getFraisSplitGroupId());
                    txMap.put("fraisOriginalAmount", t.getFraisOriginalAmount());
                    txMap.put("ruleLabel", resolveRuleLabel(t));
                    txMap.put("sens", t.getSens());
                    txMap.put("isValid", t.getIsValid());
                    return txMap;
                })
                .toList();
        response.put("transactions", transactions);
        response.put("transactionsPreview", transactions);
        response.put("fraisRuleAppliedCount", fraisRuleAppliedCount);
        response.put("hasFraisRuleApplied", fraisRuleAppliedCount > 0);
        response.put("agiosRuleAppliedCount", agiosRuleAppliedCount);
        response.put("hasAgiosRuleApplied", agiosRuleAppliedCount > 0);
        response.put("packageRuleAppliedCount", packageRuleAppliedCount);
        response.put("hasPackageRuleApplied", packageRuleAppliedCount > 0);
        response.put("ttcRuleAppliedCount", ttcRuleAppliedCount);
        response.put("hasTtcRuleApplied", ttcRuleAppliedCount > 0);
        if (fraisRuleAppliedCount > 0) {
            response.put("fraisRuleWarningMessage",
                    fraisRuleAppliedCount + " transaction(s) d'origine ont ete traitees par la regle frais.");
        }
        if (agiosRuleAppliedCount > 0) {
            response.put("agiosRuleWarningMessage",
                    agiosRuleAppliedCount + " transaction(s) d'origine ont ete traitees par la regle agios.");
        }
        if (packageRuleAppliedCount > 0) {
            response.put("packageRuleWarningMessage",
                    packageRuleAppliedCount + " transaction(s) d'origine ont ete traitees par la regle package.");
        }
        if (ttcRuleAppliedCount > 0) {
            response.put("ttcRuleWarningMessage",
                    ttcRuleAppliedCount + " transaction(s) d'origine ont ete traitees par la regle TTC / commission.");
        }

        return response;
    }

    private Set<Long> resolveCmLinkedTransactionIds(Long statementId) {
        if (statementId == null) {
            return Set.of();
        }
        try {
            List<CmExpansionDTO> expansions = centreMonetiqueLiaisonService.getCmExpansionsForStatement(statementId);
            if (expansions == null || expansions.isEmpty()) {
                return Set.of();
            }
            Set<Long> ids = new LinkedHashSet<>();
            for (CmExpansionDTO expansion : expansions) {
                if (expansion != null && expansion.bankTransactionId() != null) {
                    ids.add(expansion.bankTransactionId());
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("Lecture des liaisons CM ignorée pour relevé {}: {}", statementId, e.getMessage());
            return Set.of();
        }
    }

    private Map<String, Object> buildListResponse(BanqueReleve statement) {
        BanqueReleve source = resolveDisplaySource(statement);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", statement.getId());
        response.put("filename", statement.getFilename());
        response.put("originalName", statement.getOriginalName());
        response.put("status", mapDisplayStatus(statement));
        response.put("statusCode", statement.getStatus() != null ? statement.getStatus().name() : "UNKNOWN");
        response.put("rib", source.getRib());
        response.put("month", source.getMonth());
        response.put("year", source.getYear());
        response.put("bankName", source.getBankName());
        response.put("applyTtcRule", Boolean.TRUE.equals(statement.getApplyTtcRule()));
        response.put("applyFraisRule", Boolean.TRUE.equals(statement.getApplyFraisRule()));
        response.put("applyAgiosRule", Boolean.TRUE.equals(statement.getApplyAgiosRule()));
        response.put("applyPackageRule", Boolean.TRUE.equals(statement.getApplyPackageRule()));
        int fraisRuleAppliedCount = countAppliedRulesByPrefix(source, "FRAIS_");
        int agiosRuleAppliedCount = countAppliedRulesByPrefix(source, "AGIOS_");
        int packageRuleAppliedCount = countAppliedRulesByPrefix(source, "PACKAGE_");
        int ttcRuleAppliedCount = countAppliedRulesByPrefix(source, "COMMISSION_");
        response.put("fraisRuleAppliedCount", fraisRuleAppliedCount);
        response.put("hasFraisRuleApplied", fraisRuleAppliedCount > 0);
        response.put("agiosRuleAppliedCount", agiosRuleAppliedCount);
        response.put("hasAgiosRuleApplied", agiosRuleAppliedCount > 0);
        response.put("packageRuleAppliedCount", packageRuleAppliedCount);
        response.put("hasPackageRuleApplied", packageRuleAppliedCount > 0);
        response.put("ttcRuleAppliedCount", ttcRuleAppliedCount);
        response.put("hasTtcRuleApplied", ttcRuleAppliedCount > 0);
        response.put("openingBalance", source.getOpeningBalance());
        response.put("closingBalance", source.getClosingBalance());
        response.put("totalCredit", source.getTotalCredit());
        response.put("totalDebit", source.getTotalDebit());
        response.put("balanceDifference", source.getBalanceDifference());
        response.put("transactionCount", source.getTransactionCount());
        response.put("validTransactionCount", source.getValidTransactionCount());
        response.put("errorTransactionCount", source.getErrorTransactionCount());
        response.put("overallConfidence", source.getOverallConfidence());
        response.put("clientValidated", Boolean.TRUE.equals(statement.getClientValidated()));
        response.put("clientValidatedAt", statement.getClientValidatedAt());
        response.put("clientValidatedBy", statement.getClientValidatedBy());
        response.put("continuityStatus",
                source.getContinuityStatus() != null ? source.getContinuityStatus().name() : "UNKNOWN");
        response.put("isBalanceValid", source.getIsBalanceValid());
        response.put("isContinuityValid", source.getIsContinuityValid());
        response.put("isLinked", false);
        response.put("cmApplied", false);
        response.put("canReprocess", statement.isModifiable() && statement.getStatus() != BanqueStatus.PROCESSING);
        response.put("canDelete", canDeleteStatement(statement));
        response.put("createdAt", statement.getCreatedAt());
        response.put("updatedAt", statement.getUpdatedAt());
        response.put("accountedAt", statement.getAccountedAt());
        response.put("accountedBy", statement.getAccountedBy());
        response.put("detailPageUrl", "/bank-statements/detail.html?id=" + statement.getId());

        if (Boolean.TRUE.equals(statement.getIsDuplicate()) || statement.getStatus() == BanqueStatus.DUPLIQUE) {
            Long sourceId = extractDuplicateOfId(statement.getValidationErrors());
            response.put("alertType", "danger");
            response.put("alertMessage", "Ce relevé est un doublon du relevé #" + (sourceId != null ? sourceId : "?"));
            response.put("duplicateOfId", sourceId);
        } else if (ttcRuleAppliedCount > 0 || fraisRuleAppliedCount > 0
                || agiosRuleAppliedCount > 0 || packageRuleAppliedCount > 0) {
            response.put("alertType", "warning");
            List<String> parts = new ArrayList<>();
            if (ttcRuleAppliedCount > 0) {
                parts.add(ttcRuleAppliedCount + " par la regle TTC / commission");
            }
            if (fraisRuleAppliedCount > 0) {
                parts.add(fraisRuleAppliedCount + " par la regle frais");
            }
            if (agiosRuleAppliedCount > 0) {
                parts.add(agiosRuleAppliedCount + " par la regle agios");
            }
            if (packageRuleAppliedCount > 0) {
                parts.add(packageRuleAppliedCount + " par la regle package");
            }
            response.put("alertMessage", String.join(" | ", parts));
        }

        return response;
    }

    private Map<String, Object> buildFinalResponse(BanqueReleve statement, boolean includeTransactions) {
        BanqueReleve source = resolveDisplaySource(statement);
        List<BanqueTransaction> txs = source.getTransactions() != null ? source.getTransactions() : List.of();
        List<BanqueTransaction> txsWithOperationDate = txs.stream()
                .filter(t -> t.getDateOperation() != null)
                .toList();

        if (txsWithOperationDate.isEmpty()) {
            return Map.of("erreur", "Aucune date d'opération détectée");
        }

        LocalDate dateMin = txsWithOperationDate.stream()
                .map(BanqueTransaction::getDateOperation)
                .min(LocalDate::compareTo)
                .orElse(null);
        LocalDate dateMax = txsWithOperationDate.stream()
                .map(BanqueTransaction::getDateOperation)
                .max(LocalDate::compareTo)
                .orElse(null);

        String periode = formatPeriod(source.getMonth(), source.getYear());
        if ((periode == null || periode.isBlank()) && dateMin != null) {
            periode = formatPeriod(dateMin.getMonthValue(), dateMin.getYear());
        }

        BigDecimal totalDecaissement = txs.stream()
                .map(t -> t.getDebit() != null ? t.getDebit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEncaissement = txs.stream()
                .map(t -> t.getCredit() != null ? t.getCredit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("banque", source.getBankName() != null ? source.getBankName() : "");
        response.put("rib", source.getRib() != null ? source.getRib() : "");
        response.put("periode", periode);
        response.put("date_debut", dateMin != null ? dateMin.toString() : "");
        response.put("date_fin", dateMax != null ? dateMax.toString() : "");
        response.put("total_decaissement", totalDecaissement);
        response.put("total_encaissement", totalEncaissement);
        response.put("nombre_operations", txsWithOperationDate.size());
        response.put("statut", statement.getStatus() == BanqueStatus.COMPTABILISE ? "COMPTABILISE" : "PRET_A_VALIDER");
        return response;
    }

    private String formatStatementDate(LocalDate date) {
        if (date == null) {
            return "";
        }
        return date.format(STATEMENT_DATE_FORMATTER);
    }

    private String[] resolveDisplayDates(BanqueReleve statement, BanqueTransaction transaction) {
        LocalDate periodStart = getStatementPeriodStart(statement);
        String dateOperation = formatStatementDate(clampToStatementPeriod(transaction.getDateOperation(), periodStart));
        String dateValeur = formatStatementDate(clampToStatementPeriod(transaction.getDateValeur(), periodStart));
        String raw = transaction.getRawOcrLine();

        if (!dateOperation.isBlank() && !dateValeur.isBlank()) {
            return new String[] { dateOperation, dateValeur };
        }

        if (raw == null || raw.isBlank()) {
            return new String[] { dateOperation, dateValeur };
        }

        Matcher matcher = OCR_DATE_PATTERN.matcher(raw);
        List<String> foundDates = new ArrayList<>();
        while (matcher.find() && foundDates.size() < 2) {
            String captured = matcher.group(1);
            LocalDate parsed = parseOcrDisplayDate(captured);
            if (parsed != null) {
                foundDates.add(formatStatementDate(clampToStatementPeriod(parsed, periodStart)));
            }
        }

        if (!foundDates.isEmpty()) {
            if (dateOperation.isBlank()) {
                dateOperation = foundDates.get(0);
            }
            if (dateValeur.isBlank()) {
                dateValeur = foundDates.size() > 1 ? foundDates.get(1) : foundDates.get(0);
            }
        }

        return new String[] { dateOperation, dateValeur };
    }

    private boolean isValidatedDocumentDeletionAllowed(Long dossierId) {
        return dossierGeneralParamsDao.findByDossierId(dossierId)
                .map(params -> Boolean.TRUE.equals(params.getAllowValidatedDocumentDeletion()))
                .orElse(false);
    }

    private boolean isAccountedDocumentDeletionAllowed(Long dossierId) {
        return dossierGeneralParamsDao.findByDossierId(dossierId)
                .map(params -> Boolean.TRUE.equals(params.getAllowAccountedDocumentDeletion()))
                .orElse(false);
    }

    private boolean canDeleteStatement(BanqueReleve statement) {
        if (statement == null) {
            return false;
        }
        BanqueStatus status = statement.getStatus();
        Long dossierId = statement.getDossierId();
        if (status == BanqueStatus.COMPTABILISE) {
            return isAccountedDocumentDeletionAllowed(dossierId);
        }
        if (Boolean.TRUE.equals(statement.getClientValidated()) || status == BanqueStatus.VALIDATED) {
            return isValidatedDocumentDeletionAllowed(dossierId);
        }
        return statement.isModifiable();
    }

    private boolean resolveEffectiveCmApplied(BanqueTransaction transaction, Set<Long> linkedTxIds) {
        if (transaction == null) {
            return false;
        }
        if (Boolean.TRUE.equals(transaction.getCmAppliedUserDisabled())) {
            return false;
        }
        if (Boolean.TRUE.equals(transaction.getCmApplied())) {
            return true;
        }
        return linkedTxIds != null && transaction.getId() != null && linkedTxIds.contains(transaction.getId());
    }

    private boolean canAccessStatementInDossier(SessionUser sessionUser, BanqueReleve statement, Long dossierId) {
        if (sessionUser == null || statement == null || dossierId == null) {
            return false;
        }
        Long statementDossierId = statement.getDossierId();
        if (statementDossierId == null || !dossierId.equals(statementDossierId)) {
            return false;
        }
        if (sessionUser.isAdmin()) {
            return true;
        }
        if (sessionUser.isComptable()) {
            return dossierDao.findByIdAndComptableId(dossierId, sessionUser.id()).isPresent();
        }
        if (sessionUser.isClient()) {
            return dossierDao.findByIdAndClientId(dossierId, sessionUser.id()).isPresent();
        }
        return false;
    }

    private LocalDate getStatementPeriodStart(BanqueReleve statement) {
        if (statement == null || statement.getMonth() == null || statement.getYear() == null) {
            return null;
        }
        try {
            return LocalDate.of(statement.getYear(), statement.getMonth(), 1);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate clampToStatementPeriod(LocalDate date, LocalDate periodStart) {
        if (date == null || periodStart == null) {
            return date;
        }
        return date.isBefore(periodStart) ? periodStart : date;
    }

    private LocalDate parseOcrDisplayDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }

        String normalized = rawDate.trim()
                .replaceAll("\\s*[\\/\\-.]\\s*", "/")
                .replaceAll("\\s+", "/");

        String[] parts = normalized.split("/");
        if (parts.length != 3) {
            return null;
        }

        try {
            int day = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int year = Integer.parseInt(parts[2]);
            if (year < 100) {
                year += 2000;
            }
            if (day < 1 || day > 31 || month < 1 || month > 12 || year < 1900 || year > 2100) {
                return null;
            }
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    private BanqueReleve resolveDisplaySource(BanqueReleve statement) {
        if (statement == null) {
            return null;
        }
        if (!Boolean.TRUE.equals(statement.getIsDuplicate()) && statement.getStatus() != BanqueStatus.DUPLIQUE) {
            return statement;
        }

        Long duplicateOfId = extractDuplicateOfId(statement.getValidationErrors());
        if (duplicateOfId != null) {
            return repository.findById(duplicateOfId).orElse(statement);
        }

        if (statement.getDuplicateHash() != null && !statement.getDuplicateHash().isBlank()) {
            return repository.findAllByDuplicateHashOrderByCreatedAtDescIdDesc(statement.getDuplicateHash())
                    .stream()
                    .filter(candidate -> !Objects.equals(candidate.getId(), statement.getId()))
                    .findFirst()
                    .orElse(statement);
        }
        return statement;
    }

    private Long extractDuplicateOfId(String validationErrors) {
        if (validationErrors == null) {
            return null;
        }
        Matcher matcher = DUPLICATE_OF_PATTERN.matcher(validationErrors);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatPeriod(Integer month, Integer year) {
        if (month == null || year == null) {
            return "";
        }
        return String.format("%02d/%04d", month, year);
    }

    private String displayCompte(String compte) {
        if (compte == null || compte.isBlank()) {
            return DEFAULT_COMPTE;
        }
        return compte.trim();
    }

    private String resolveDisplayedCompte(BanqueTransaction transaction) {
        String current = displayCompte(transaction.getCompte());
        if (!DEFAULT_COMPTE.equals(current)) {
            return current;
        }
        return accountLearningService.findSuggestedAccount(transaction.getLibelle()).orElse(current);
    }

    private DisplayAmount resolveDisplayAmount(BanqueTransaction transaction, Map<String, List<BanqueTransaction>> splitTransactionsByGroup) {
        BigDecimal rawDebit = transaction.getDebit() != null ? transaction.getDebit() : BigDecimal.ZERO;
        BigDecimal rawCredit = transaction.getCredit() != null ? transaction.getCredit() : BigDecimal.ZERO;
        if (!isSplitBankRole(transaction.getFraisSplitRole())) {
            return new DisplayAmount(rawDebit, rawCredit);
        }

        BigDecimal originalAmount = transaction.getFraisOriginalAmount();
        if (originalAmount == null || originalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            originalAmount = rawDebit.compareTo(BigDecimal.ZERO) > 0 ? rawDebit : rawCredit;
        }

        List<BanqueTransaction> grouped = splitTransactionsByGroup.getOrDefault(transaction.getFraisSplitGroupId(), List.of());
        boolean feesOnDebit = grouped.stream()
                .filter(candidate -> !isSplitBankRole(candidate.getFraisSplitRole()))
                .anyMatch(candidate -> candidate.getDebit() != null && candidate.getDebit().compareTo(BigDecimal.ZERO) > 0);
        boolean feesOnCredit = grouped.stream()
                .filter(candidate -> !isSplitBankRole(candidate.getFraisSplitRole()))
                .anyMatch(candidate -> candidate.getCredit() != null && candidate.getCredit().compareTo(BigDecimal.ZERO) > 0);

        if (feesOnDebit && !feesOnCredit) {
            return new DisplayAmount(BigDecimal.ZERO, originalAmount);
        }
        if (feesOnCredit && !feesOnDebit) {
            return new DisplayAmount(originalAmount, BigDecimal.ZERO);
        }
        return new DisplayAmount(rawDebit, rawCredit);
    }

    private boolean displayIsLinked(Boolean isLinked, String displayedCompte) {
        if (Boolean.TRUE.equals(isLinked)) {
            return true;
        }
        return displayedCompte != null && !displayedCompte.isBlank();
    }

    private void syncCmAppliedFromExpansions(Long statementId) {
        if (statementId == null) {
            return;
        }
        try {
            List<CmExpansionDTO> expansions = centreMonetiqueLiaisonService.getCmExpansionsForStatement(statementId);
            if (expansions == null || expansions.isEmpty()) {
                return;
            }

            Set<Long> linkedTxIds = new HashSet<>();
            for (CmExpansionDTO expansion : expansions) {
                if (expansion != null && expansion.bankTransactionId() != null) {
                    linkedTxIds.add(expansion.bankTransactionId());
                }
            }
            if (linkedTxIds.isEmpty()) {
                return;
            }

            List<BanqueTransaction> linkedTransactions = transactionRepository.findAllById(linkedTxIds);
            boolean changed = false;
            for (BanqueTransaction tx : linkedTransactions) {
                if (Boolean.TRUE.equals(tx.getCmAppliedUserDisabled())) {
                    if (!Boolean.FALSE.equals(tx.getCmApplied())) {
                        tx.setCmApplied(false);
                        changed = true;
                    }
                    continue;
                }
                if (!Boolean.TRUE.equals(tx.getCmApplied())) {
                    tx.setCmApplied(true);
                    changed = true;
                }
            }
            if (changed) {
                transactionRepository.saveAll(linkedTransactions);
            }
        } catch (Exception e) {
            log.warn("Synchronisation CM ignorée pour relevé {}: {}", statementId, e.getMessage());
        }
    }

    private int countAppliedRulesByPrefix(BanqueReleve statement, String prefix) {
        if (statement == null || statement.getTransactions() == null || statement.getTransactions().isEmpty()) {
            return 0;
        }
        Set<String> splitGroupIds = new LinkedHashSet<>();
        int fallbackCount = 0;
        for (BanqueTransaction transaction : statement.getTransactions()) {
            if (!Boolean.TRUE.equals(transaction.getFraisRuleApplied())
                    || !isSplitRolePrefix(transaction.getFraisSplitRole(), prefix)) {
                continue;
            }
            String groupId = transaction.getFraisSplitGroupId();
            if (groupId != null && !groupId.isBlank()) {
                splitGroupIds.add(groupId.trim());
            } else {
                fallbackCount++;
            }
        }
        return splitGroupIds.size() + fallbackCount;
    }

    private String resolveRuleLabel(BanqueTransaction transaction) {
        if (!Boolean.TRUE.equals(transaction.getFraisRuleApplied())
                || !isAnySplitRole(transaction.getFraisSplitRole())) {
            return "";
        }
        String splitRole = transaction.getFraisSplitRole();
        if ("FRAIS_TVA".equals(splitRole)) {
            return "Regle frais - TVA";
        }
        if ("FRAIS_REMISE_NET".equals(splitRole)) {
            return "Regle frais - Remise nette";
        }
        if ("AGIOS_TVA".equals(splitRole)) {
            return "Regle agios - TVA";
        }
        if ("AGIOS_REMISE_NET".equals(splitRole)) {
            return "Regle agios - Remise nette";
        }
        if ("PACKAGE_TVA".equals(splitRole)) {
            return "Regle package - TVA";
        }
        if ("PACKAGE_REMISE_NET".equals(splitRole)) {
            return "Regle package - Remise nette";
        }
        if ("COMMISSION_TVA".equals(splitRole)) {
            return "Regle TTC - TVA";
        }
        if ("COMMISSION_REMISE_NET".equals(splitRole)) {
            return "Regle TTC - Remise nette";
        }
        if (splitRole != null && splitRole.startsWith("COMMISSION_")) {
            return "Regle TTC - HT";
        }
        if (splitRole != null && splitRole.startsWith("AGIOS_")) {
            return "Regle agios - HT";
        }
        if (splitRole != null && splitRole.startsWith("PACKAGE_")) {
            return "Regle package - HT";
        }
        return "Regle frais - HT";
    }

    private boolean isSplitRolePrefix(String splitRole, String prefix) {
        return splitRole != null && splitRole.startsWith(prefix);
    }

    private boolean isFraisSplitRole(String splitRole) {
        return isSplitRolePrefix(splitRole, "FRAIS_");
    }

    private boolean isAgiosSplitRole(String splitRole) {
        return isSplitRolePrefix(splitRole, "AGIOS_");
    }

    private boolean isPackageSplitRole(String splitRole) {
        return isSplitRolePrefix(splitRole, "PACKAGE_");
    }

    private boolean isTtcSplitRole(String splitRole) {
        return isSplitRolePrefix(splitRole, "COMMISSION_");
    }

    private boolean isAnySplitRole(String splitRole) {
        return isFraisSplitRole(splitRole) || isAgiosSplitRole(splitRole)
                || isPackageSplitRole(splitRole) || isTtcSplitRole(splitRole);
    }

    private boolean isSplitTransaction(BanqueTransaction transaction) {
        return transaction != null
                && Boolean.TRUE.equals(transaction.getFraisRuleApplied())
                && isAnySplitRole(transaction.getFraisSplitRole());
    }

    private boolean isSplitBankRole(String splitRole) {
        return splitRole != null && splitRole.endsWith("_REMISE_NET");
    }

    private int splitRoleOrder(String splitRole) {
        if (splitRole == null) {
            return 99;
        }
        if (splitRole.endsWith("_HT")) {
            return 1;
        }
        if (splitRole.endsWith("_TVA")) {
            return 2;
        }
        if (splitRole.endsWith("_REMISE_NET")) {
            return 3;
        }
        return 99;
    }

    private record DisplayAmount(BigDecimal debit, BigDecimal credit) {
    }

    private String mapStatus(BanqueStatus status) {
        if (status == null) {
            return "A_VERIFIER";
        }
        return switch (status) {
            case PENDING -> "EN_ATTENTE";
            case PROCESSING -> "EN_COURS";
            case READY_TO_VALIDATE -> "PRET_A_VALIDER";
            case TREATED -> "A_VERIFIER";
            case VALIDATED -> "VALIDE";
            case COMPTABILISE -> "COMPTABILISE";
            case ERROR -> "ERREUR";
            case VIDE -> "VIDE";
            case DUPLIQUE -> "DUPLIQUE";
            default -> "A_VERIFIER";
        };
    }

    private String mapDisplayStatus(BanqueReleve statement) {
        if (statement == null) {
            return "A_VERIFIER";
        }

        // Statuts techniques : afficher tels quels
        if (statement.getStatus() == BanqueStatus.ERROR
                || statement.getStatus() == BanqueStatus.PROCESSING
                || statement.getStatus() == BanqueStatus.PENDING
                || statement.getStatus() == BanqueStatus.VALIDATED
                || statement.getStatus() == BanqueStatus.COMPTABILISE) {
            return mapStatus(statement.getStatus());
        }

        if (Boolean.TRUE.equals(statement.getIsDuplicate())) {
            return "DUPLIQUE";
        }

        boolean isEmpty = (statement.getRib() == null || statement.getRib().isBlank())
                && statement.getTransactionCount() == 0
                && statement.getTotalDebitPdf() == null
                && statement.getTotalCreditPdf() == null;
        if (isEmpty) {
            return "VIDE";
        }

        // "À vérifier" uniquement si les totaux calculés diffèrent des totaux OCR
        if (hasTotalMismatch(statement)) {
            return "A_VERIFIER";
        }

        return "PRET_A_VALIDER";
    }

    private boolean hasTotalMismatch(BanqueReleve statement) {
        BigDecimal debitPdf = statement.getTotalDebitPdf();
        BigDecimal creditPdf = statement.getTotalCreditPdf();
        if (debitPdf == null && creditPdf == null) {
            return false;
        }
        BigDecimal debitCalc = statement.getTotalDebit() != null ? statement.getTotalDebit() : BigDecimal.ZERO;
        BigDecimal creditCalc = statement.getTotalCredit() != null ? statement.getTotalCredit() : BigDecimal.ZERO;
        boolean debitOk = debitPdf == null
                || debitPdf.subtract(debitCalc).abs().compareTo(new BigDecimal("0.01")) <= 0;
        boolean creditOk = creditPdf == null
                || creditPdf.subtract(creditCalc).abs().compareTo(new BigDecimal("0.01")) <= 0;
        return !(debitOk && creditOk);
    }

    public static class UpdateBankStatementStatusRequest {
        private String status;
        private String updatedBy;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getUpdatedBy() {
            return updatedBy;
        }

        public void setUpdatedBy(String updatedBy) {
            this.updatedBy = updatedBy;
        }
    }

    public static class UpdateTtcRuleRequest {
        private Boolean enabled;
        private Boolean reprocess = true;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Boolean getReprocess() {
            return reprocess;
        }

        public void setReprocess(Boolean reprocess) {
            this.reprocess = reprocess;
        }
    }

    // ==================== DOSSIER HELPERS (même pattern que CentreMonetiqueController) ====================

    private Long resolveOrFallbackDossierId(SessionUser sessionUser, Long requestedDossierId, HttpSession session) {
        Long resolvedDossierId = resolveDossierId(sessionUser, requestedDossierId, session);
        if (resolvedDossierId == null && (sessionUser.isAdmin() || sessionUser.isComptable())) {
            var firstDossier = dossierDao.findFirstByOrderByCreatedAtDesc();
            if (firstDossier.isPresent()) {
                resolvedDossierId = firstDossier.get().getId();
            }
        }
        return resolvedDossierId;
    }

    private Long resolveDossierId(SessionUser sessionUser, Long requestedDossierId, HttpSession session) {
        if (requestedDossierId != null) {
            Dossier requested = requireDossierForUser(sessionUser, requestedDossierId);
            if (requested != null) {
                session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, requestedDossierId);
                return requestedDossierId;
            }
            return null;
        }
        Object rawId = session.getAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
        if (rawId == null) {
            return null;
        }
        try {
            Long sessionDossierId = Long.valueOf(rawId.toString());
            Dossier sessionDossier = requireDossierForUser(sessionUser, sessionDossierId);
            return sessionDossier != null ? sessionDossierId : null;
        } catch (NumberFormatException e) {
            session.removeAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
            return null;
        }
    }

    private Dossier requireDossierForUser(SessionUser sessionUser, Long dossierId) {
        if (sessionUser == null || dossierId == null) {
            return null;
        }
        if (sessionUser.isAdmin()) {
            return dossierDao.findById(dossierId).orElse(null);
        }
        if (sessionUser.isComptable()) {
            return dossierDao.findByIdAndComptableId(dossierId, sessionUser.id()).orElse(null);
        }
        if (sessionUser.isClient()) {
            return dossierDao.findByIdAndClientId(dossierId, sessionUser.id()).orElse(null);
        }
        return null;
    }
}
