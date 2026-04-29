package com.invoice_reader.invoice_reader.sales.service.extraction;

import com.invoice_reader.invoice_reader.sales.utils.SalesInvoiceTypeDetector;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesAmountExtractorTest {

    @Test
    void isAvoir_givenNegativeAmount_whenDetect_thenReturnTrue() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("amountHT", -150.0);
        fields.put("amountTTC", -180.0);

        assertTrue(SalesInvoiceTypeDetector.isAvoir(fields));
    }

    @Test
    void isAvoir_givenAvoirKeywordInRawText_whenDetect_thenReturnTrue() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("amountHT", 100.0);
        fields.put("amountTTC", 120.0);

        assertTrue(SalesInvoiceTypeDetector.isAvoir(fields, "Ceci est un AVoir client"));
    }

    @Test
    void isAvoir_givenRegularInvoice_whenDetect_thenReturnFalse() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("amountHT", 100.0);
        fields.put("tva", 20.0);
        fields.put("amountTTC", 120.0);

        assertFalse(SalesInvoiceTypeDetector.isAvoir(fields, "Facture de vente classique"));
    }
}
