package com.invoice_reader.invoice_reader.vente.service.ocr;

import com.invoice_reader.invoice_reader.vente.service.VenteExtractionResult;
import com.invoice_reader.invoice_reader.vente.service.VenteFieldExtractorService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VenteOcrZoneParserTest {

    private final VenteFieldExtractorService extractorService = new VenteFieldExtractorService();

    @Test
    void extractWithoutTemplate_givenZonedText_whenExtract_thenFindCoreFields() {
        String ocr = """
                [HEADER]
                Facture N° FAC-2026-001
                Date facturation : 01/02/2026
                CLIENT DEMO
                [BODY]
                Ligne article test
                [FOOTER]
                ICE : 001234567890123
                IF : 1234567
                RC : 765432
                Total HT : 100,00
                Total TVA 20% 20,00
                Total TTC 120,00
                """;

        VenteExtractionResult result = extractorService.extractWithoutTemplate(ocr);
        Map<String, Object> simple = result.toSimpleMap();

        assertTrue(simple.containsKey("invoiceNumber"));
        assertTrue(simple.containsKey("amountHT"));
        assertTrue(simple.containsKey("amountTTC"));
        assertFalse(result.getExtractedFields().isEmpty());
    }

    @Test
    void extractWithoutTemplate_givenBlankText_whenExtract_thenReturnNoExtractedFields() {
        VenteExtractionResult result = extractorService.extractWithoutTemplate(" ");

        assertEquals(0, result.getExtractedCount());
    }

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

        VenteExtractionResult result = extractorService.extractWithoutTemplate(ocr);
        Map<String, Object> simple = result.toSimpleMap();

        assertEquals("BL-2026-77", simple.get("invoiceNumber"));
        assertEquals("07/03/2026", simple.get("invoiceDate"));
        assertEquals("100.00", simple.get("amountHT"));
        assertEquals("20.00", simple.get("tva"));
        assertEquals("120.00", simple.get("amountTTC"));
    }
}
