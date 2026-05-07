package com.invoice_reader.invoice_reader.controller.auth;

import com.invoice_reader.invoice_reader.entity.auth.Dossier;
import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.banking_entity.BankStatement;
import com.invoice_reader.invoice_reader.banking_entity.BankStatus;
import com.invoice_reader.invoice_reader.banking_repository.BankStatementRepository;
import com.invoice_reader.invoice_reader.centremonetique.entity.CentreMonetiqueBatch;
import com.invoice_reader.invoice_reader.centremonetique.repository.CentreMonetiqueBatchRepository;
import com.invoice_reader.invoice_reader.entity.dynamic.DynamicInvoice;
import com.invoice_reader.invoice_reader.entity.invoice.InvoiceStatus;
import com.invoice_reader.invoice_reader.repository.DossierDao;
import com.invoice_reader.invoice_reader.repository.DynamicInvoiceDao;
import com.invoice_reader.invoice_reader.sales.entity.SalesInvoice;
import com.invoice_reader.invoice_reader.sales.repository.SalesInvoiceRepository;
import com.invoice_reader.invoice_reader.security.RequireRole;
import com.invoice_reader.invoice_reader.servises.auth.AuthService;
import com.invoice_reader.invoice_reader.servises.auth.SessionKeys;
import com.invoice_reader.invoice_reader.servises.auth.SessionUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/client/dashboard")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ClientDashboardController {

    private final DossierDao dossierDao;
    private final DynamicInvoiceDao dynamicInvoiceDao;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final BankStatementRepository bankStatementRepository;
    private final CentreMonetiqueBatchRepository cmBatchRepository;
    private final AuthService authService;

    /**
     * GET /api/client/dashboard
     * Récupère le dashboard complet pour le client connecté
     */
    @GetMapping
    @RequireRole({UserRole.CLIENT})
    @Transactional(readOnly = true)
    public ResponseEntity<?> getDashboard(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        log.info("Client dashboard request");

        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        // Récupérer tous les dossiers du client
        List<Dossier> dossiers = dossierDao.findByClientId(sessionUser.id());
        if (dossiers.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Aucun dossier disponible",
                    "dossiers", List.of()
            ));
        }

        Dossier activeDossier = resolveClientDossier(sessionUser, dossierId, session)
                .orElse(dossiers.get(0));

        Long dossierIdToUse = activeDossier.getId();
        session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, dossierIdToUse);

        // Construire la réponse
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("clientId", sessionUser.id());
        response.put("clientName", sessionUser.username());
        response.put("dossiers", dossiers.stream()
                .map(this::dossierToMap)
                .toList());
        
        // Info dossier actif
        Map<String, Object> dossierInfo = new LinkedHashMap<>();
        dossierInfo.put("id", activeDossier.getId());
        dossierInfo.put("name", activeDossier.getName());
        dossierInfo.put("exerciseStartDate", activeDossier.getExerciseStartDate());
        dossierInfo.put("exerciseEndDate", activeDossier.getExerciseEndDate());
        dossierInfo.put("comptableId", activeDossier.getComptable() != null ? activeDossier.getComptable().getId() : null);
        dossierInfo.put("comptableName", activeDossier.getComptable() != null ? activeDossier.getComptable().getDisplayName() : null);
        response.put("activeDossier", dossierInfo);

        // Statistiques
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalBuyingInvoices", dynamicInvoiceDao.countByDossierId(dossierIdToUse));
        stats.put("totalSalesInvoices", salesInvoiceRepository.countByDossierId(dossierIdToUse));
        stats.put("totalBankStatements", bankStatementRepository.countByDossierId(dossierIdToUse));
        stats.put("totalCentreMonetique", cmBatchRepository.countByDossierId(dossierIdToUse));

        // Compter les factures en attente
        long pendingBuying = dynamicInvoiceDao.findByDossierIdOrderByCreatedAtDesc(dossierIdToUse).stream()
                .filter(this::isPendingInvoice)
                .count();
        long pendingSales = salesInvoiceRepository.findByDossierIdOrderByCreatedAtDesc(dossierIdToUse).stream()
                .filter(this::isPendingSalesInvoice)
                .count();
        long pendingBankStatements = bankStatementRepository.findByDossierIdOrderByCreatedAtDesc(dossierIdToUse).stream()
                .filter(this::isPendingBankStatement)
                .count();
        long pendingCentreMonetique = cmBatchRepository.findByDossierIdOrderByCreatedAtDesc(dossierIdToUse).stream()
                .filter(this::isPendingCentreMonetique)
                .count();

        stats.put("pendingBuyingInvoices", pendingBuying);
        stats.put("pendingSalesInvoices", pendingSales);
        stats.put("pendingBankStatements", pendingBankStatements);
        stats.put("pendingCentreMonetique", pendingCentreMonetique);
        stats.put("pendingTotal", pendingBuying + pendingSales + pendingBankStatements + pendingCentreMonetique);

        response.put("stats", stats);

        // Listes des documents récents (les 10 derniers de chaque type)
        response.put("recentBuyingInvoices", getRecentBuyingInvoices(dossierIdToUse, 10));
        response.put("recentSalesInvoices", getRecentSalesInvoices(dossierIdToUse, 10));
        response.put("recentBankStatements", getRecentBankStatements(dossierIdToUse, 10));
        response.put("recentCentreMonetique", getRecentCentreMonetique(dossierIdToUse, 10));

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/client/dashboard/buying-invoices
     * Liste complète des factures d'achat du client
     */
    @GetMapping("/buying-invoices")
    @RequireRole({UserRole.CLIENT})
    @Transactional(readOnly = true)
    public ResponseEntity<?> listBuyingInvoices(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int limit,
            HttpSession session) {
        log.info("Client buying invoices list");

        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }

        List<DynamicInvoice> invoices;
        if (status != null && !status.isBlank()) {
            try {
                InvoiceStatus invoiceStatus = InvoiceStatus.valueOf(status.toUpperCase());
                invoices = dynamicInvoiceDao.findByStatusAndDossierIdOrderByCreatedAtDesc(invoiceStatus, resolvedDossierId);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid status: " + status));
            }
        } else {
            invoices = dynamicInvoiceDao.findByDossierIdOrderByCreatedAtDesc(resolvedDossierId);
        }

        List<Map<String, Object>> items = invoices.stream()
                .skip((long) page * limit)
                .limit(limit)
                .map(this::invoiceToMap)
                .toList();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "page", page,
                "limit", limit,
                "total", invoices.size(),
                "invoices", items
        ));
    }

    /**
     * GET /api/client/dashboard/sales-invoices
     * Liste complète des factures de vente du client
     */
    @GetMapping("/sales-invoices")
    @RequireRole({UserRole.CLIENT})
    @Transactional(readOnly = true)
    public ResponseEntity<?> listSalesInvoices(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int limit,
            HttpSession session) {
        log.info("Client sales invoices list");

        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }

        List<SalesInvoice> invoices;
        if (status != null && !status.isBlank()) {
            try {
                InvoiceStatus invoiceStatus = InvoiceStatus.valueOf(status.toUpperCase());
                invoices = salesInvoiceRepository.findByStatusAndDossierIdOrderByCreatedAtDesc(invoiceStatus, resolvedDossierId);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid status: " + status));
            }
        } else {
            invoices = salesInvoiceRepository.findByDossierIdOrderByCreatedAtDesc(resolvedDossierId);
        }

        List<Map<String, Object>> items = invoices.stream()
                .skip((long) page * limit)
                .limit(limit)
                .map(this::salesInvoiceToMap)
                .toList();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "page", page,
                "limit", limit,
                "total", invoices.size(),
                "invoices", items
        ));
    }

    /**
     * GET /api/client/dashboard/bank-statements
     * Liste des relevés bancaires du client
     */
    @GetMapping("/bank-statements")
    @RequireRole({UserRole.CLIENT})
    @Transactional(readOnly = true)
    public ResponseEntity<?> listBankStatements(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int limit,
            HttpSession session) {
        log.info("Client bank statements list");

        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }

        List<BankStatement> statements = bankStatementRepository.findByDossierIdOrderByCreatedAtDesc(resolvedDossierId);

        List<Map<String, Object>> items = statements.stream()
                .skip((long) page * limit)
                .limit(limit)
                .map(this::bankStatementToMap)
                .toList();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "page", page,
                "limit", limit,
                "total", statements.size(),
                "statements", items
        ));
    }

    /**
     * GET /api/client/dashboard/centre-monetique
     * Liste des relevés centre monétique du client
     */
    @GetMapping("/centre-monetique")
    @RequireRole({UserRole.CLIENT})
    @Transactional(readOnly = true)
    public ResponseEntity<?> listCentreMonetique(
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int limit,
            HttpSession session) {
        log.info("Client centre monetique list");

        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        Long resolvedDossierId = resolveDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }

        List<CentreMonetiqueBatch> batches = cmBatchRepository.findByDossierIdOrderByCreatedAtDesc(resolvedDossierId);

        List<Map<String, Object>> items = batches.stream()
                .skip((long) page * limit)
                .limit(limit)
                .map(this::centreMonetiqueBatchToMap)
                .toList();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "page", page,
                "limit", limit,
                "total", batches.size(),
                "batches", items
        ));
    }

    // ==================== HELPER METHODS ====================

    private Long resolveDossierId(SessionUser sessionUser, Long providedDossierId, HttpSession session) {
        return resolveClientDossier(sessionUser, providedDossierId, session)
                .map(Dossier::getId)
                .orElse(null);
    }

    private Optional<Dossier> resolveClientDossier(SessionUser sessionUser, Long providedDossierId, HttpSession session) {
        if (sessionUser == null) {
            return Optional.empty();
        }

        if (providedDossierId != null) {
            return dossierDao.findByIdAndClientId(providedDossierId, sessionUser.id());
        }

        if (session != null) {
            Object rawActiveId = session.getAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
            if (rawActiveId != null) {
                try {
                    Long activeId = Long.valueOf(rawActiveId.toString());
                    Optional<Dossier> activeDossier = dossierDao.findByIdAndClientId(activeId, sessionUser.id());
                    if (activeDossier.isPresent()) {
                        return activeDossier;
                    }
                } catch (NumberFormatException e) {
                    session.removeAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
                }
            }
        }

        return dossierDao.findFirstByClientIdAndActiveTrueOrderByCreatedAtDesc(sessionUser.id());
    }

    private boolean isPendingInvoice(DynamicInvoice invoice) {
        if (invoice == null || invoice.getStatus() == null) {
            return false;
        }
        return switch (invoice.getStatus()) {
            case VALIDATED, ACCOUNTED, ERROR, DUPLICATE, OUT_OF_PERIOD -> false;
            default -> true;
        };
    }

    private boolean isPendingSalesInvoice(SalesInvoice invoice) {
        if (invoice == null || invoice.getStatus() == null) {
            return false;
        }
        return switch (invoice.getStatus()) {
            case VALIDATED, ACCOUNTED, ERROR, DUPLICATE, OUT_OF_PERIOD -> false;
            default -> true;
        };
    }

    private boolean isPendingBankStatement(BankStatement statement) {
        if (statement == null || statement.getStatus() == null) {
            return false;
        }
        return statement.getStatus() != BankStatus.VALIDATED
                && statement.getStatus() != BankStatus.COMPTABILISE
                && statement.getStatus() != BankStatus.ERROR
                && statement.getStatus() != BankStatus.DUPLIQUE;
    }

    private boolean isPendingCentreMonetique(CentreMonetiqueBatch batch) {
        if (batch == null || batch.getStatus() == null) {
            return false;
        }
        return !"PROCESSED".equalsIgnoreCase(batch.getStatus());
    }

    private List<Map<String, Object>> getRecentBuyingInvoices(Long dossierId, int limit) {
        return dynamicInvoiceDao.findByDossierIdOrderByCreatedAtDesc(dossierId).stream()
                .limit(limit)
                .map(this::invoiceToMap)
                .toList();
    }

    private List<Map<String, Object>> getRecentSalesInvoices(Long dossierId, int limit) {
        return salesInvoiceRepository.findByDossierIdOrderByCreatedAtDesc(dossierId).stream()
                .limit(limit)
                .map(this::salesInvoiceToMap)
                .toList();
    }

    private List<Map<String, Object>> getRecentBankStatements(Long dossierId, int limit) {
        return bankStatementRepository.findByDossierIdOrderByCreatedAtDesc(dossierId).stream()
                .limit(limit)
                .map(this::bankStatementToMap)
                .toList();
    }

    private List<Map<String, Object>> getRecentCentreMonetique(Long dossierId, int limit) {
        return cmBatchRepository.findByDossierIdOrderByCreatedAtDesc(dossierId).stream()
                .limit(limit)
                .map(this::centreMonetiqueBatchToMap)
                .toList();
    }

    private Map<String, Object> invoiceToMap(DynamicInvoice invoice) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", invoice.getId());
        map.put("filename", invoice.getFilename());
        map.put("originalName", invoice.getOriginalName());
        map.put("status", invoice.getStatus() != null ? invoice.getStatus().name() : null);
        map.put("supplierName", getFieldValue(invoice, "supplierName"));
        map.put("invoiceNumber", getFieldValue(invoice, "invoiceNumber"));
        map.put("invoiceDate", getFieldValue(invoice, "invoiceDate"));
        map.put("totalAmount", getFieldValue(invoice, "totalAmount"));
        map.put("createdAt", invoice.getCreatedAt());
        map.put("scanned", Boolean.TRUE.equals(invoice.getScanned()));
        map.put("clientValidated", invoice.getClientValidated());
        map.put("clientValidatedAt", invoice.getClientValidatedAt());
        map.put("validatedAt", invoice.getValidatedAt());
        map.put("accounted", Boolean.TRUE.equals(invoice.getAccounted()));
        map.put("accountedAt", invoice.getAccountedAt());
        return map;
    }

    private Map<String, Object> salesInvoiceToMap(SalesInvoice invoice) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", invoice.getId());
        map.put("filename", invoice.getFilename());
        map.put("originalName", invoice.getOriginalName());
        map.put("status", invoice.getStatus() != null ? invoice.getStatus().name() : null);
        map.put("clientName", getFieldValue(invoice.getFieldsData(), "clientName"));
        map.put("invoiceNumber", getFieldValue(invoice.getFieldsData(), "invoiceNumber"));
        map.put("invoiceDate", getFieldValue(invoice.getFieldsData(), "invoiceDate"));
        map.put("totalAmount", getFieldValue(invoice.getFieldsData(), "totalAmount"));
        map.put("createdAt", invoice.getCreatedAt());
        map.put("scanned", Boolean.TRUE.equals(invoice.getScanned()));
        map.put("clientValidated", invoice.getClientValidated());
        map.put("clientValidatedAt", invoice.getClientValidatedAt());
        map.put("validatedAt", invoice.getValidatedAt());
        map.put("accounted", Boolean.TRUE.equals(invoice.getAccounted()));
        map.put("accountedAt", invoice.getAccountedAt());
        return map;
    }

    private Map<String, Object> bankStatementToMap(BankStatement statement) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", statement.getId());
        map.put("filename", statement.getFilename());
        map.put("bankName", statement.getBankName());
        map.put("status", statement.getStatus() != null ? statement.getStatus().name() : null);
        map.put("createdAt", statement.getCreatedAt());
        map.put("clientValidated", Boolean.TRUE.equals(statement.getClientValidated()));
        map.put("clientValidatedAt", statement.getClientValidatedAt());
        map.put("clientValidatedBy", statement.getClientValidatedBy());
        return map;
    }

    private Map<String, Object> centreMonetiqueBatchToMap(CentreMonetiqueBatch batch) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", batch.getId());
        map.put("filename", batch.getFilename());
        map.put("status", batch.getStatus());
        map.put("structure", batch.getStructure());
        map.put("createdAt", batch.getCreatedAt());
        map.put("clientValidated", Boolean.TRUE.equals(batch.getClientValidated()));
        map.put("clientValidatedAt", batch.getClientValidatedAt());
        map.put("clientValidatedBy", batch.getClientValidatedBy());
        return map;
    }

    private Map<String, Object> dossierToMap(Dossier dossier) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", dossier.getId());
        map.put("name", dossier.getName());
        map.put("exerciseStartDate", dossier.getExerciseStartDate());
        map.put("exerciseEndDate", dossier.getExerciseEndDate());
        map.put("active", dossier.getActive());
        return map;
    }

    private Object getFieldValue(DynamicInvoice invoice, String fieldName) {
        if (invoice == null || invoice.getFieldsData() == null) {
            return null;
        }
        return getFieldValue(invoice.getFieldsData(), fieldName);
    }

    private Object getFieldValue(Map<String, Object> fieldsData, String fieldName) {
        if (fieldsData == null) {
            return null;
        }
        return fieldsData.get(fieldName);
    }
}
