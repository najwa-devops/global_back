package com.invoice_reader.invoice_reader.sales.controller;

import com.invoice_reader.invoice_reader.dto.account_tier.TierDto;
import com.invoice_reader.invoice_reader.entity.auth.Dossier;
import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.entity.account_tier.Tier;
import com.invoice_reader.invoice_reader.entity.invoice.InvoiceStatus;
import com.invoice_reader.invoice_reader.repository.DossierDao;
import com.invoice_reader.invoice_reader.repository.DossierGeneralParamsDao;
import com.invoice_reader.invoice_reader.sales.entity.SalesInvoice;
import com.invoice_reader.invoice_reader.sales.repository.SalesInvoiceRepository;
import com.invoice_reader.invoice_reader.sales.service.SalesInvoiceProcessingService;
import com.invoice_reader.invoice_reader.servises.dynamic.ExtractionEngine;
import com.invoice_reader.invoice_reader.servises.FileStorageService;
import com.invoice_reader.invoice_reader.utils.ExercisePeriodException;
import com.invoice_reader.invoice_reader.utils.InvoiceTypeDetector;
import com.invoice_reader.invoice_reader.security.RequireRole;
import com.invoice_reader.invoice_reader.servises.account_tier.TierService;
import com.invoice_reader.invoice_reader.servises.auth.AuthService;
import com.invoice_reader.invoice_reader.servises.auth.SessionKeys;
import com.invoice_reader.invoice_reader.servises.auth.SessionUser;
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
import com.invoice_reader.invoice_reader.utils.AmountToWordsFormatter;

@RestController
@RequestMapping("/api/sales/invoices")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class SalesInvoiceController {

    private final SalesInvoiceRepository salesInvoiceRepository;
    private final SalesInvoiceProcessingService processingService;
    private final FileStorageService fileStorageService;
    private final TierService tierService;
    private final AuthService authService;
    private final DossierDao dossierDao;
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
        log.info("Upload facture vente: {}", file.getOriginalFilename());

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
            if (isDuplicateFilename(resolvedDossierId, originalName)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Cette facture est doublon par nom"));
            }

            SalesInvoice processed;
            if (sessionUser.isClient()) {
                processed = createUploadOnlyInvoice(file);
            } else {
                processed = processingService.processInvoice(
                        file,
                        resolvedDossierId,
                        ExtractionEngine.resolve(engine, ocrMode, useAlphaAgent));
                if (sessionUser.isAdmin()) {
                    processed.setClientValidated(true);
                    processed.setClientValidatedAt(java.time.LocalDateTime.now());
                    processed.setClientValidatedBy(sessionUser.username());
                }
            }

            if (dossier != null) {
                processed.setDossier(dossier);
                processed.setDossierId(dossier.getId());
                processed = salesInvoiceRepository.save(processed);
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
            log.error("Erreur upload facture vente: {}", e.getMessage(), e);
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
        log.info("Upload batch vente: {} fichiers", count);

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
                if (!normalizedName.isBlank() && !batchNames.add(normalizedName)) {
                    item.put("status", "error");
                    item.put("error", "Cette facture est doublon par nom");
                    results.add(item);
                    continue;
                }
                if (isDuplicateFilename(resolvedDossierId, originalName)) {
                    item.put("status", "error");
                    item.put("error", "Cette facture est doublon par nom");
                    results.add(item);
                    continue;
                }

                SalesInvoice processed = sessionUser.isClient()
                        ? createUploadOnlyInvoice(file)
                        : processingService.processInvoice(
                                file,
                                resolvedDossierId,
                                ExtractionEngine.resolve(engine, ocrMode, useAlphaAgent));
                if (!sessionUser.isClient() && sessionUser.isAdmin()) {
                    processed.setClientValidated(true);
                    processed.setClientValidatedAt(java.time.LocalDateTime.now());
                    processed.setClientValidatedBy(sessionUser.username());
                }

                if (dossier != null) {
                    processed.setDossier(dossier);
                    processed.setDossierId(dossier.getId());
                    processed = salesInvoiceRepository.save(processed);
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
                log.error("Erreur upload batch vente ({}): {}", originalName, e.getMessage(), e);
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

    @PostMapping({"/{id}/process", "/{id}/reprocess"})
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> reprocess(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        log.info("Retraitement facture vente: {}", id);
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        return salesInvoiceRepository.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    try {
                        SalesInvoice processed = processingService.reprocessExistingInvoice(invoice);
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
                        log.error("Erreur retraitement vente {}: {}", id, e.getMessage(), e);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(Map.of("error", e.getMessage()));
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
        return salesInvoiceRepository.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    return ResponseEntity.ok(toDetailedResponse(invoice));
                })
                .orElse(ResponseEntity.notFound().build());
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

        List<SalesInvoice> invoices;
        if (status != null) {
            InvoiceStatus invoiceStatus = InvoiceStatus.valueOf(status.toUpperCase());
            invoices = salesInvoiceRepository.findByStatusAndDossierIdOrderByCreatedAtDesc(invoiceStatus, dossierId);
        } else if (templateId != null) {
            invoices = salesInvoiceRepository.findByTemplateIdAndDossierIdOrderByCreatedAtDesc(templateId, dossierId);
        } else {
            invoices = salesInvoiceRepository.findByDossierIdOrderByCreatedAtDesc(dossierId);
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

    @GetMapping("/pending")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> listPending(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(defaultValue = "200") int limit,
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

        List<Map<String, Object>> response = salesInvoiceRepository.findByDossierIdOrderByCreatedAtDesc(dossierId).stream()
                .filter(this::isPendingWorkflowInvoice)
                .filter(invoice -> sessionUser.isClient() || Boolean.TRUE.equals(invoice.getClientValidated()))
                .limit(limit)
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(Map.of(
                "count", response.size(),
                "invoices", response));
    }

    @GetMapping("/scanned")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> listScanned(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(defaultValue = "200") int limit,
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

        List<Map<String, Object>> response = salesInvoiceRepository.findByDossierIdOrderByCreatedAtDesc(dossierId).stream()
                .filter(invoice -> sessionUser.isClient() || Boolean.TRUE.equals(invoice.getClientValidated()))
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
        log.info("Suppression facture vente: {}", id);
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        return salesInvoiceRepository.findById(id)
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
                    if (Boolean.TRUE.equals(invoice.getClientValidated())
                            && !isValidatedDocumentDeletionAllowed(resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "validated_document_deletion_disabled"));
                    }
                    String filePath = invoice.getFilePath();
                    if (filePath == null || filePath.isBlank()) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                    }

                    salesInvoiceRepository.delete(invoice);

                    try {
                        Files.deleteIfExists(Path.of(filePath));
                    } catch (Exception e) {
                        log.warn("Facture vente {} supprimée en base, mais fichier non supprimé: {} ({})",
                                id, filePath, e.getMessage());
                    }

                    return ResponseEntity.noContent().build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping({"/{id}", "/{id}/fields"})
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
        log.info("Modification champs facture vente {}: {}", id, fields.keySet());
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }

        return salesInvoiceRepository.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
                    }
                    if (!invoice.isModifiable()) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "Facture validée, modification impossible"));
                    }

                    Map<String, Object> currentFields = invoice.getFieldsData();
                    if (currentFields == null) {
                        currentFields = new LinkedHashMap<>();
                    }
                    currentFields.putAll(fields);
                    try {
                        syncAmountTtcEnLettres(currentFields);
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "invalid_fields",
                                "message", e.getMessage()));
                    }
                    invoice.setFieldsData(currentFields);
                    invoice.setIsAvoir(resolveIsAvoir(currentFields, invoice.getRawOcrText()));

                    if (invoice.getStatus() == InvoiceStatus.TREATED
                            || invoice.getStatus() == InvoiceStatus.RECALCULATED
                            || invoice.getStatus() == InvoiceStatus.OUT_OF_PERIOD) {
                        invoice.setStatus(InvoiceStatus.READY_TO_VALIDATE);
                    }

                    SalesInvoice saved = salesInvoiceRepository.save(invoice);
                    return ResponseEntity.ok(toResponse(saved));
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
        log.info("Validation facture vente {} par {}", id, validator);

        return salesInvoiceRepository.findById(id)
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
                    SalesInvoice saved = salesInvoiceRepository.save(invoice);

                    return ResponseEntity.ok(Map.of(
                            "message", "Facture validee",
                            "invoice", toResponse(saved)));
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
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }

        return salesInvoiceRepository.findById(id)
                .map(invoice -> {
                    if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "forbidden"));
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
                            && salesInvoiceRepository.countOtherInvoicesByDossierIdAndInvoiceNumber(
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
                    SalesInvoice saved = salesInvoiceRepository.save(invoice);

                    return ResponseEntity.ok(Map.of(
                            "message", "Facture comptabilisee",
                            "invoice", toResponse(saved)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/journal")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> salesJournal(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
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

        List<SalesInvoice> invoices = salesInvoiceRepository.findByDossierIdOrderByCreatedAtDesc(dossierId);
        List<Map<String, Object>> entries = new ArrayList<>();

        for (SalesInvoice invoice : invoices) {
            if (!Boolean.TRUE.equals(invoice.getAccounted())) {
                continue;
            }

            Map<String, Object> fields = invoice.getFieldsData() != null
                    ? invoice.getFieldsData()
                    : Collections.emptyMap();
            double amountHt = parseAmount(fields.get("amountHT"));
            double amountTva = parseAmount(fields.get("tva"));
            double amountTtc = parseAmount(fields.get("amountTTC"));
            if (amountTtc == 0.0 && amountHt > 0.0) {
                amountTtc = amountHt + amountTva;
            }

            String invoiceNumber = firstNonBlank(
                    getStringValue(fields, "invoiceNumber"),
                    invoice.getInvoiceNumber(),
                    "N/A"
            );
            String supplier = firstNonBlank(
                    getStringValue(fields, "supplier"),
                    invoice.getTierName(),
                    "N/A"
            );
            String chargeAccount = firstNonBlank(getStringValue(fields, "chargeAccount"), "711100");
            String tvaAccount = firstNonBlank(getStringValue(fields, "tvaAccount"), "445500");
            String clientAccount = firstNonBlank(getStringValue(fields, "tierNumber"), "342100");
            String journal = firstNonBlank(getStringValue(fields, "salesJournal"), "VENTE");
            Object entryDate = firstNonBlank(
                    getStringValue(fields, "invoiceDate"),
                    invoice.getCreatedAt() != null ? invoice.getCreatedAt().toLocalDate().toString() : null
            );
            boolean isAvoir = Boolean.TRUE.equals(invoice.getIsAvoir());

            entries.add(buildJournalEntry(invoice, invoiceNumber, supplier, journal, chargeAccount, entryDate,
                    isAvoir ? 0.0 : amountHt, isAvoir ? amountHt : 0.0, "Produit HT"));
            if (amountTva > 0.0) {
                entries.add(buildJournalEntry(invoice, invoiceNumber, supplier, journal, tvaAccount, entryDate,
                        isAvoir ? 0.0 : amountTva, isAvoir ? amountTva : 0.0, "TVA"));
            }
            entries.add(buildJournalEntry(invoice, invoiceNumber, supplier, journal, clientAccount, entryDate,
                    isAvoir ? amountTtc : 0.0, isAvoir ? 0.0 : amountTtc, "Client"));
        }

        return ResponseEntity.ok(Map.of(
                "count", entries.size(),
                "entries", entries
        ));
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

        return salesInvoiceRepository.findById(id)
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
                    SalesInvoice saved = salesInvoiceRepository.save(invoice);

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
                Optional<SalesInvoice> invoiceOpt = salesInvoiceRepository.findById(id);
                if (invoiceOpt.isEmpty()) {
                    item.put("status", "error");
                    item.put("error", "not_found");
                    results.add(item);
                    continue;
                }
                SalesInvoice invoice = invoiceOpt.get();
                if (!canAccessInvoiceInDossier(sessionUser, invoice, dossierId)) {
                    item.put("status", "error");
                    item.put("error", "forbidden");
                    results.add(item);
                    continue;
                }
                SalesInvoice processed = processingService.reprocessExistingInvoice(invoice);
                item.put("status", "success");
                item.put("invoice", toResponse(processed));
                results.add(item);
                successCount++;
            } catch (Exception e) {
                log.error("Erreur bulk process vente ({}): {}", id, e.getMessage(), e);
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
                Optional<SalesInvoice> invoiceOpt = salesInvoiceRepository.findById(id);
                if (invoiceOpt.isEmpty()) {
                    item.put("status", "error");
                    item.put("error", "not_found");
                    results.add(item);
                    continue;
                }
                SalesInvoice invoice = invoiceOpt.get();
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
                SalesInvoice saved = salesInvoiceRepository.save(invoice);
                item.put("status", "success");
                item.put("invoice", toResponse(saved));
                results.add(item);
                successCount++;
            } catch (Exception e) {
                log.error("Erreur bulk validate vente ({}): {}", id, e.getMessage(), e);
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
                Optional<SalesInvoice> invoiceOpt = salesInvoiceRepository.findById(id);
                if (invoiceOpt.isEmpty()) {
                    item.put("status", "error");
                    item.put("error", "not_found");
                    results.add(item);
                    continue;
                }
                SalesInvoice invoice = invoiceOpt.get();
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

                salesInvoiceRepository.delete(invoice);

                try {
                    Files.deleteIfExists(Path.of(filePath));
                } catch (Exception e) {
                    log.warn("Facture vente {} supprimée en base, mais fichier non supprimé: {} ({})",
                            id, filePath, e.getMessage());
                }

                item.put("status", "success");
                results.add(item);
                successCount++;
            } catch (Exception e) {
                log.error("Erreur bulk delete vente ({}): {}", id, e.getMessage(), e);
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

    @GetMapping("/{id}/available-signatures")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> getAvailableSignatures(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "dossier_required"));
        }
        return salesInvoiceRepository.findById(id)
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

                    if (ifNumber != null && !ifNumber.isBlank()) {
                        signatures.add(Map.of(
                                "type", "IF",
                                "value", ifNumber,
                                "label", "IF: " + ifNumber,
                                "recommended", true,
                                "reason", "Identifiant unique du fournisseur (recommandé)"));
                    }

                    if (ice != null && !ice.isBlank()) {
                        signatures.add(Map.of(
                                "type", "ICE",
                                "value", ice,
                                "label", "ICE: " + ice,
                                "recommended", false,
                                "reason", "Peut apparaître plusieurs fois (client + fournisseur)"));
                    }

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
            stats.put("total", salesInvoiceRepository.countByDossierIdAndClientValidatedTrue(dossierId));
            stats.put("pending", salesInvoiceRepository.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.PENDING, dossierId));
            stats.put("processing", salesInvoiceRepository.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.PROCESSING, dossierId));
            stats.put("treated", salesInvoiceRepository.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.TREATED, dossierId));
            stats.put("readyToValidate", salesInvoiceRepository.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.READY_TO_VALIDATE, dossierId));
            stats.put("validated", salesInvoiceRepository.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.VALIDATED, dossierId));
            stats.put("error", salesInvoiceRepository.countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus.ERROR, dossierId));
        } else {
            stats.put("total", salesInvoiceRepository.countByDossierId(dossierId));
            stats.put("pending", salesInvoiceRepository.countByStatusAndDossierId(InvoiceStatus.PENDING, dossierId));
            stats.put("processing", salesInvoiceRepository.countByStatusAndDossierId(InvoiceStatus.PROCESSING, dossierId));
            stats.put("treated", salesInvoiceRepository.countByStatusAndDossierId(InvoiceStatus.TREATED, dossierId));
            stats.put("readyToValidate", salesInvoiceRepository.countByStatusAndDossierId(InvoiceStatus.READY_TO_VALIDATE, dossierId));
            stats.put("validated", salesInvoiceRepository.countByStatusAndDossierId(InvoiceStatus.VALIDATED, dossierId));
            stats.put("error", salesInvoiceRepository.countByStatusAndDossierId(InvoiceStatus.ERROR, dossierId));
        }

        List<SalesInvoice> lowConfidence = filterByClientValidated
                ? salesInvoiceRepository.findLowConfidenceByDossierIdClientValidated(0.7, dossierId)
                : salesInvoiceRepository.findLowConfidenceByDossierId(0.7, dossierId);
        stats.put("lowConfidenceCount", lowConfidence.size());

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{id}/download")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> downloadById(
            @PathVariable Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }

        Optional<SalesInvoice> invoiceOpt = salesInvoiceRepository.findById(id);
        if (invoiceOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        SalesInvoice invoice = invoiceOpt.get();
        if (!canAccessInvoiceInDossier(sessionUser, invoice, resolvedDossierId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (invoice.getFilePath() == null || invoice.getFilePath().isBlank()) {
            return ResponseEntity.notFound().build();
        }

        try {
            Path path = Path.of(invoice.getFilePath());
            if (!Files.exists(path)) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }

            String contentType = Files.probeContentType(path);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + path.getFileName() + "\"")
                    .body(resource);
        } catch (Exception e) {
            log.error("Erreur téléchargement facture vente {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/files/{filename}")
    @CrossOrigin("*")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> getFile(
            @PathVariable String filename,
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
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
        Optional<SalesInvoice> invoiceOpt = salesInvoiceRepository.findById(invoiceId);
        if (invoiceOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        SalesInvoice invoice = invoiceOpt.get();
        if (!canAccessInvoiceInDossier(sessionUser, invoice, dossierId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String filePath = invoice.getFilePath();
        if (filePath != null && !filePath.endsWith(filename)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            Path dir = fileStorageService.getBaseDirForFiles();

            if (!Files.exists(dir)) {
                return ResponseEntity.notFound().build();
            }

            try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
                Path foundPath = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(filename))
                        .findFirst()
                        .orElse(null);

                if (foundPath == null) {
                    return ResponseEntity.notFound().build();
                }

                Resource resource = new UrlResource(foundPath.toUri());

                if (!resource.exists() || !resource.isReadable()) {
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
            log.error("Erreur récupération fichier vente {}: {}", filename, e.getMessage());
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

        log.info("=== LIAISON TIER À FACTURE VENTE ===");
        log.info("Invoice ID: {}, Tier ID: {}", id, tierId);

        try {
            SalesInvoice invoice = salesInvoiceRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Facture vente non trouvée: " + id));

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

            Optional<TierDto> tierDtoOpt = tierService.getTierById(tierId, dossierId);

            if (tierDtoOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "success", false,
                        "error", "Tier non trouvé: " + tierId));
            }

            TierDto tierDto = tierDtoOpt.get();
            Tier tier = convertDtoToEntity(tierDto);

            invoice.setTier(tier);
            invoice.setTierId(tier.getId());
            invoice.setTierName(tier.getLibelle());

            Map<String, Object> fieldsData = invoice.getFieldsData();
            if (fieldsData == null) {
                fieldsData = new LinkedHashMap<>();
            }

            List<String> autoFilledFields = new ArrayList<>();

            fieldsData.put("supplier", tier.getLibelle());
            autoFilledFields.add("supplier");

            if (tier.hasAccountingConfiguration()) {
                fieldsData.put("tierNumber", tier.getTierNumber());
                autoFilledFields.add("tierNumber");

                if (tier.getAuxiliaireMode() != null && tier.getAuxiliaireMode()) {
                    fieldsData.put("collectifAccount", tier.getCollectifAccount());
                    autoFilledFields.add("collectifAccount");
                }

                fieldsData.put("chargeAccount", tier.getDefaultChargeAccount());
                fieldsData.put("tvaAccount", tier.getTvaAccount());
                fieldsData.put("tvaRate", tier.getDefaultTvaRate());

                autoFilledFields.add("chargeAccount");
                autoFilledFields.add("tvaAccount");
                autoFilledFields.add("tvaRate");
            }

            invoice.setFieldsData(fieldsData);

            List<String> currentAutoFilled = invoice.getAutoFilledFields();
            if (currentAutoFilled == null) {
                currentAutoFilled = new ArrayList<>();
            }
            currentAutoFilled.addAll(autoFilledFields);
            invoice.setAutoFilledFields(currentAutoFilled);

            if (invoice.getStatus() == InvoiceStatus.TREATED
                    || invoice.getStatus() == InvoiceStatus.RECALCULATED
                    || invoice.getStatus() == InvoiceStatus.OUT_OF_PERIOD) {
                invoice.setStatus(InvoiceStatus.READY_TO_VALIDATE);
            }

            SalesInvoice saved = salesInvoiceRepository.save(invoice);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Fournisseur lié avec succès",
                    "invoice", toDetailedResponse(saved)));

        } catch (DataIntegrityViolationException e) {
            log.error("Erreur intégrité liaison tier vente {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "error", "Conflit de données lors de la sauvegarde"));
        } catch (IllegalStateException e) {
            log.warn("Erreur validation tier vente {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur inattendue linkTier vente: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "Erreur serveur: " + e.getMessage()));
        }
    }

    // ===================== HELPERS =====================

    private SalesInvoice createUploadOnlyInvoice(MultipartFile file) {
        String filePath = fileStorageService.store(file);
        SalesInvoice invoice = new SalesInvoice();

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

    private boolean isDuplicateFilename(Long dossierId, String filename) {
        if (dossierId == null) return false;
        String normalized = normalizeKey(filename);
        if (normalized.isBlank()) return false;
        return salesInvoiceRepository.existsDuplicateFilenameInSameDossier(dossierId, normalized);
    }

    private boolean hasAnotherInvoiceWithSameNumber(Long dossierId, String invoiceNumber, Long invoiceId) {
        if (dossierId == null) return false;
        String normalized = normalizeKey(invoiceNumber);
        if (normalized.isBlank()) return false;
        return salesInvoiceRepository.countOtherInvoicesByDossierIdAndInvoiceNumber(dossierId, normalized, invoiceId) > 0;
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

    private boolean canAccessInvoiceInDossier(SessionUser sessionUser, SalesInvoice invoice, Long dossierId) {
        if (sessionUser == null || invoice == null || dossierId == null) {
            return false;
        }
        Long invoiceDossierId = invoice.getDossierId();
        if (invoiceDossierId == null || !dossierId.equals(invoiceDossierId)) {
            return false;
        }
        return canAccessInvoice(sessionUser, invoice);
    }

    private boolean canAccessInvoice(SessionUser sessionUser, SalesInvoice invoice) {
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
                if (item == null) continue;
                try {
                    ids.add(Long.valueOf(item.toString()));
                } catch (NumberFormatException ignored) {}
            }
            return ids;
        }
        if (raw instanceof String str) {
            String[] parts = str.split(",");
            for (String part : parts) {
                if (part.isBlank()) continue;
                try {
                    ids.add(Long.valueOf(part.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return ids;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
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
        if (amountWords.isBlank()) {
            fieldsData.remove("amountTTCEnLettres");
        } else {
            fieldsData.put("amountTTCEnLettres", amountWords);
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

    private double parseAmount(Object raw) {
        if (raw == null) {
            return 0.0;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            String cleaned = raw.toString()
                    .replace('\u00A0', ' ')
                    .replace('\u202F', ' ')
                    .replaceAll("\\s+", "")
                    .replaceAll("[^0-9,\\.\\-]", "")
                    .replace(",", ".");
            if (cleaned.isBlank() || "-".equals(cleaned)) {
                return 0.0;
            }
            return Double.parseDouble(cleaned);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private Map<String, Object> buildJournalEntry(
            SalesInvoice invoice,
            String invoiceNumber,
            String supplier,
            String journal,
            String accountNumber,
            Object entryDate,
            double debit,
            double credit,
            String label) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("invoiceId", invoice.getId());
        entry.put("invoiceNumber", invoiceNumber);
        entry.put("supplier", supplier);
        entry.put("journal", journal);
        entry.put("accountNumber", accountNumber);
        entry.put("entryDate", entryDate);
        entry.put("debit", debit);
        entry.put("credit", credit);
        entry.put("label", label);
        return entry;
    }

    private Map<String, Object> toResponse(SalesInvoice invoice) {
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
        response.put("createdAt", invoice.getCreatedAt());
        response.put("updatedAt", invoice.getUpdatedAt());
        return response;
    }

    private boolean isPendingWorkflowInvoice(SalesInvoice invoice) {
        if (invoice == null || invoice.getStatus() == null) {
            return false;
        }
        if (Boolean.TRUE.equals(invoice.getAccounted())) {
            return false;
        }
        return invoice.getStatus() != InvoiceStatus.VALIDATED;
    }

    private Map<String, Object> toDetailedResponse(SalesInvoice invoice) {
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
                tierData.put("defaultChargeAccount", tier.getDefaultChargeAccount() != null ? tier.getDefaultChargeAccount() : "");
                tierData.put("tvaAccount", tier.getTvaAccount() != null ? tier.getTvaAccount() : "");
                tierData.put("defaultTvaRate", tier.getDefaultTvaRate() != null ? tier.getDefaultTvaRate() : 0.0);
                tierData.put("active", tier.getActive());

                boolean hasAccountingConfig = tier.getDefaultChargeAccount() != null
                        && !tier.getDefaultChargeAccount().isBlank()
                        && tier.getTvaAccount() != null
                        && !tier.getTvaAccount().isBlank();
                tierData.put("hasAccountingConfig", hasAccountingConfig);

                response.put("tier", tierData);
                if (hasAccountingConfig) {
                    fieldsData.put("tierNumber", tier.getTierNumber());
                    if (tier.getAuxiliaireMode() != null && tier.getAuxiliaireMode()) {
                        fieldsData.put("collectifAccount", tier.getCollectifAccount());
                    }
                    fieldsData.put("chargeAccount", tier.getDefaultChargeAccount());
                    fieldsData.put("tvaAccount", tier.getTvaAccount());
                    fieldsData.put("tvaRate", tier.getDefaultTvaRate());
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

    private Tier convertDtoToEntity(TierDto dto) {
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
                .tvaAccount(dto.getTvaAccount())
                .defaultTvaRate(dto.getDefaultTvaRate())
                .active(dto.getActive())
                .build();
    }
}
