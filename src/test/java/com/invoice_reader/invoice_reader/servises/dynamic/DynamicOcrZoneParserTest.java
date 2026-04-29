package com.invoice_reader.invoice_reader.servises.dynamic;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicOcrZoneParserTest {

    private final DynamicFieldExtractorService extractorService = new DynamicFieldExtractorService();

    @Test
    void extractWithoutTemplate_givenUserRegexLabels_whenExtract_thenFindExpectedValues() {
        String ocr = """
                [HEADER]
                BL/FACTURE N° BL-2026-77
                le 07/03/2026
                [BODY]
                Montant net: 100,00
                DONT TVA 20% 20,00
                [FOOTER]
                TOTAL NET A PAYER : 120,00
                """;

        DynamicExtractionResult result = extractorService.extractWithoutTemplate(ocr);
        Map<String, Object> simple = result.toSimpleMap();

        assertEquals("BL-2026-77", simple.get("invoiceNumber"));
        assertEquals("07/03/2026", simple.get("invoiceDate"));
        assertEquals("100.00", simple.get("amountHT"));
        assertEquals("20.00", simple.get("tva"));
        assertEquals("120.00", simple.get("amountTTC"));
    }

    @Test
    void extractWithoutTemplate_whenLabelAppearsAfterAmount_thenMapTtcAndTvaCorrectly() {
        String ocr = """
                [PAGE 1]
                MARRAKECH, le 19/08/2025
                BL/FACTURE N° 25/02869
                2.460.00
                TOTAL NET A PAYER
                20% 410.00
                DONT TVA
                """;

        DynamicExtractionResult result = extractorService.extractWithoutTemplate(ocr);
        Map<String, Object> simple = result.toSimpleMap();

        assertEquals("2460.00", simple.get("amountTTC"));
        assertEquals("410.00", simple.get("tva"));
        assertEquals("2050.00", simple.get("amountHT"));
    }
}

