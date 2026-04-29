package com.invoice_reader.invoice_reader.servises.ocr;

import com.invoice_reader.invoice_reader.entity.dynamic.DuplicateLevel;
import com.invoice_reader.invoice_reader.entity.dynamic.DynamicInvoice;
import com.invoice_reader.invoice_reader.repository.DynamicInvoiceDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * SERVICE DE DÉTECTION DE DOUBLONS
 *
 * Deux stratégies complémentaires :
 * 1. Doublon certain : même N° facture + même ICE dans le même dossier
 * 2. Doublon probable : même TTC (±0.05) + même fournisseur + date ±7 jours
 *
 * Les doublons ne sont PAS rejetés automatiquement — ils sont signalés
 * via DuplicateLevel pour que l'utilisateur décide.
 *
 * IMPORTANT : nécessite que la facture ait un id persisté avant appel.
 */
@Service
public class DuplicateDetectionService {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetectionService.class);

    private static final DateTimeFormatter DATE_FMT_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_FMT_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DynamicInvoiceDao dynamicInvoiceDao;

    public DuplicateDetectionService(DynamicInvoiceDao dynamicInvoiceDao) {
        this.dynamicInvoiceDao = dynamicInvoiceDao;
    }

    /**
     * Résultat de la détection de doublon.
     */
    public record DetectionResult(DuplicateLevel level, Long duplicateOfId) {
        public static DetectionResult none() {
            return new DetectionResult(DuplicateLevel.NONE, null);
        }
    }

    /**
     * Détecte si la facture donnée est un doublon d'une facture existante.
     *
     * @param invoice facture persistée (avec id non null)
     * @return résultat avec niveau NONE, PROBABLE ou CERTAIN
     */
    public DetectionResult detect(DynamicInvoice invoice) {
        if (invoice.getId() == null || invoice.getDossierId() == null) {
            return DetectionResult.none();
        }

        Long dossierId = invoice.getDossierId();
        Long currentId = invoice.getId();

        // ── Stratégie 1 : Doublon CERTAIN (N° facture + ICE) ──
        String invoiceNumber = invoice.getInvoiceNumber();
        String ice = invoice.getIce();

        if (invoiceNumber != null && !invoiceNumber.isBlank()
                && ice != null && !ice.isBlank()) {
            try {
                Optional<Long> certain = dynamicInvoiceDao.findCertainDuplicate(
                        dossierId, currentId, invoiceNumber.trim(), ice.trim()
                );
                if (certain.isPresent()) {
                    log.warn("Doublon CERTAIN détecté: facture {} = doublon de {}", currentId, certain.get());
                    return new DetectionResult(DuplicateLevel.CERTAIN, certain.get());
                }
            } catch (Exception e) {
                log.debug("DuplicateDetectionService stratégie 1 ignorée: {}", e.getMessage());
            }
        }

        // ── Stratégie 2 : Doublon PROBABLE (TTC + fournisseur + date ±7j) ──
        Double amountTTC = invoice.getAmountTTC();
        String supplier = invoice.getSupplier();
        String invoiceDateStr = invoice.getInvoiceDate();

        if (amountTTC != null && supplier != null && !supplier.isBlank()
                && invoiceDateStr != null && !invoiceDateStr.isBlank()) {
            LocalDate localDate = parseDate(invoiceDateStr);
            if (localDate != null) {
                try {
                    Optional<Long> probable = dynamicInvoiceDao.findProbableDuplicate(
                            dossierId, currentId,
                            amountTTC, supplier.trim(),
                            Date.valueOf(localDate)
                    );
                    if (probable.isPresent()) {
                        log.warn("Doublon PROBABLE détecté: facture {} ≈ doublon de {}", currentId, probable.get());
                        return new DetectionResult(DuplicateLevel.PROBABLE, probable.get());
                    }
                } catch (Exception e) {
                    log.debug("DuplicateDetectionService stratégie 2 ignorée: {}", e.getMessage());
                }
            }
        }

        return DetectionResult.none();
    }

    /**
     * Parse une date au format marocain "dd/MM/yyyy" ou ISO "yyyy-MM-dd".
     * Retourne null si le format n'est pas reconnu (pas d'exception propagée).
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return LocalDate.parse(dateStr.trim(), DATE_FMT_FR);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDate.parse(dateStr.trim(), DATE_FMT_ISO);
            } catch (DateTimeParseException e2) {
                log.debug("DuplicateDetectionService: date non parseable '{}'", dateStr);
                return null;
            }
        }
    }
}
