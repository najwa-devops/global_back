package com.invoice_reader.invoice_reader.servises.ocr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextCleaningServiceTest {

    private TextCleaningService service;

    @BeforeEach
    void setUp() {
        service = new TextCleaningService();
    }

    // ── parseAmount ──────────────────────────────────────────────────────────

    @Test
    void parseAmount_moroccanSpace() {
        assertEquals(1234.56, service.parseAmount("1 234,56"), 0.001);
    }

    @Test
    void parseAmount_dotThousands() {
        assertEquals(1234.56, service.parseAmount("1.234,56"), 0.001);
    }

    @Test
    void parseAmount_simpleComma() {
        assertEquals(1234.56, service.parseAmount("1234,56"), 0.001);
    }

    @Test
    void parseAmount_simpleDot() {
        assertEquals(1234.56, service.parseAmount("1234.56"), 0.001);
    }

    @Test
    void parseAmount_null_returnsNull() {
        assertNull(service.parseAmount(null));
    }

    @Test
    void parseAmount_blank_returnsNull() {
        assertNull(service.parseAmount("   "));
    }

    @Test
    void parseAmount_letters_returnsNull() {
        assertNull(service.parseAmount("N/A"));
    }

    // ── clean — normalisation mots-clés ──────────────────────────────────────

    @Test
    void clean_normalizeTTC() {
        String result = service.clean("Montant T.T.C 1000");
        assertTrue(result.contains("TTC"), "T.T.C doit être normalisé en TTC");
    }

    @Test
    void clean_normalizeTTCWithTrailingDot() {
        String result = service.clean("T.T.C. 1200");
        assertTrue(result.contains("TTC"), "T.T.C. doit être normalisé en TTC");
    }

    @Test
    void clean_normalizeHT() {
        String result = service.clean("Montant H.T. 1000");
        assertTrue(result.contains("HT"), "H.T. doit être normalisé en HT");
    }

    @Test
    void clean_normalizeTVA() {
        String result = service.clean("T.V.A. 20%");
        assertTrue(result.contains("TVA"), "T.V.A. doit être normalisé en TVA");
    }

    @Test
    void clean_normalizeICE() {
        String result = service.clean("I.C.E. 001234567000012");
        assertTrue(result.contains("ICE"), "I.C.E. doit être normalisé en ICE");
    }

    @Test
    void clean_normalizeNDegree() {
        String result = service.clean("N ° 12345");
        assertTrue(result.contains("N°"), "N ° doit être normalisé en N°");
    }

    // ── clean — zone markers préservés ───────────────────────────────────────

    @Test
    void clean_preserveHeaderMarker() {
        String result = service.clean("[HEADER]\nFACTURE N° 001\n[BODY]\nMontant TTC 1000\n[FOOTER]\nICE 001");
        assertTrue(result.contains("[HEADER]"), "[HEADER] doit être préservé");
        assertTrue(result.contains("[BODY]"), "[BODY] doit être préservé");
        assertTrue(result.contains("[FOOTER]"), "[FOOTER] doit être préservé");
    }

    @Test
    void clean_nullInput_returnsEmpty() {
        assertEquals("", service.clean(null));
    }

    @Test
    void clean_collapseMultipleSpaces() {
        String result = service.clean("Montant    TTC    1000");
        assertFalse(result.contains("  "), "Les espaces multiples doivent être réduits à un seul");
    }
}
