package com.invoice_reader.invoice_reader.banque.controller;

import com.invoice_reader.invoice_reader.banque.entity.BanqueReleve;
import com.invoice_reader.invoice_reader.banque.repository.BanqueReleveRepository;
import com.invoice_reader.invoice_reader.banque.service.ComptabilisationWorkflowService;
import com.invoice_reader.invoice_reader.database.entity.auth.Dossier;
import com.invoice_reader.invoice_reader.database.entity.auth.UserRole;
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
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping({"/api/v2/bank-statements", "/api/bank-statements"})
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class BanqueReleveAccountingController {

    private final ComptabilisationWorkflowService workflowService;
    private final BanqueReleveRepository bankStatementRepository;
    private final DossierDao dossierDao;
    private final AuthService authService;

    @PostMapping("/{id}/accounting/simulate")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> simulate(
            @PathVariable("id") Long id,
            @RequestParam(name = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        try {
            SessionUser sessionUser = authService.requireSessionUser(session);
            BanqueReleve statement = requireStatementForUser(id, dossierId, sessionUser, session);
            if (statement == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "dossier_forbidden"));
            }
            return ResponseEntity.ok(workflowService.simulate(statement.getId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur lors de la simulation de comptabilisation bancaire", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur interne lors de la simulation."));
        }
    }

    @PostMapping("/accounting/confirm")
    @RequireRole({UserRole.ADMIN, UserRole.COMPTABLE, UserRole.CLIENT})
    public ResponseEntity<?> confirm(
            @RequestBody ConfirmRequest request,
            HttpSession session) {
        try {
            if (request == null || request.simulationId() == null || request.simulationId().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "simulationId est obligatoire."));
            }
            SessionUser sessionUser = authService.requireSessionUser(session);
            String userId = sessionUser != null ? sessionUser.username() : request.userId();
            return ResponseEntity.ok(workflowService.confirm(request.simulationId(), userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Erreur lors de la confirmation de comptabilisation bancaire", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur interne lors de la confirmation."));
        }
    }

    private BanqueReleve requireStatementForUser(
            Long statementId,
            Long requestedDossierId,
            SessionUser sessionUser,
            HttpSession session) {
        if (statementId == null || sessionUser == null) {
            return null;
        }
        BanqueReleve statement = bankStatementRepository.findById(statementId).orElse(null);
        if (statement == null) {
            return null;
        }
        Long statementDossierId = statement.getDossierId();
        Long activeDossierId = resolveDossierId(
                sessionUser,
                requestedDossierId != null ? requestedDossierId : statementDossierId,
                session);
        if (activeDossierId == null) {
            return null;
        }
        if (requireDossierForUser(sessionUser, activeDossierId) == null) {
            return null;
        }
        if (statementDossierId != null && !statementDossierId.equals(activeDossierId)) {
            return null;
        }
        if (statementDossierId == null) {
            statement.setDossierId(activeDossierId);
            statement = bankStatementRepository.save(statement);
        }
        return statement;
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

    private Long resolveDossierId(SessionUser sessionUser, Long fallbackDossierId, HttpSession session) {
        if (fallbackDossierId != null) {
            if (session != null) {
                session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, fallbackDossierId);
            }
            return fallbackDossierId;
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
        try {
            Long resolvedId = Long.valueOf(rawId.toString());
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
        } catch (NumberFormatException ex) {
            session.removeAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
            return null;
        }
    }

    public record ConfirmRequest(String simulationId, String userId) {
    }
}
