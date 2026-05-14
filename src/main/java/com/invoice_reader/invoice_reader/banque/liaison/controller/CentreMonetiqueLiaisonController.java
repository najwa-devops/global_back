package com.invoice_reader.invoice_reader.banque.liaison.controller;

import com.invoice_reader.invoice_reader.banque.entity.BanqueReleve;
import com.invoice_reader.invoice_reader.banque.repository.BanqueReleveRepository;
import com.invoice_reader.invoice_reader.banque.centremonetique.entity.CentreMonetiqueBatch;
import com.invoice_reader.invoice_reader.banque.centremonetique.repository.CentreMonetiqueBatchRepository;
import com.invoice_reader.invoice_reader.database.entity.auth.Dossier;
import com.invoice_reader.invoice_reader.database.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.banque.liaison.dto.CmExpansionDTO;
import com.invoice_reader.invoice_reader.banque.liaison.dto.RapprochementResultDTO;
import com.invoice_reader.invoice_reader.banque.liaison.service.CentreMonetiqueLiaisonService;
import com.invoice_reader.invoice_reader.database.dao.DossierDao;
import com.invoice_reader.invoice_reader.security.RequireRole;
import com.invoice_reader.invoice_reader.auth.service.AuthService;
import com.invoice_reader.invoice_reader.auth.service.SessionKeys;
import com.invoice_reader.invoice_reader.auth.service.SessionUser;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping({"/api/v2/centre-monetique", "/api/centre-monetique"})
@RequiredArgsConstructor
@Slf4j
public class CentreMonetiqueLiaisonController {

    private final CentreMonetiqueLiaisonService liaisonService;
    private final AuthService authService;
    private final DossierDao dossierDao;
    private final CentreMonetiqueBatchRepository batchRepository;
    private final BanqueReleveRepository bankStatementRepository;

    @GetMapping("/{id}/rapprochement")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> rapprochement(
            @PathVariable("id") Long id,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        SessionUser sessionUser = authService.requireSessionUser(session);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        Long resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);

        // Pour CLIENT sans dossier en session : essayer de le déduire du batch demandé
        if (resolvedDossierId == null && sessionUser.isClient()) {
            Optional<CentreMonetiqueBatch> batchForFallback = batchRepository.findById(id);
            if (batchForFallback.isPresent() && batchForFallback.get().getDossierId() != null) {
                Dossier d = requireDossierForUser(sessionUser, batchForFallback.get().getDossierId());
                if (d != null) {
                    resolvedDossierId = batchForFallback.get().getDossierId();
                }
            }
        }

        if (resolvedDossierId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
        }

        Optional<CentreMonetiqueBatch> batchOpt = batchRepository.findById(id);
        if (batchOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
        }
        // Compatibilité legacy: accepter les batches historiques sans dossier.
        Long batchDossierId = batchOpt.get().getDossierId();
        if (batchDossierId != null && !resolvedDossierId.equals(batchDossierId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
        }

        Optional<RapprochementResultDTO> result = liaisonService.rapprochement(id);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
        }
        return ResponseEntity.ok(result.get());
    }

    @GetMapping("/statement/{statementId}/expansions")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> cmExpansionsForStatement(
            @PathVariable("statementId") Long statementId,
            @RequestParam(value = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        try {
            SessionUser sessionUser = authService.requireSessionUser(session);
            if (sessionUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
            }
            Long resolvedDossierId = resolveOrFallbackDossierId(sessionUser, dossierId, session);

            // Pour CLIENT sans dossier en session : essayer de le déduire du relevé demandé
            if (resolvedDossierId == null && sessionUser.isClient()) {
                Optional<BanqueReleve> stmtForFallback = bankStatementRepository.findById(statementId);
                if (stmtForFallback.isPresent() && stmtForFallback.get().getDossierId() != null) {
                    Dossier d = requireDossierForUser(sessionUser, stmtForFallback.get().getDossierId());
                    if (d != null) {
                        resolvedDossierId = stmtForFallback.get().getDossierId();
                    }
                }
            }

            if (resolvedDossierId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "dossier_required"));
            }

            Optional<BanqueReleve> statementOpt = bankStatementRepository.findById(statementId);
            if (statementOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
            }
            // Compatibilité legacy: accepter les relevés historiques sans dossier.
            Long statementDossierId = statementOpt.get().getDossierId();
            if (statementDossierId != null && !resolvedDossierId.equals(statementDossierId)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Introuvable"));
            }

            List<CmExpansionDTO> expansions = liaisonService.getCmExpansionsForStatement(statementId);
            return ResponseEntity.ok(expansions);
        } catch (Exception e) {
            log.error("Erreur cm-expansions pour statement {}: {}", statementId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Erreur interne"));
        }
    }

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
