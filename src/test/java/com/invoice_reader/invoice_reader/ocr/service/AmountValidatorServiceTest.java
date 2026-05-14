package com.invoice_reader.invoice_reader.ocr.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AmountValidatorServiceTest {

    private AmountValidatorService service;

    @BeforeEach
    void setUp() {
        service = new AmountValidatorService();
    }

    // ── validate(Double, Double, Double) ─────────────────────────────────────

    @Test
    void validate_coherent_isValid() {
        var result = service.validate(100.0, 20.0, 120.0);
        assertTrue(result.valid);
        assertNull(result.message);
    }

    @Test
    void validate_fiveCentimes_stillValid() {
        // TTC = 120.04 → HT(100) + TVA(20) = 120.00, diff = 0.04 < 0.05 → valid
        var result = service.validate(100.0, 20.0, 120.04);
        assertTrue(result.valid);
    }

    @Test
    void validate_exceedsTolerance_invalid() {
        // TTC = 121.0 → diff = 1.0 > 0.05 → invalid
        var result = service.validate(100.0, 20.0, 121.0);
        assertFalse(result.valid);
        assertNotNull(result.message);
        // TTC fait foi : HT recalculé = 121 - 20 = 101
        assertEquals(101.0, result.correctedHT, 0.01);
        assertEquals(121.0, result.correctedTTC, 0.01);
    }

    @Test
    void validate_missingTTC_autoCalc() {
        // HT=100, TVA=20, TTC=null → TTC calculé = 120
        var result = service.validate(100.0, 20.0, null);
        assertTrue(result.valid);
        assertEquals(120.0, result.correctedTTC, 0.01);
    }

    @Test
    void validate_missingHT_autoCalc() {
        // HT=null, TVA=20, TTC=120 → HT calculé = 100
        var result = service.validate(null, 20.0, 120.0);
        assertTrue(result.valid);
        assertEquals(100.0, result.correctedHT, 0.01);
    }

    @Test
    void validate_missingTVA_autoCalc() {
        // HT=100, TVA=null, TTC=120 → TVA calculée = 20
        var result = service.validate(100.0, null, 120.0);
        assertTrue(result.valid);
        assertEquals(20.0, result.correctedTVA, 0.01);
    }

    @Test
    void validate_allNull_isValid() {
        var result = service.validate(null, null, null);
        assertTrue(result.valid);
    }

    @Test
    void validate_onlyOneKnown_isValid() {
        var result = service.validate(100.0, null, null);
        assertTrue(result.valid);
    }

    // ── applyToFieldsData ─────────────────────────────────────────────────────

    @Test
    void applyToFieldsData_missingTTC_calculated() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("amountHT", 100.0);
        fields.put("tva", 20.0);

        boolean valid = service.applyToFieldsData(fields);

        assertTrue(valid);
        assertEquals(120.0, ((Number) fields.get("amountTTC")).doubleValue(), 0.01);
    }

    @Test
    void applyToFieldsData_stringValues_parsed() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("amountHT", "100,00");
        fields.put("tva", "20,00");
        fields.put("amountTTC", "120,00");

        boolean valid = service.applyToFieldsData(fields);
        assertTrue(valid);
    }

    @Test
    void applyToFieldsData_null_returnsTrue() {
        assertTrue(service.applyToFieldsData(null));
    }
}
