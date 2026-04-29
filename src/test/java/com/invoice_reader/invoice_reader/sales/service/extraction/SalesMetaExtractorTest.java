package com.invoice_reader.invoice_reader.sales.service.extraction;

import com.invoice_reader.invoice_reader.sales.service.SalesExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesMetaExtractorTest {

    @Test
    void toSimpleMap_givenExtractedFields_whenConvert_thenReturnNormalizedValuesOnly() {
        Map<String, SalesExtractionResult.ExtractedField> fields = new LinkedHashMap<>();
        fields.put("invoiceNumber", SalesExtractionResult.ExtractedField.builder()
                .value("FAC-001")
                .normalizedValue("FAC-001")
                .confidence(0.9)
                .build());
        fields.put("amountHT", SalesExtractionResult.ExtractedField.builder()
                .value("100,00")
                .normalizedValue("100.00")
                .confidence(0.85)
                .build());

        SalesExtractionResult result = SalesExtractionResult.builder()
                .extractedFields(fields)
                .build();

        Map<String, Object> simple = result.toSimpleMap();
        assertEquals(2, simple.size());
        assertEquals("FAC-001", simple.get("invoiceNumber"));
        assertEquals("100.00", simple.get("amountHT"));
    }

    @Test
    void toDetailedMap_givenFieldMetadata_whenConvert_thenIncludeFieldDetails() {
        Map<String, SalesExtractionResult.ExtractedField> fields = new LinkedHashMap<>();
        fields.put("ice", SalesExtractionResult.ExtractedField.builder()
                .value("001234567890123")
                .normalizedValue("001234567890123")
                .confidence(0.95)
                .detectionMethod("REGEX")
                .validated(true)
                .validationError(null)
                .build());

        SalesExtractionResult result = SalesExtractionResult.builder()
                .extractedFields(fields)
                .build();

        Map<String, Object> detailed = result.toDetailedMap();
        assertTrue(detailed.containsKey("ice"));

        @SuppressWarnings("unchecked")
        Map<String, Object> iceMap = (Map<String, Object>) detailed.get("ice");
        assertEquals("001234567890123", iceMap.get("value"));
        assertEquals("REGEX", iceMap.get("detectionMethod"));
    }

    @Test
    void getCounts_givenNullCollections_whenRead_thenReturnZero() {
        SalesExtractionResult result = new SalesExtractionResult();
        result.setExtractedFields(null);
        result.setMissingFields(null);

        assertEquals(0, result.getExtractedCount());
        assertEquals(0, result.getMissingCount());
    }
}
