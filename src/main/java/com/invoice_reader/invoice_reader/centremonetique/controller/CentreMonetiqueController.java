package com.invoice_reader.invoice_reader.centremonetique.controller;

import com.invoice_reader.invoice_reader.centremonetique.dto.CentreMonetiqueBatchDetailDTO;
import com.invoice_reader.invoice_reader.centremonetique.dto.CentreMonetiqueBatchSummaryDTO;
import com.invoice_reader.invoice_reader.centremonetique.dto.CentreMonetiqueExtractionRow;
import com.invoice_reader.invoice_reader.centremonetique.dto.CentreMonetiqueUploadResponseDTO;
import com.invoice_reader.invoice_reader.centremonetique.service.CentreMonetiqueStructureType;
import com.invoice_reader.invoice_reader.centremonetique.service.CentreMonetiqueWorkflowService;
import com.invoice_reader.invoice_reader.entity.auth.Dossier;
import com.invoice_reader.invoice_reader.entity.auth.DossierGeneralParams;
import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.repository.DossierDao;
import com.invoice_reader.invoice_reader.repository.DossierGeneralParamsDao;
import com.invoice_reader.invoice_reader.security.RequireRole;
import com.invoice_reader.invoice_reader.servises.auth.AuthService;
import com.invoice_reader.invoice_reader.servises.auth.SessionKeys;
import com.invoice_reader.invoice_reader.servises.auth.SessionUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping({"/api/v2/centre-monetique", "/api/centre-monetique"})
@RequiredArgsConstructor
@Slf4j
public class CentreMonetiqueController {

    private final CentreMonetiqueWorkflowService workflowService;
    private final AuthService authService;
    private final DossierDao dossierDao;
    private final DossierGeneralParamsDao dossierGeneralParamsDao;

    @PostMapping("/upload")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> uploadAndExtract(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "structure", required = false) String structure,
            @RequestParam(name = "rib", required = false) String rib,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {

        try {
            SessionUser sessionUser = authService.requireSessionUser(session);
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "unauthorized"));
            }

            Long resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);
            if (resolvedDossierId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "dossier_required"));
            }

            Dossier dossier = requireDossierForUser(sessionUser, resolvedDossierId);
            if (dossier == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "dossier_forbidden", 
                                "message", "Vous n'avez pas accès à ce dossier"));
            }

            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Fichier vide"));
            }

            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
            if (!isSupported(filename, file.getContentType())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Type de fichier non supporte",
                        "filename", filename,
                        "contentType", file.getContentType() != null ? file.getContentType() : "unknown",
                        "supported", List.of("pdf", "png", "jpg", "jpeg", "webp", "bmp", "tif", "tiff")));
            }

            CentreMonetiqueBatchDetailDTO detail = workflowService.uploadAndExtract(
                    file, year, CentreMonetiqueStructureType.fromNullable(structure), rib, resolvedDossierId);
            return ResponseEntity.status(HttpStatus.CREATED).body(new CentreMonetiqueUploadResponseDTO(
                    "Extraction terminee",
                    detail,
                    detail.getRows()));
        } catch (Exception e) {
            log.error("Erreur extraction centre monetique: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Erreur interne"));
        }
    }

    @PutMapping("/{id}/rib")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> updateRib(@PathVariable("id") Long id,
                                       @RequestBody Map<String, String> body,
                                       @RequestParam(value = "dossierId", required = false) Long dossierId,
                                       HttpSession session) {
        try {
            SessionUser sessionUser = authService.requireSessionUser(session);
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "unauthorized"));
            }

            Long resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);
            if (resolvedDossierId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "dossier_required"));
            }

            String rib = body != null ? body.get("rib") : null;
            Optional<CentreMonetiqueBatchDetailDTO> detail = workflowService.updateRib(id, rib, resolvedDossierId);
            if (detail.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
            }
            return ResponseEntity.ok(detail.get());
        } catch (Exception e) {
            log.error("Erreur mise à jour RIB centre monétique {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Erreur interne"));
        }
    }

    @GetMapping
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> list(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        try {
            SessionUser sessionUser = authService.requireSessionUser(session);
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "unauthorized"));
            }

            Long resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);
            if (resolvedDossierId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "dossier_required"));
            }

            List<CentreMonetiqueBatchSummaryDTO> batches = workflowService.list(limit, resolvedDossierId);
            if (!sessionUser.isClient()) {
                batches = batches.stream()
                        .filter(batch -> Boolean.TRUE.equals(batch.getClientValidated()))
                        .toList();
            }
            return ResponseEntity.ok(Map.of(
                    "count", batches.size(),
                    "batches", batches));
        } catch (Exception e) {
            log.error("Erreur listage centre monetique: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Erreur lors du listage"));
        }
    }

    @GetMapping("/{id}")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> detail(
            @PathVariable("id") Long id,
            @RequestParam(name = "includeRawOcr", defaultValue = "false") boolean includeRawOcr,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        try {
            SessionUser sessionUser = authService.requireSessionUser(session);
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "unauthorized"));
            }

            Long resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);
            if (resolvedDossierId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "dossier_required"));
            }

            Optional<CentreMonetiqueBatchDetailDTO> detail = workflowService.findDetail(id, includeRawOcr, resolvedDossierId);
            return detail.<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable")));
        } catch (Exception e) {
            log.error("Erreur détail centre monetique: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Erreur lors de la récupération"));
        }
    }

    @PostMapping("/{id}/reprocess")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> reprocess(@PathVariable("id") Long id,
                                       @RequestParam(name = "year", required = false) Integer year,
                                       @RequestParam(name = "structure", required = false) String structure,
                                       @RequestParam(value = "dossierId", required = false) Long dossierId,
                                       HttpSession session) {
        try {
            SessionUser sessionUser = authService.requireSessionUser(session);
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "unauthorized"));
            }

            Long resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);
            if (resolvedDossierId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "dossier_required"));
            }

            Optional<CentreMonetiqueBatchDetailDTO> detail = workflowService.reprocess(
                    id, year, CentreMonetiqueStructureType.fromNullable(structure), resolvedDossierId);
            if (detail.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
            }
            return ResponseEntity.ok(new CentreMonetiqueUploadResponseDTO(
                    "Retraitement termine",
                    detail.get(),
                    detail.get().getRows()));
        } catch (Exception e) {
            log.error("Erreur reprocess centre monetique {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Erreur interne"));
        }
    }

    @PutMapping("/{id}/rows")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE})
    public ResponseEntity<?> saveRows(@PathVariable("id") Long id,
                                      @RequestBody(required = false) List<CentreMonetiqueExtractionRow> rows,
                                      @RequestParam(value = "dossierId", required = false) Long dossierId,
                                      HttpSession session) {
        try {
            SessionUser sessionUser = authService.requireSessionUser(session);
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "unauthorized"));
            }

            Long resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);
            if (resolvedDossierId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "dossier_required"));
            }

            Optional<CentreMonetiqueBatchDetailDTO> detail = workflowService.saveRows(id, rows, resolvedDossierId);
            if (detail.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
            }
            return ResponseEntity.ok(new CentreMonetiqueUploadResponseDTO(
                    "Lignes enregistrees",
                    detail.get(),
                    detail.get().getRows()));
        } catch (Exception e) {
            log.error("Erreur sauvegarde des lignes centre monétique {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Erreur interne"));
        }
    }

    @GetMapping("/{id}/file")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<Resource> file(@PathVariable("id") Long id,
                                         @RequestParam(value = "dossierId", required = false) Long dossierId,
                                         HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Long resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);
        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<Map<String, Object>> payload = workflowService.filePayload(id, resolvedDossierId);
        if (payload.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        byte[] data = (byte[]) payload.get().get("data");
        if (data == null || data.length == 0) {
            return ResponseEntity.notFound().build();
        }

        String filename = String.valueOf(payload.get().get("filename"));
        String contentType = String.valueOf(payload.get().get("contentType"));
        ByteArrayResource resource = new ByteArrayResource(data);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(resource);
    }

    @DeleteMapping("/{id}")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> delete(
            @PathVariable("id") Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        try {
            SessionUser sessionUser = authService.requireSessionUser(session);
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "unauthorized"));
            }

            Long resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);
            if (resolvedDossierId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "dossier_required"));
            }

            Optional<CentreMonetiqueBatchDetailDTO> existing = workflowService.findDetail(id, false, resolvedDossierId);
            if (existing.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
            }
            CentreMonetiqueBatchDetailDTO detail = existing.get();
            if (!canAccessBatchInDossier(sessionUser, detail.getId(), resolvedDossierId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            }
            if (sessionUser.isClient() && Boolean.TRUE.equals(detail.getClientValidated())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "client_validated"));
            }
            if (Boolean.TRUE.equals(detail.getClientValidated()) && !isValidatedDocumentDeletionAllowed(resolvedDossierId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "validated_document_deletion_disabled"));
            }
            boolean deleted = workflowService.delete(id, resolvedDossierId);
            if (!deleted) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
            }
            return ResponseEntity.ok(Map.of("message", "Supprime", "id", id));
        } catch (Exception e) {
            log.error("Erreur suppression centre monetique: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Erreur lors de la suppression"));
        }
    }

    @PostMapping("/{id}/client-validate")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> clientValidate(@PathVariable("id") Long id,
                                            @RequestParam(value = "dossierId", required = false) Long dossierId,
                                            HttpSession session) {
        try {
            SessionUser sessionUser = authService.requireSessionUser(session);
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "unauthorized"));
            }

            Long resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);
            if (resolvedDossierId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "dossier_required"));
            }

            Optional<CentreMonetiqueBatchDetailDTO> existing = workflowService.findDetail(id, false, resolvedDossierId);
            if (existing.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
            }
            CentreMonetiqueBatchDetailDTO detail = existing.get();
            if (!canAccessBatchInDossier(sessionUser, detail.getId(), resolvedDossierId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            }
            if (Boolean.TRUE.equals(detail.getClientValidated())) {
                return ResponseEntity.ok(Map.of(
                        "message", "Batch déjà validé par le client",
                        "batch", detail));
            }

            return workflowService.clientValidate(id, resolvedDossierId, sessionUser.username())
                    .<ResponseEntity<?>>map(batch -> ResponseEntity.ok(Map.of(
                            "message", "Batch validé par le client",
                            "batch", batch)))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable")));
        } catch (Exception e) {
            log.error("Erreur validation client centre monetique {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Erreur lors de la validation"));
        }
    }

    // ==================== AUTORISATION PAR DOSSIER ====================

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

    private boolean isValidatedDocumentDeletionAllowed(Long dossierId) {
        return dossierGeneralParamsDao.findByDossierId(dossierId)
                .map(params -> Boolean.TRUE.equals(params.getAllowValidatedDocumentDeletion()))
                .orElse(false);
    }

    private boolean canAccessBatchInDossier(SessionUser sessionUser, Long batchId, Long dossierId) {
        if (sessionUser == null || batchId == null || dossierId == null) {
            return false;
        }
        Optional<Dossier> dossierOpt = dossierDao.findById(dossierId);
        if (dossierOpt.isEmpty()) {
            return false;
        }
        Dossier dossier = dossierOpt.get();
        if (sessionUser.isAdmin()) {
            return true;
        }
        if (sessionUser.isComptable()) {
            return dossier.getComptable() != null && dossier.getComptable().getId().equals(sessionUser.id());
        }
        if (sessionUser.isClient()) {
            return dossier.getClient() != null && dossier.getClient().getId().equals(sessionUser.id());
        }
        return false;
    }

    private Long resolveDossierId(SessionUser sessionUser, Long requestedDossierId, HttpSession session) {
        if (requestedDossierId != null) {
            Dossier requested = requireDossierForUser(sessionUser, requestedDossierId);
            return requested != null ? requestedDossierId : null;
        }

        Long sessionDossierId = (Long) session.getAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
        if (sessionDossierId == null) {
            return null;
        }

        Dossier sessionDossier = requireDossierForUser(sessionUser, sessionDossierId);
        return sessionDossier != null ? sessionDossierId : null;
    }

    private Dossier requireDossierForUser(SessionUser sessionUser, Long dossierId) {
        if (sessionUser == null || dossierId == null) {
            return null;
        }
        
        Optional<Dossier> dossierOpt = dossierDao.findById(dossierId);
        if (dossierOpt.isEmpty()) {
            log.warn("Dossier {} non trouvé", dossierId);
            return null;
        }
        
        Dossier dossier = dossierOpt.get();
        
        // ADMIN : voit tous les dossiers
        if (sessionUser.isAdmin()) {
            return dossier;
        }
        
        // COMPTABLE : voit seulement ses propres dossiers (ceux qu'il a créés)
        if (sessionUser.isComptable()) {
            if (dossier.getComptableId() != null && dossier.getComptableId().equals(sessionUser.id())) {
                return dossier;
            }
            log.warn("Dossier {} n'appartient pas au comptable {}", dossierId, sessionUser.username());
            return null;
        }
        
        // CLIENT : voit seulement ses propres dossiers
        if (sessionUser.isClient()) {
            if (dossier.getClientId() != null && dossier.getClientId().equals(sessionUser.id())) {
                return dossier;
            }
            log.warn("Dossier {} n'appartient pas au client {}", dossierId, sessionUser.username());
            return null;
        }
        
        return null;
    }

    private boolean isSupported(String filename, String contentType) {
        String lower = filename.toLowerCase();
        boolean extOk = lower.endsWith(".pdf") || lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx")
                || lower.endsWith(".tif") || lower.endsWith(".tiff");

        if (contentType == null) {
            return extOk;
        }

        String ct = contentType.toLowerCase();
        boolean mimeOk = ct.equals("application/pdf") || ct.equals("image/png") || ct.equals("image/jpeg")
                || ct.equals("image/jpg") || ct.equals("image/webp") || ct.equals("image/bmp")
                || ct.equals("image/tiff") || ct.equals("application/octet-stream")
                || ct.equals("application/vnd.ms-excel")
                || ct.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                || ct.contains("spreadsheet");

        return extOk || mimeOk;
    }
}
