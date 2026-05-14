package com.invoice_reader.invoice_reader.controller.dynamic;

import com.invoice_reader.invoice_reader.dto.account_tier.TierDto;
import com.invoice_reader.invoice_reader.entity.auth.Dossier;
import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.entity.dynamic.DynamicInvoice;
import com.invoice_reader.invoice_reader.entity.account_tier.Tier;
import com.invoice_reader.invoice_reader.entity.invoice.InvoiceStatus;
import com.invoice_reader.invoice_reader.repository.AccountingEntryDao;
import com.invoice_reader.invoice_reader.repository.DossierDao;
import com.invoice_reader.invoice_reader.repository.DossierGeneralParamsDao;
import com.invoice_reader.invoice_reader.repository.DynamicInvoiceDao;
import com.invoice_reader.invoice_reader.repository.FieldLearningDataDao;
import com.invoice_reader.invoice_reader.servises.FileStorageService;
import com.invoice_reader.invoice_reader.servises.account_tier.TierService;
import com.invoice_reader.invoice_reader.servises.auth.AuthService;
import com.invoice_reader.invoice_reader.servises.auth.SessionKeys;
import com.invoice_reader.invoice_reader.servises.auth.SessionUser;
import com.invoice_reader.invoice_reader.servises.dynamic.DynamicInvoiceProcessingService;
import com.invoice_reader.invoice_reader.servises.dynamic.ExtractionEngine;
import com.invoice_reader.invoice_reader.servises.ocr.AmountValidatorService;
import com.invoice_reader.invoice_reader.security.RequireRole;
import com.invoice_reader.invoice_reader.utils.AmountToWordsFormatter;
import com.invoice_reader.invoice_reader.utils.InvoiceTypeDetector;
import com.invoice_reader.invoice_reader.utils.ExercisePeriodException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/dynamic-invoices")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class DynamicInvoiceController {

    private final DynamicInvoiceDao dynamicInvoiceDao;
    private final DynamicInvoiceProcessingService processingService;
    private final FileStorageService fileStorageService;
    private final TierService tierService;
    private final AuthService authService;
    private final DossierDao dossierDao;
    private final AccountingEntryDao accountingEntryDao;
    private final FieldLearningDataDao fieldLearningDataDao;
    private final AmountValidatorService amountValidatorService;
    private final DossierGeneralParamsDao dossierGeneralParamsDao;

    @PostMapping("/upload")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> uploadAndProcess(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(value = "engine", required = false) String engine,
            @RequestParam(value = "ocrMode", required = false) String ocrMode,
            @RequestParam(value = "useAlphaAgent", required = false) Boolean useAlphaAgent,
            HttpSession session) {
        log.info("Upload fichier: {}", file.getOriginalFilename());

        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        Dossier dossier = requireDossierForUser(sessionUser, resolvedDossierId);
        if (dossier == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "dossier_forbidden"));
        }

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Fichier vide"));
            }

            String originalName = file.getOriginalFilename();
            if (originalName == null || !isValidFileType(originalName)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Type de fichier non supporte",
                        "supportedTypes", List.of("PDF", "JPG", "JPEG", "PNG")));
            }

            DynamicInvoice processed;
            if (sessionUser.isClient()) {
                processed = createUploadOnlyInvoice(file);
            } else {
                processed = processingService.processInvoice(
                        file,
                        resolvedDossierId,
                        resolveExtractionEngine(engine, ocrMode, useAlphaAgent));
                if (sessionUser.isAdmin()) {
                    processed.setClientValidated(true);
                    processed.setClientValidatedAt(java.time.LocalDateTime.now());
                    processed.setClientValidatedBy(sessionUser.username());
                }
            }

            if (dossier != null) {
                processed.setDossier(dossier);
                processed.setDossierId(dossier.getId());
                processed = dynamicInvoiceDao.save(processed);
            }

            return ResponseEntity.ok(toResponse(processed));

        } catch (ExercisePeriodException e) {
            String message = String.format(
                    "Facture hors période d'exercice (du %s au %s). Date facture: %s",
                    e.getExerciseStartDate(),
                    e.getExerciseEndDate(),
                    e.getDocumentDate());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", message,
                    "exerciseStartDate", e.getExerciseStartDate(),
                    "exerciseEndDate", e.getExerciseEndDate(),
                    "invoiceDate", e.getDocumentDate()
            ));
        } catch (Exception e) {
            log.error("Erreur upload: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/upload/batch")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> uploadAndProcessBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(value = "engine", required = false) String engine,
            @RequestParam(value = "ocrMode", required = false) String ocrMode,
            @RequestParam(value = "useAlphaAgent", required = false) Boolean useAlphaAgent,
            HttpSession session) {
        int count = files != null ? files.length : 0;
        log.info("Upload batch: {} fichiers", count);

        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        Dossier dossier = requireDossierForUser(sessionUser, resolvedDossierId);
        if (dossier == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "dossier_forbidden"));
        }

        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Aucun fichier fourni"));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> batchNames = new HashSet<>();
        int successCount = 0;

        for (MultipartFile file : files) {
            String originalName = file != null ? file.getOriginalFilename() : null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filename", originalName != null ? originalName : "unknown");

            try {
                if (file == null || file.isEmpty()) {
                    item.put("status", "error");
                    item.put("error", "Fichier vide");
                    results.add(item);
                    continue;
                }

                if (originalName == null || !isValidFileType(originalName)) {
                    item.put("status", "error");
                    item.put("error", "Type de fichier non supporte");
                    item.put("supportedTypes", List.of("PDF", "JPG", "JPEG", "PNG"));
                    results.add(item);
                    continue;
                }

                String normalizedName = normalizeKey(originalName);
                boolean duplicateInBatch = batchNames.contains(normalizedName);
                batchNames.add(normalizedName);

                boolean duplicateInDossier = false;
                try {
                    duplicateInDossier = isDuplicateFilename(resolvedDossierId, originalName);
                } catch (Exception duplicateCheckError) {
                    log.warn("Verification doublon nom ignoree pour {}: {}", originalName, duplicateCheckError.getMessage());
                }

                DynamicInvoice processed = sessionUser.isClient()
                        ? createUploadOnlyInvoice(file)
                        : processingService.processInvoice(
                                file,
                                resolvedDossierId,
                                resolveExtractionEngine(engine, ocrMode, useAlphaAgent));
                if (!sessionUser.isClient() && sessionUser.isAdmin()) {
                    processed.setClientValidated(true);
                    processed.setClientValidatedAt(java.time.LocalDateTime.now());
                    processed.setClientValidatedBy(sessionUser.username());
                }

                String invoiceNumber = processed != null && processed.getFieldsData() != null
                        ? getStringValue(processed.getFieldsData(), "invoiceNumber")
                        : null;
                boolean duplicateInvoiceNumber = false;
                if (invoiceNumber != null && !invoiceNumber.isBlank()) {
                    try {
                        duplicateInvoiceNumber = dynamicInvoiceDao.countByDossierIdAndInvoiceNumber(
                                resolvedDossierId,
                                normalizeKey(invoiceNumber)) > 0;
                    } catch (Exception duplicateCheckError) {
                        log.warn("Verification doublon numero ignoree pour {}: {}", invoiceNumber, duplicateCheckError.getMessage());
                    }
                }

                if (dossier != null) {
                    processed.setDossier(dossier);
                    processed.setDossierId(dossier.getId());
                    processed = dynamicInvoiceDao.save(processed);
                }
                if (duplicateInBatch || duplicateInDossier || duplicateInvoiceNumber) {
                    Map<String, Object> warningData = new LinkedHashMap<>();
                    warningData.put("duplicateInBatch", duplicateInBatch);
                    warningData.put("duplicateInDossier", duplicateInDossier);
                    warningData.put("duplicateInvoiceNumber", duplicateInvoiceNumber);
                    warningData.put("message", "Facture importee avec alerte doublon");
                    item.put("warnings", warningData);
                }
                item.put("status", "success");
                item.put("invoice", toResponse(processed));
                results.add(item);
                successCount++;
            } catch (ExercisePeriodException e) {
                String message = String.format(
                        "Facture hors période d'exercice (du %s au %s). Date facture: %s",
                        e.getExerciseStartDate(),
                        e.getExerciseEndDate(),
                        e.getDocumentDate());
                item.put("status", "error");
                item.put("error", message);
                item.put("exerciseStartDate", e.getExerciseStartDate());
                item.put("exerciseEndDate", e.getExerciseEndDate());
                item.put("invoiceDate", e.getDocumentDate());
                results.add(item);
            } catch (Exception e) {
                log.error("Erreur upload batch ({}): {}", originalName, e.getMessage(), e);
                item.put("status", "error");
                item.put("error", e.getMessage());
                results.add(item);
            }
        }

        int errorCount = results.size() - successCount;
        return ResponseEntity.ok(Map.of(
                "count", results.size(),
                "successCount", successCount,
                "errorCount", errorCount,
                "results", results
        ));
    }

    @PostMapping("/{id}/extract-field")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> extractField(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }

        String fieldName = payload != null ? String.valueOf(payload.getOrDefault("fieldName", "")).trim() : "";
        if (fieldName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fieldName_required"));
        }

        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    try {
                        Map<String, Object> singleField = processingService.extractSingleField(invoice, fieldName);
                        return ResponseEntity.ok(Map.of(
                                "success", true,
                                "invoiceId", id,
                                "fieldName", fieldName,
                                "result", singleField
                        ));
                    } catch (Exception e) {
                        log.error("Erreur extraction champ {} pour facture {}: {}", fieldName, id, e.getMessage(), e);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/process")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> reprocess(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        log.info("Retraitement facture: {}", id);
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    try {
                        DynamicInvoice processed = processingService.reprocessExistingInvoice(invoice);
                        return ResponseEntity.ok(toResponse(processed));
                    } catch (ExercisePeriodException e) {
                        String message = String.format(
                                "Facture hors période d'exercice (du %s au %s). Date facture: %s",
                                e.getExerciseStartDate(),
                                e.getExerciseEndDate(),
                                e.getDocumentDate());
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", message,
                                "exerciseStartDate", e.getExerciseStartDate(),
                                "exerciseEndDate", e.getExerciseEndDate(),
                                "invoiceDate", e.getDocumentDate()
                        ));
                    } catch (Exception e) {
                        log.error("Erreur retraitement facture {}: {}", id, e.getMessage(), e);
                        String errorMessage = e.getMessage();
                        if (errorMessage == null || errorMessage.isBlank()) {
                            errorMessage = e.getClass().getSimpleName();
                        } else {
                            errorMessage = e.getClass().getSimpleName() + ": " + errorMessage;
                        }
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(Map.of("error", errorMessage));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> getById(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    return ResponseEntity.ok(toDetailedResponse(invoice));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/check-duplicate")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> checkDuplicate(
            @RequestParam(value = "supplier", required = false) String supplier,
            @RequestParam(value = "invoiceNumber", required = false) String invoiceNumber,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "dossier_required",
                    "code", "DOSSIER_REQUIRED"));
        }
        if (supplier == null || supplier.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "supplier_required",
                    "code", "SUPPLIER_REQUIRED"));
        }
        String searchValue = firstNonBlank(invoiceNumber, filename);
        if (searchValue == null || searchValue.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "invoiceNumber_or_filename_required",
                    "code", "SEARCH_VALUE_REQUIRED"));
        }

        boolean exists = dynamicInvoiceDao.existsValidatedDuplicateBySupplierAndInvoiceOrFilename(
                resolvedDossierId,
                supplier,
                searchValue
        ) > 0;
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of(
                        "exists", exists,
                        "matchedBy", invoiceNumber != null && !invoiceNumber.isBlank() ? "invoiceNumber" : "filename"
                )
        ));
    }

    @GetMapping
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long templateId,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(defaultValue = "50") int limit,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        dossierId = resolveDossierId(sessionUser, dossierId, session);
        if (dossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }
        if (requireDossierForUser(sessionUser, dossierId) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "dossier_forbidden"));
        }

        List<DynamicInvoice> invoices;
        if (status != null) {
            InvoiceStatus invoiceStatus = InvoiceStatus.valueOf(status.toUpperCase());
            invoices = dynamicInvoiceDao.findByStatusAndDossierIdOrderByCreatedAtDesc(invoiceStatus, dossierId);
        } else if (templateId != null) {
            invoices = dynamicInvoiceDao.findByTemplateIdAndDossierIdOrderByCreatedAtDesc(templateId, dossierId);
        } else {
            invoices = dynamicInvoiceDao.findByDossierIdOrderByCreatedAtDesc(dossierId);
        }

        if (!sessionUser.isClient()) {
            invoices = invoices.stream()
                    .filter(invoice -> Boolean.TRUE.equals(invoice.getClientValidated()))
                    .toList();
        }

        List<Map<String, Object>> response = invoices.stream()
                .limit(limit)
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(Map.of(
                "count", response.size(),
                "invoices", response));
    }

    @DeleteMapping("/{id}")
    @Transactional
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> delete(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        log.info("Suppression facture: {}", id);
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    if (sessionUser != null && sessionUser.isClient()
                            && Boolean.TRUE.equals(invoice.getClientValidated())) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "client_validated"));
                    }
                    if (sessionUser != null && sessionUser.isComptable() && !sessionUser.isAdmin()
                            && !Boolean.TRUE.equals(invoice.getClientValidated())) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "client_not_visible"));
                    }
                    // Accounted (comptabilisé): ADMIN only + flag allowAccountedDocumentDeletion
                    if (Boolean.TRUE.equals(invoice.getAccounted()) || invoice.getAccountedAt() != null) {
                        if (sessionUser == null || !sessionUser.isAdmin()) {
                            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                    .body(Map.of("error", "accounted_document_deletion_admin_only"));
                        }
                        if (!isAccountedDocumentDeletionAllowed(resolvedDossierId)) {
                            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                    .body(Map.of("error", "accounted_document_deletion_disabled"));
                        }
                    } else if (Boolean.TRUE.equals(invoice.getClientValidated())
                            && !isValidatedDocumentDeletionAllowed(resolvedDossierId)) {
                        // Client-validated but NOT accounted: flag allowValidatedDocumentDeletion
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "validated_document_deletion_disabled"));
                    }
                    String filePath = invoice.getFilePath();
                    try {
                        accountingEntryDao.deleteByInvoiceId(invoice.getId());
                        fieldLearningDataDao.deleteByInvoiceId(invoice.getId());
                        dynamicInvoiceDao.delete(invoice);
                    } catch (DataIntegrityViolationException ex) {
                        log.error("Suppression facture {} bloquee par contraintes de donnees", id, ex);
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of(
                                        "error", "invoice_delete_conflict",
                                        "message", "Impossible de supprimer la facture car des donnees liees existent encore."));
                    }

                    if (filePath != null && !filePath.isBlank()) {
                        try {
                            Files.deleteIfExists(Path.of(filePath));
                        } catch (Exception e) {
                            log.warn("Facture {} supprimee en base, mais fichier non supprime: {} ({})",
                                    id, filePath, e.getMessage());
                        }
                    }

                    return ResponseEntity.noContent().build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/fields")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> updateFields(
            @PathVariable Long id,
            @RequestBody Map<String, Object> fields,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        if (fields == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "fields_required",
                    "message", "Aucune donnée à enregistrer"));
        }
        log.info("Modification champs facture {}: {}", id, fields.keySet());
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    if (!invoice.isModifiable()) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of(
                                        "error", "Facture validee, modification impossible",
                                        "code", "invoice_locked"));
                    }

                    Map<String, Object> currentFields = invoice.getFieldsData();
                    if (currentFields == null) {
                        currentFields = new LinkedHashMap<>();
                    }
                    currentFields.putAll(fields);
                    try {
                        amountValidatorService.applyToFieldsData(currentFields);
                        syncAmountTtcEnLettres(currentFields);
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "invalid_fields",
                                "message", e.getMessage()));
                    }

                    String candidateInvoiceNumber = firstNonBlank(
                            getStringValue(currentFields, "invoiceNumber"),
                            getStringValue(currentFields, "numeroFacture")
                    );
                    if (candidateInvoiceNumber != null
                            && !candidateInvoiceNumber.isBlank()
                            && hasAnotherInvoiceWithSameNumber(resolvedDossierId, candidateInvoiceNumber, invoice.getId())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of(
                                        "error", "duplicate_invoice_number",
                                        "message", "Une autre facture du dossier utilise deja ce numero",
                                        "code", "duplicate_invoice_number"));
                    }

                    invoice.setFieldsData(currentFields);
                    invoice.setIsAvoir(resolveIsAvoir(currentFields, invoice.getRawOcrText()));

                    if (invoice.getStatus() == InvoiceStatus.TREATED
                            || invoice.getStatus() == InvoiceStatus.RECALCULATED
                            || invoice.getStatus() == InvoiceStatus.OUT_OF_PERIOD) {
                        invoice.setStatus(InvoiceStatus.READY_TO_VALIDATE);
                    }

                    DynamicInvoice saved = dynamicInvoiceDao.save(invoice);
                    return ResponseEntity.ok(toResponse(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/account")
    @Transactional
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> account(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    if (Boolean.TRUE.equals(invoice.getAccounted())) {
                        return ResponseEntity.ok(Map.of(
                                "message", "Facture déjà comptabilisée",
                                "invoice", toDetailedResponse(invoice)));
                    }
                    if (invoice.getStatus() != InvoiceStatus.VALIDATED
                            && invoice.getStatus() != InvoiceStatus.READY_TO_VALIDATE) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "invoice_not_validated",
                                "currentStatus", invoice.getStatus().name()));
                    }

                    String invoiceNumber = invoice.getInvoiceNumber();
                    if (invoiceNumber != null
                            && !invoiceNumber.isBlank()
                            && dynamicInvoiceDao.countOtherInvoicesByDossierIdAndInvoiceNumber(
                                    resolvedDossierId,
                                    invoiceNumber,
                                    invoice.getId()) > 0) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                                "error", "duplicate_invoice_number",
                                "message", "Comptabilisation impossible: facture deja existe avec meme numero"));
                    }

                    invoice.setAccounted(true);
                    invoice.setAccountedAt(java.time.LocalDateTime.now());
                    invoice.setAccountedBy(sessionUser != null ? sessionUser.username() : "system");
                    DynamicInvoice saved = dynamicInvoiceDao.save(invoice);

                    return ResponseEntity.ok(Map.of(
                            "message", "Facture comptabilisée",
                            "invoice", toDetailedResponse(saved)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/validate")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> validate(
            @PathVariable Long id,
            @RequestParam(required = false) String userId,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        String validator = sessionUser != null ? sessionUser.username() : (userId != null ? userId : "system");
        log.info("Validation facture {} par {}", id, validator);

        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    if (invoice.getStatus() == InvoiceStatus.VALIDATED) {
                        return ResponseEntity.ok(Map.of(
                                "message", "Facture deja validee",
                                "invoice", toResponse(invoice)));
                    }

                    if (!invoice.canBeValidated()) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Facture non prete pour validation",
                                "currentStatus", invoice.getStatus().name(),
                                "requiredStatus", "READY_TO_VALIDATE"));
                    }

                    invoice.validate(validator);
                    DynamicInvoice saved = dynamicInvoiceDao.save(invoice);

                    return ResponseEntity.ok(Map.of(
                            "message", "Facture validee",
                            "invoice", toResponse(saved)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/client-validate")
    @Transactional
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> clientValidate(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    if (Boolean.TRUE.equals(invoice.getClientValidated())) {
                        return ResponseEntity.ok(Map.of(
                                "message", "Facture deja validee par le client",
                                "invoice", toResponse(invoice)));
                    }

                    invoice.setClientValidated(true);
                    invoice.setClientValidatedAt(java.time.LocalDateTime.now());
                    invoice.setClientValidatedBy(sessionUser != null ? sessionUser.username() : "client");
                    DynamicInvoice saved = dynamicInvoiceDao.save(invoice);

                    return ResponseEntity.ok(Map.of(
                            "message", "Facture validee par le client",
                            "invoice", toResponse(saved)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== BULK ACTIONS ====================

    @PostMapping("/bulk/process")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> bulkProcess(
            @RequestBody Map<String, Object> body,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        dossierId = resolveDossierId(sessionUser, dossierId, session);
        if (dossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        List<Long> ids = parseIds(body.get("ids"));
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids_required"));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;

        for (Long id : ids) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            try {
                Optional<DynamicInvoice> invoiceOpt = dynamicInvoiceDao.findById(id);
                if (invoiceOpt.isEmpty()) {
                    item.put("status", "error");
                    item.put("error", "not_found");
                    results.add(item);
                    continue;
                }
                DynamicInvoice invoice = invoiceOpt.get();
                if (!canAccessInvoiceInDossier(sessionUser, invoice, dossierId)) {
                    item.put("status", "error");
                    item.put("error", "forbidden");
                    results.add(item);
                    continue;
                }
                DynamicInvoice processed = processingService.reprocessExistingInvoice(invoice);
                item.put("status", "success");
                item.put("invoice", toResponse(processed));
                results.add(item);
                successCount++;
            } catch (Exception e) {
                log.error("Erreur bulk process ({}): {}", id, e.getMessage(), e);
                item.put("status", "error");
                item.put("error", e.getMessage());
                results.add(item);
            }
        }

        int errorCount = results.size() - successCount;
        return ResponseEntity.ok(Map.of(
                "count", results.size(),
                "successCount", successCount,
                "errorCount", errorCount,
                "results", results
        ));
    }

    @PostMapping("/bulk/validate")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> bulkValidate(
            @RequestBody Map<String, Object> body,
            @RequestParam(required = false) String userId,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        dossierId = resolveDossierId(sessionUser, dossierId, session);
        if (dossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        List<Long> ids = parseIds(body.get("ids"));
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids_required"));
        }

        String validator = sessionUser.username() != null
                ? sessionUser.username()
                : (userId != null ? userId : "system");

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;

        for (Long id : ids) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            try {
                Optional<DynamicInvoice> invoiceOpt = dynamicInvoiceDao.findById(id);
                if (invoiceOpt.isEmpty()) {
                    item.put("status", "error");
                    item.put("error", "not_found");
                    results.add(item);
                    continue;
                }
                DynamicInvoice invoice = invoiceOpt.get();
                if (!canAccessInvoiceInDossier(sessionUser, invoice, dossierId)) {
                    item.put("status", "error");
                    item.put("error", "forbidden");
                    results.add(item);
                    continue;
                }
                if (invoice.getStatus() == InvoiceStatus.VALIDATED) {
                    item.put("status", "skipped");
                    item.put("message", "already_validated");
                    results.add(item);
                    continue;
                }
                if (!invoice.canBeValidated()) {
                    item.put("status", "error");
                    item.put("error", "not_ready");
                    results.add(item);
                    continue;
                }
                invoice.validate(validator);
                DynamicInvoice saved = dynamicInvoiceDao.save(invoice);
                item.put("status", "success");
                item.put("invoice", toResponse(saved));
                results.add(item);
                successCount++;
            } catch (Exception e) {
                log.error("Erreur bulk validate ({}): {}", id, e.getMessage(), e);
                item.put("status", "error");
                item.put("error", e.getMessage());
                results.add(item);
            }
        }

        int errorCount = results.size() - successCount;
        return ResponseEntity.ok(Map.of(
                "count", results.size(),
                "successCount", successCount,
                "errorCount", errorCount,
                "results", results
        ));
    }

    @PostMapping("/bulk/delete")
    @Transactional
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> bulkDelete(
            @RequestBody Map<String, Object> body,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        dossierId = resolveDossierId(sessionUser, dossierId, session);
        if (dossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        List<Long> ids = parseIds(body.get("ids"));
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids_required"));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;

        for (Long id : ids) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            try {
                Optional<DynamicInvoice> invoiceOpt = dynamicInvoiceDao.findById(id);
                if (invoiceOpt.isEmpty()) {
                    item.put("status", "error");
                    item.put("error", "not_found");
                    results.add(item);
                    continue;
                }
                DynamicInvoice invoice = invoiceOpt.get();
                if (!canAccessInvoiceInDossier(sessionUser, invoice, dossierId)) {
                    item.put("status", "error");
                    item.put("error", "forbidden");
                    results.add(item);
                    continue;
                }
                String filePath = invoice.getFilePath();
                if (filePath == null || filePath.isBlank()) {
                    item.put("status", "error");
                    item.put("error", "file_missing");
                    results.add(item);
                    continue;
                }

                dynamicInvoiceDao.delete(invoice);

                try {
                    Files.deleteIfExists(Path.of(filePath));
                } catch (Exception e) {
                    log.warn("Facture {} supprimee en base, mais fichier non supprime: {} ({})",
                            id, filePath, e.getMessage());
                }

                item.put("status", "success");
                results.add(item);
                successCount++;
            } catch (Exception e) {
                log.error("Erreur bulk delete ({}): {}", id, e.getMessage(), e);
                item.put("status", "error");
                item.put("error", e.getMessage());
                results.add(item);
            }
        }

        int errorCount = results.size() - successCount;
        return ResponseEntity.ok(Map.of(
                "count", results.size(),
                "successCount", successCount,
                "errorCount", errorCount,
                "results", results
        ));
    }

    // ==================== NOUVEAU: GET SIGNATURES DISPONIBLES ====================

    /**
     * Récupère les signatures disponibles pour créer un template
     * GET /api/dynamic-invoices/{id}/available-signatures
     */
    @GetMapping("/{id}/available-signatures")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> getAvailableSignatures(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        log.info("GET /api/dynamic-invoices/{}/available-signatures", id);

        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        return dynamicInvoiceDao.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    List<Map<String, Object>> signatures = new ArrayList<>();

                    Map<String, Object> fieldsData = invoice.getFieldsData();
                    if (fieldsData == null) {
                        return ResponseEntity.ok(Map.of(
                                "message", "Aucune donnée extraite",
                                "signatures", signatures));
                    }

                    String ifNumber = getStringValue(fieldsData, "ifNumber");
                    String ice = getStringValue(fieldsData, "ice");
                    String rcNumber = getStringValue(fieldsData, "rcNumber");
                    String supplier = getStringValue(fieldsData, "supplier");

                    // Signature IF (prioritaire)
                    if (ifNumber != null && !ifNumber.isBlank()) {
                        signatures.add(Map.of(
                                "type", "IF",
                                "value", ifNumber,
                                "label", "IF: " + ifNumber,
                                "recommended", true,
                                "reason", "Identifiant unique du fournisseur (recommandé)"));
                    }

                    // Signature ICE
                    if (ice != null && !ice.isBlank()) {
                        signatures.add(Map.of(
                                "type", "ICE",
                                "value", ice,
                                "label", "ICE: " + ice,
                                "recommended", false,
                                "reason", "Peut apparaître plusieurs fois (client + fournisseur)"));
                    }

                    // Signature RC
                    if (rcNumber != null && !rcNumber.isBlank()) {
                        signatures.add(Map.of(
                                "type", "RC",
                                "value", rcNumber,
                                "label", "RC: " + rcNumber,
                                "recommended", false,
                                "reason", "Moins standardisé"));
                    }

                    if (signatures.isEmpty()) {
                        return ResponseEntity.ok(Map.of(
                                "message", "Aucune signature détectée",
                                "suggestion", "Vérifiez que l'OCR a extrait les données correctement",
                                "signatures", signatures));
                    }

                    return ResponseEntity.ok(Map.of(
                            "invoiceId", id,
                            "supplier", supplier != null ? supplier : "Non détecté",
                            "signatures", signatures,
                            "recommendedSignature", signatures.get(0)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> stats(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        Map<String, Object> stats = new LinkedHashMap<>();

        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        dossierId = resolveDossierId(sessionUser, dossierId, session);
        if (dossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        if (requireDossierForUser(sessionUser, dossierId) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "dossier_forbidden"));
        }

        boolean filterByClientValidated = !sessionUser.isClient();

        if (filterByClientValidated) {
            stats.put("total", dynamicInvoiceDao.countByDossierIdAndClientValidatedTrue(dossierId));
            stats.put("pending",
                    dynamicInvoiceDao.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.PENDING, dossierId));
            stats.put("processing",
                    dynamicInvoiceDao.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.PROCESSING, dossierId));
            stats.put("treated",
                    dynamicInvoiceDao.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.TREATED, dossierId));
            stats.put("readyToValidate",
                    dynamicInvoiceDao.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.READY_TO_VALIDATE, dossierId));
            stats.put("validated",
                    dynamicInvoiceDao.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.VALIDATED, dossierId));
            stats.put("error",
                    dynamicInvoiceDao.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.ERROR, dossierId));
        } else {
            stats.put("total", dynamicInvoiceDao.countByDossierId(dossierId));
            stats.put("pending", dynamicInvoiceDao.countByStatusAndDossierId(InvoiceStatus.PENDING, dossierId));
            stats.put("processing", dynamicInvoiceDao.countByStatusAndDossierId(InvoiceStatus.PROCESSING, dossierId));
            stats.put("treated", dynamicInvoiceDao.countByStatusAndDossierId(InvoiceStatus.TREATED, dossierId));
            stats.put("readyToValidate",
                    dynamicInvoiceDao.countByStatusAndDossierId(InvoiceStatus.READY_TO_VALIDATE, dossierId));
            stats.put("validated", dynamicInvoiceDao.countByStatusAndDossierId(InvoiceStatus.VALIDATED, dossierId));
            stats.put("error", dynamicInvoiceDao.countByStatusAndDossierId(InvoiceStatus.ERROR, dossierId));
        }

        List<DynamicInvoice> lowConfidence = filterByClientValidated
                ? dynamicInvoiceDao.findLowConfidenceByDossierIdClientValidated(0.7, dossierId)
                : dynamicInvoiceDao.findLowConfidenceByDossierId(0.7, dossierId);
        stats.put("lowConfidenceCount", lowConfidence.size());

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/files/{filename}")
    @CrossOrigin("*")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> getFile(
            @PathVariable String filename,
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        log.debug("Requ?te de fichier re?ue pour: {}", filename);
        SessionUser sessionUser = authService.requireSessionUser(session);

        dossierId = resolveDossierId(sessionUser, dossierId, session);
        if (dossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (invoiceId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        Optional<DynamicInvoice> invoiceOpt = dynamicInvoiceDao.findById(invoiceId);
        if (invoiceOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        DynamicInvoice invoice = invoiceOpt.get();
        if (!canAccessInvoiceInDossier(sessionUser, invoice, dossierId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String filePath = invoice.getFilePath();
        if (filePath != null && !filePath.endsWith(filename)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            Path dir = fileStorageService.getBaseDirForFiles();
            log.debug("Recherche dans le r?pertoire de base: {}", dir);

            if (!Files.exists(dir)) {
                log.error("Le r?pertoire de base n'existe pas: {}", dir);
                return ResponseEntity.notFound().build();
            }

            // Chercher fichier se terminant par filename (car pr?fixe UUID ajout?)
            // Utilisation de try-with-resources pour fermer le stream
            try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
                Path foundPath = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(filename))
                        .findFirst()
                        .orElse(null);

                if (foundPath == null) {
                    log.warn("Fichier non trouv? apr?s recherche r?cursive: {}", filename);
                    return ResponseEntity.notFound().build();
                }

                log.debug("Fichier trouv?: {}", foundPath);
                Resource resource = new UrlResource(foundPath.toUri());

                if (!resource.exists() || !resource.isReadable()) {
                    log.error("Fichier trouv? mais non lisible: {}", foundPath);
                    return ResponseEntity.notFound().build();
                }

                String contentType = Files.probeContentType(foundPath);
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }

                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                        .body(resource);
            }
        } catch (Exception e) {
            log.error("Erreur lors de la r?cup?ration du fichier {}: {}", filename, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

@PostMapping("/{id}/link-tier")
    @Transactional
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> linkTier(
            @PathVariable Long id,
            @RequestBody Map<String, Long> request,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        Long tierId = request.get("tierId");

        log.info("=== LIAISON TIER À FACTURE ===");
        log.info("Invoice ID: {}, Tier ID: {}", id, tierId);

        try {
            // 1. Récupérer la facture
            DynamicInvoice invoice = dynamicInvoiceDao.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Facture non trouvée: " + id));

            SessionUser sessionUser = authService.requireSessionUser(session);

            dossierId = resolveDossierId(sessionUser, dossierId, session);
            if (dossierId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "dossier_required"));
            }
            if (!canAccessInvoiceInDossier(sessionUser, invoice, dossierId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "error", "forbidden"));
            }

            // 2. Récupérer le tier
            Optional<TierDto> tierDtoOpt = tierService.getTierById(tierId, dossierId);

            if (tierDtoOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "success", false,
                        "error", "Tier non trouvé: " + tierId));
            }

            TierDto tierDto = tierDtoOpt.get();

            log.info("Liaison: Facture '{}' → Tier '{}'",
                    invoice.getFilename(), tierDto.getLibelle());

            // 3. Convertir DTO en Entity
            Tier tier = convertDtoToEntity(tierDto);

            // 4. Lier le tier à la facture
            invoice.setTier(tier);
            invoice.setTierId(tier.getId());
            invoice.setTierName(tier.getLibelle());

            // MODIFICATION : Initialiser fieldsData ICI
            Map<String, Object> fieldsData = invoice.getFieldsData();
            if (fieldsData == null) {
                fieldsData = new LinkedHashMap<>();
            }

            List<String> autoFilledFields = new ArrayList<>();

            // NOUVEAU : Remplacer supplier par Tier.libelle
            fieldsData.put("supplier", tier.getLibelle());
            autoFilledFields.add("supplier");
            log.info("Supplier remplacé par Tier.libelle: {}", tier.getLibelle());

            // 5. Auto-remplir les comptes comptables si disponibles
            if (tier.hasAccountingConfiguration()) {
                fieldsData.put("tierNumber", tier.getTierNumber());
                autoFilledFields.add("tierNumber");

                if (tier.getAuxiliaireMode() != null && tier.getAuxiliaireMode()) {
                    fieldsData.put("collectifAccount", tier.getCollectifAccount());
                    autoFilledFields.add("collectifAccount");
                }

                fieldsData.put("chargeAccount", tier.getEffectiveChargeAccount());
                fieldsData.put("tvaAccount", tier.getEffectiveTvaAccount());
                fieldsData.put("tvaRate", tier.getEffectiveTvaRate());

                autoFilledFields.add("chargeAccount");
                autoFilledFields.add("tvaAccount");
                autoFilledFields.add("tvaRate");

                log.info("Comptes comptables auto-remplis:");
                log.info("  - tierNumber: {}", tier.getTierNumber());
                log.info("  - chargeAccount: {}", tier.getEffectiveChargeAccount());
                log.info("  - tvaAccount: {}", tier.getEffectiveTvaAccount());
            }

            invoice.setFieldsData(fieldsData);

            // Mettre à jour autoFilledFields
            List<String> currentAutoFilled = invoice.getAutoFilledFields();
            if (currentAutoFilled == null) {
                currentAutoFilled = new ArrayList<>();
            }
            currentAutoFilled.addAll(autoFilledFields);
            invoice.setAutoFilledFields(currentAutoFilled);

            // 6. Mettre à jour le status si maintenant complet
            if (invoice.getStatus() == InvoiceStatus.TREATED
                    || invoice.getStatus() == InvoiceStatus.RECALCULATED
                    || invoice.getStatus() == InvoiceStatus.OUT_OF_PERIOD) {
                invoice.setStatus(InvoiceStatus.READY_TO_VALIDATE);
                log.info("Status mis à jour: READY_TO_VALIDATE");
            }

            // 7. Sauvegarder
            DynamicInvoice saved = dynamicInvoiceDao.save(invoice);

            log.info("Tier lié avec succès");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Fournisseur lié avec succès",
                    "invoice", toDetailedResponse(saved)));

        } catch (IllegalArgumentException e) {
            log.error("Erreur validation: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            log.error("Erreur intégrité liaison tier facture {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "error", "Conflit de données lors de la sauvegarde"));
        } catch (IllegalStateException e) {
            log.warn("Erreur validation tier facture {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur inattendue: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "Erreur serveur: " + e.getMessage()));
        }
    }

    // ===================== HELPERS =====================

    private DynamicInvoice createUploadOnlyInvoice(MultipartFile file) {
        String filePath = fileStorageService.store(file);
        DynamicInvoice invoice = new DynamicInvoice();

        String originalName = file.getOriginalFilename();
        String safeName = originalName != null ? originalName : "upload";

        invoice.setFilename(safeName);
        invoice.setOriginalName(safeName);
        invoice.setFilePath(filePath);
        invoice.setFileSize(file.getSize());
        invoice.setRawOcrText("");
        invoice.setExtractedData(new LinkedHashMap<>());
        invoice.setFieldsData(new LinkedHashMap<>());
        invoice.setMissingFields(new ArrayList<>());
        invoice.setLowConfidenceFields(new ArrayList<>());
        invoice.setAutoFilledFields(new ArrayList<>());
        invoice.setStatus(InvoiceStatus.PENDING);
        return invoice;
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private ExtractionEngine resolveExtractionEngine(String engine, String ocrMode, Boolean useAlphaAgent) {
        return ExtractionEngine.resolve(engine, ocrMode, useAlphaAgent);
    }

    private boolean isDuplicateFilename(Long dossierId, String filename) {
        if (dossierId == null) return false;
        String normalized = normalizeKey(filename);
        if (normalized.isBlank()) return false;
        return dynamicInvoiceDao.existsDuplicateFilenameInSameDossier(dossierId, normalized);
    }

    private boolean hasAnotherInvoiceWithSameNumber(Long dossierId, String invoiceNumber, Long invoiceId) {
        if (dossierId == null) return false;
        String normalized = normalizeKey(invoiceNumber);
        if (normalized.isBlank()) return false;
        return dynamicInvoiceDao.countOtherInvoicesByDossierIdAndInvoiceNumber(dossierId, normalized, invoiceId) > 0;
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

    private Long resolveDossierId(SessionUser sessionUser, Long dossierId, HttpSession session) {
        if (dossierId != null) {
            return dossierId;
        }
        if (sessionUser == null || session == null) {
            return null;
        }
        Object rawId = session.getAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
        if (rawId == null) {
            if (sessionUser.isClient()) {
                Dossier fallback = dossierDao.findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(sessionUser.id())
                        .orElse(null);
                if (fallback != null) {
                    session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, fallback.getId());
                    return fallback.getId();
                }
            }
            return null;
        }
        Long resolvedId;
        try {
            resolvedId = Long.valueOf(rawId.toString());
        } catch (NumberFormatException ex) {
            session.removeAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
            if (sessionUser.isClient()) {
                Dossier fallback = dossierDao.findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(sessionUser.id())
                        .orElse(null);
                if (fallback != null) {
                    session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, fallback.getId());
                    return fallback.getId();
                }
            }
            return null;
        }
        if (requireDossierForUser(sessionUser, resolvedId) == null) {
            session.removeAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
            if (sessionUser.isClient()) {
                Dossier fallback = dossierDao.findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(sessionUser.id())
                        .orElse(null);
                if (fallback != null) {
                    session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, fallback.getId());
                    return fallback.getId();
                }
            }
            return null;
        }
        return resolvedId;
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

    private boolean canAccessInvoiceInDossier(SessionUser sessionUser, DynamicInvoice invoice, Long dossierId) {
        if (sessionUser == null || invoice == null || dossierId == null) {
            return false;
        }
        Long invoiceDossierId = invoice.getDossierId();
        if (invoiceDossierId == null || !dossierId.equals(invoiceDossierId)) {
            return false;
        }
        return canAccessInvoice(sessionUser, invoice);
    }

    private boolean canAccessInvoice(SessionUser sessionUser, DynamicInvoice invoice) {
        if (sessionUser == null || invoice == null) {
            return false;
        }
        if (sessionUser.isAdmin()) {
            return true;
        }
        Long dossierId = invoice.getDossierId();
        if (dossierId == null) {
            return false;
        }
        if (sessionUser.isComptable()) {
            return dossierDao.findByIdAndComptableId(dossierId, sessionUser.id()).isPresent();
        }
        if (sessionUser.isClient()) {
            return dossierDao.findByIdAndClientId(dossierId, sessionUser.id()).isPresent();
        }
        return false;
    }

    private boolean isValidFileType(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private List<Long> parseIds(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item == null) {
                    continue;
                }
                try {
                    ids.add(Long.valueOf(item.toString()));
                } catch (NumberFormatException ignored) {
                }
            }
            return ids;
        }
        if (raw instanceof String str) {
            String[] parts = str.split(",");
            for (String part : parts) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    ids.add(Long.valueOf(part.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return ids;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean resolveIsAvoir(Map<String, Object> fieldsData, String rawOcrText) {
        Boolean manualOverride = parseBooleanField(fieldsData, "isAvoirOverride");
        if (manualOverride != null) {
            return manualOverride;
        }
        return InvoiceTypeDetector.isAvoir(fieldsData, rawOcrText);
    }

    private void syncAmountTtcEnLettres(Map<String, Object> fieldsData) {
        if (fieldsData == null) {
            return;
        }
        Object amountValue = fieldsData.get("amountTTC");
        if (amountValue == null) {
            amountValue = fieldsData.get("totalTtc");
        }
        if (amountValue == null) {
            amountValue = fieldsData.get("amountTTc");
        }
        String amountWords = AmountToWordsFormatter.formatTtcInWords(amountValue);
        if (!amountWords.isBlank()) {
            fieldsData.put("amountTTCEnLettres", amountWords);
        } else {
            fieldsData.remove("amountTTCEnLettres");
        }
    }

    private Boolean parseBooleanField(Map<String, Object> fieldsData, String key) {
        if (fieldsData == null || key == null || key.isBlank()) {
            return null;
        }
        Object raw = fieldsData.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof Number n) {
            return n.intValue() != 0;
        }
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return null;
        }
        if (value.equals("true") || value.equals("1") || value.equals("yes") || value.equals("oui")) {
            return true;
        }
        if (value.equals("false") || value.equals("0") || value.equals("no") || value.equals("non")) {
            return false;
        }
        return null;
    }

    private Map<String, Object> toResponse(DynamicInvoice invoice) {
        Map<String, Object> fieldsData = invoice.getFieldsData() != null
                ? invoice.getFieldsData()
                : new LinkedHashMap<>();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", invoice.getId());
        response.put("filename", invoice.getFilename());
        response.put("originalName", invoice.getOriginalName());
        response.put("status", invoice.getStatus().name());
        response.put("templateId", invoice.getTemplateId());
        response.put("templateName", invoice.getTemplateName());
        response.put("overallConfidence", invoice.getOverallConfidence());
        response.put("cleanedOcrText", invoice.getCleanedOcrText());
        response.put("scanned", Boolean.TRUE.equals(invoice.getScanned()));
        response.put("documentType", invoice.getDocumentType() != null ? invoice.getDocumentType().name() : null);
        response.put("amountsValid", invoice.getAmountsValid());
        response.put("validationMessage", invoice.getValidationMessage());
        response.put("qualityScore", invoice.getExtractedData() != null ? invoice.getExtractedData().get("qualityScore") : null);
        response.put("difficultyClass", invoice.getExtractedData() != null ? invoice.getExtractedData().get("difficultyClass") : null);
        response.put("qualityFlags", invoice.getExtractedData() != null ? invoice.getExtractedData().get("qualityFlags") : List.of());
        response.put("reviewRequired", invoice.getExtractedData() != null ? invoice.getExtractedData().get("reviewRequired") : null);
        response.put("reviewReasons", invoice.getExtractedData() != null ? invoice.getExtractedData().get("reviewReasons") : List.of());
        response.put("fieldConfidences", invoice.getExtractedData() != null ? invoice.getExtractedData().get("fieldConfidences") : Map.of());
        response.put("fieldSources", invoice.getExtractedData() != null ? invoice.getExtractedData().get("fieldSources") : Map.of());
        response.put("olmocrUsed", invoice.getExtractedData() != null ? invoice.getExtractedData().get("olmocrUsed") : null);
        response.put("olmocrDurationMs", invoice.getExtractedData() != null ? invoice.getExtractedData().get("olmocrDurationMs") : null);
        response.put("olmocrMode", invoice.getExtractedData() != null ? invoice.getExtractedData().get("olmocrMode") : null);
        response.put("dossierId", invoice.getDossierId());
        response.put("clientValidated", Boolean.TRUE.equals(invoice.getClientValidated()));
        response.put("clientValidatedAt", invoice.getClientValidatedAt());
        response.put("clientValidatedBy", invoice.getClientValidatedBy());
        response.put("accounted", Boolean.TRUE.equals(invoice.getAccounted()));
        response.put("accountedAt", invoice.getAccountedAt());
        response.put("accountedBy", invoice.getAccountedBy());
        response.put("isAvoir", Boolean.TRUE.equals(invoice.getIsAvoir()));
        response.put("fieldsData", fieldsData);
        response.put("extractionMethod", getStringValue(fieldsData, "extractionMethod"));
        response.put("invoiceDate", firstNonBlank(
                getStringValue(fieldsData, "invoiceDate"),
                getStringValue(fieldsData, "dateFacture"),
                getStringValue(fieldsData, "date")
        ));
        response.put("missingFields", invoice.getMissingFields());
        response.put("lowConfidenceFields", invoice.getLowConfidenceFields());
        response.put("autoFilledFields", invoice.getAutoFilledFields() != null
                ? invoice.getAutoFilledFields()
                : List.of());
        response.put("rawOcrText", invoice.getRawOcrText());
        response.put("extractedText", invoice.getCleanedOcrText() != null
                ? invoice.getCleanedOcrText()
                : invoice.getRawOcrText());
        response.put("cleanedOcrText", invoice.getCleanedOcrText());
        response.put("createdAt", invoice.getCreatedAt());
        response.put("updatedAt", invoice.getUpdatedAt());
        return response;
    }

    private Map<String, Object> toDetailedResponse(DynamicInvoice invoice) {
        Map<String, Object> response = toResponse(invoice);
        response.put("extractedData", invoice.getExtractedData());
        response.put("rawOcrText", invoice.getRawOcrText());
        response.put("cleanedOcrText", invoice.getCleanedOcrText());
        response.put("filePath", invoice.getFilePath());
        response.put("fileSize", invoice.getFileSize());
        response.put("clientValidated", Boolean.TRUE.equals(invoice.getClientValidated()));
        response.put("clientValidatedAt", invoice.getClientValidatedAt());
        response.put("clientValidatedBy", invoice.getClientValidatedBy());
        response.put("validatedAt", invoice.getValidatedAt());
        response.put("validatedBy", invoice.getValidatedBy());
        response.put("accounted", Boolean.TRUE.equals(invoice.getAccounted()));
        response.put("accountedAt", invoice.getAccountedAt());
        response.put("accountedBy", invoice.getAccountedBy());
        response.put("isAvoir", Boolean.TRUE.equals(invoice.getIsAvoir()));

        if (invoice.getDetectedSignature() != null) {
            response.put("detectedSignature", Map.of(
                    "type", invoice.getDetectedSignature().getSignatureType().name(),
                    "value", invoice.getDetectedSignature().getSignatureValue()));
        }

        Long tierId = invoice.getTierId();
        if (tierId != null) {
            Optional<TierDto> tierDtoOpt = tierService.getTierById(tierId, invoice.getDossierId());
            if (tierDtoOpt.isPresent()) {
                TierDto tier = tierDtoOpt.get();
                Map<String, Object> fieldsData = invoice.getFieldsData() != null
                        ? invoice.getFieldsData()
                        : new LinkedHashMap<>();

                Map<String, Object> tierData = new LinkedHashMap<>();
                tierData.put("id", tier.getId());
                tierData.put("libelle", tier.getLibelle());
                tierData.put("auxiliaireMode", tier.getAuxiliaireMode());
                tierData.put("tierNumber", tier.getTierNumber() != null ? tier.getTierNumber() : "");
                tierData.put("collectifAccount", tier.getCollectifAccount() != null ? tier.getCollectifAccount() : "");
                tierData.put("displayAccount", tier.getDisplayAccount());
                tierData.put("ifNumber", tier.getIfNumber() != null ? tier.getIfNumber() : "");
                tierData.put("ice", tier.getIce() != null ? tier.getIce() : "");
                tierData.put("rcNumber", tier.getRcNumber() != null ? tier.getRcNumber() : "");
                tierData.put("defaultChargeAccount",
                        tier.getDefaultChargeAccount() != null ? tier.getDefaultChargeAccount() : "");
                tierData.put("defaultChargeAccount2",
                        tier.getDefaultChargeAccount2() != null ? tier.getDefaultChargeAccount2() : "");
                tierData.put("tvaAccount", tier.getTvaAccount() != null ? tier.getTvaAccount() : "");
                tierData.put("tvaAccount2", tier.getTvaAccount2() != null ? tier.getTvaAccount2() : "");
                tierData.put("defaultTvaRate", tier.getDefaultTvaRate() != null ? tier.getDefaultTvaRate() : 0.0);
                tierData.put("defaultTvaRate2", tier.getDefaultTvaRate2() != null ? tier.getDefaultTvaRate2() : 0.0);
                tierData.put("active", tier.getActive());

                boolean hasAccountingConfig = tier.hasAccountingConfiguration();
                tierData.put("hasAccountingConfig", hasAccountingConfig);

                response.put("tier", tierData);
                if (hasAccountingConfig) {
                    fieldsData.put("tierNumber", tier.getTierNumber());
                    if (tier.getAuxiliaireMode() != null && tier.getAuxiliaireMode()) {
                        fieldsData.put("collectifAccount", tier.getCollectifAccount());
                    }
                    fieldsData.put("chargeAccount", tier.getEffectiveChargeAccount());
                    fieldsData.put("tvaAccount", tier.getEffectiveTvaAccount());
                    fieldsData.put("tvaRate", tier.getEffectiveTvaRate());
                }
                response.put("fieldsData", fieldsData);
            } else {
                response.put("tier", Map.of(
                        "id", tierId,
                        "libelle", invoice.getTierName() != null ? invoice.getTierName() : "",
                        "active", false,
                        "hasAccountingConfig", false));
            }
        } else {
            response.put("tier", null);
        }

        return response;
    }

    private Tier convertDtoToEntity(com.invoice_reader.invoice_reader.dto.account_tier.TierDto dto) {
        return Tier.builder()
                .id(dto.getId())
                .auxiliaireMode(dto.getAuxiliaireMode())
                .tierNumber(dto.getTierNumber())
                .collectifAccount(dto.getCollectifAccount())
                .libelle(dto.getLibelle())
                .activity(dto.getActivity())
                .ifNumber(dto.getIfNumber())
                .ice(dto.getIce())
                .rcNumber(dto.getRcNumber())
                .defaultChargeAccount(dto.getDefaultChargeAccount())
                .defaultChargeAccount2(dto.getDefaultChargeAccount2())
                .tvaAccount(dto.getTvaAccount())
                .tvaAccount2(dto.getTvaAccount2())
                .defaultTvaRate(dto.getDefaultTvaRate())
                .defaultTvaRate2(dto.getDefaultTvaRate2())
                .active(dto.getActive())
                .build();
    }
}
