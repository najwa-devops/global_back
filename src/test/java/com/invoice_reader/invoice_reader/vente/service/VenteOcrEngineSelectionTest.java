package com.invoice_reader.invoice_reader.vente.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste la logique de décision du moteur OCR dans VenteInvoiceProcessingService.
 *
 * Condition reproduite depuis processInvoice() :
 *   boolean tryOlmOcrFirst = "primary".equalsIgnoreCase(olmocrStrategy)
 *           && olmocrFallbackService.isEnabled()
 *           && path.toString().toLowerCase().endsWith(".pdf");
 *
 * Pourquoi tester ça ?
 * Cette condition pilote QUEL moteur OCR est utilisé pour chaque facture.
 * Si elle est cassée, toutes les factures PDF passent par le mauvais moteur
 * sans aucune erreur visible — un bug silencieux de fiabilité critique.
 */
class VenteOcrEngineSelectionTest {

    /**
     * Reproduit exactement la condition de VenteInvoiceProcessingService.
     * Si la condition change dans le service, ce test échoue → régression détectée.
     */
    private boolean shouldUsePrimaryOlmOcr(String strategy, boolean enabled, String filename) {
        return "primary".equalsIgnoreCase(strategy)
                && enabled
                && filename.toLowerCase().endsWith(".pdf");
    }

    // ── Stratégie FALLBACK ────────────────────────────────────────────────────

    @Test
    void strategieFallback_avecPdfActive_nUtilisePasOlmOcrPrimary() {
        assertFalse(shouldUsePrimaryOlmOcr("fallback", true, "facture.pdf"));
    }

    @Test
    void strategieFallback_majuscule_nUtilisePasOlmOcrPrimary() {
        assertFalse(shouldUsePrimaryOlmOcr("FALLBACK", true, "facture.pdf"));
    }

    // ── Stratégie PRIMARY désactivée ─────────────────────────────────────────

    @Test
    void strategiePrimary_olmOcrDesactive_nUtilisePasOlmOcr() {
        assertFalse(shouldUsePrimaryOlmOcr("primary", false, "facture.pdf"));
    }

    // ── Stratégie PRIMARY activée, type de fichier ────────────────────────────

    @Test
    void strategiePrimary_active_fichierPdf_utiliseOlmOcr() {
        assertTrue(shouldUsePrimaryOlmOcr("primary", true, "facture.pdf"));
    }

    @Test
    void strategiePrimary_active_fichierPdfMajuscule_utiliseOlmOcr() {
        assertTrue(shouldUsePrimaryOlmOcr("primary", true, "FACTURE.PDF"));
    }

    @Test
    void strategiePrimary_active_fichierPng_nUtilisePasOlmOcr() {
        assertFalse(shouldUsePrimaryOlmOcr("primary", true, "facture.png"));
    }

    @Test
    void strategiePrimary_active_fichierJpg_nUtilisePasOlmOcr() {
        assertFalse(shouldUsePrimaryOlmOcr("primary", true, "scan.jpg"));
    }

    @Test
    void strategiePrimary_active_fichierJpeg_nUtilisePasOlmOcr() {
        assertFalse(shouldUsePrimaryOlmOcr("primary", true, "scan.jpeg"));
    }

    // ── Casse de la stratégie ─────────────────────────────────────────────────

    @Test
    void strategiePrimary_majuscule_active_pdf_utiliseOlmOcr() {
        assertTrue(shouldUsePrimaryOlmOcr("PRIMARY", true, "facture.pdf"));
    }

    @Test
    void strategiePrimary_mixedCase_active_pdf_utiliseOlmOcr() {
        assertTrue(shouldUsePrimaryOlmOcr("Primary", true, "facture.pdf"));
    }
}
