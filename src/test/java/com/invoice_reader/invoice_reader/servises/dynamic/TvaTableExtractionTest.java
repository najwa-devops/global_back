package com.invoice_reader.invoice_reader.servises.dynamic;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test pour l'extraction des montants depuis les tableaux de TVA
 * Cas des factures type SOREMED avec structure:
 * | TAUX | MONTANT TTC | DONT TAXE | MONTANT HT |
 * | 20   | 6866.94     | 1144.49   | 5722.45    |
 */
@Slf4j
class TvaTableExtractionTest {

    private DynamicFieldExtractorService extractor;

    @BeforeEach
    void setUp() {
        extractor = new DynamicFieldExtractorService();
    }

    @Test
    void testExtractHtFromTvaTable_SOREMED_Format() {
        // Texte OCR simulé d'une facture SOREMED
        String ocrText = """
                DESIGNATION          C.A PERIODE   REMISE    CA NET. TTC
                SPECIALITES à 20%         6212.03     0.000      6212.03
                PARAPHARMACIE à 20%        654.91     0.000       654.91
                LAIT à 0% ART .91         1968.40     0.000      1968.40
                VETERINAIRES à 0%           83.47     0.000        83.47
                MED A 0% MARGE 33        55549.26     0.000     55549.26
                MED A 0% MARGE 29         3633.48     0.000      3633.48
                
                TOTAUX               68101.55      0.00     68101.55
                
                TAUX | MONTANT T.T.C | DONT TAXE | MONTANT H.T
                TVA  |                |           |
                00   | 61234.61       | 0.00      | 61234.61
                07   | 0.00           | 0.00      | 0.00
                20   | 6866.94        | 1144.49   | 5722.45
                10   | 0.00           | 0.00      | 0.00
                
                68101.55            1144.49      66957.06
                NET A PAYER: 68101.55
                """;

        // Utilisation de la réflexion pour tester la méthode privée
        try {
            java.lang.reflect.Method method = DynamicFieldExtractorService.class
                    .getDeclaredMethod("extractHtFromTvaTable", String.class);
            method.setAccessible(true);
            
            Double htExtracted = (Double) method.invoke(extractor, ocrText);
            
            log.info("HT extrait depuis tableau TVA: {}", htExtracted);
            
            // Le HT total devrait être: 61234.61 + 0.00 + 5722.45 + 0.00 = 66957.06
            assertNotNull(htExtracted, "HT devrait être extrait du tableau TVA");
            assertEquals(66957.06, htExtracted, 0.01, "Le total HT devrait être 66957.06");
            
        } catch (Exception e) {
            fail("Erreur lors de l'extraction HT: " + e.getMessage());
        }
    }

    @Test
    void testExtractTvaFromTvaTable_SOREMED_Format() {
        String ocrText = """
                TAUX | MONTANT T.T.C | DONT TAXE | MONTANT H.T
                TVA  |                |           |
                00   | 61234.61       | 0.00      | 61234.61
                07   | 0.00           | 0.00      | 0.00
                20   | 6866.94        | 1144.49   | 5722.45
                10   | 0.00           | 0.00      | 0.00
                """;

        try {
            java.lang.reflect.Method method = DynamicFieldExtractorService.class
                    .getDeclaredMethod("extractTvaFromTvaTable", String.class);
            method.setAccessible(true);
            
            Double tvaExtracted = (Double) method.invoke(extractor, ocrText);
            
            log.info("TVA extraite depuis tableau TVA: {}", tvaExtracted);
            
            // La TVA totale devrait être: 0.00 + 0.00 + 1144.49 + 0.00 = 1144.49
            assertNotNull(tvaExtracted, "TVA devrait être extraite du tableau TVA");
            assertEquals(1144.49, tvaExtracted, 0.01, "Le total TVA devrait être 1144.49");
            
        } catch (Exception e) {
            fail("Erreur lors de l'extraction TVA: " + e.getMessage());
        }
    }

    @Test
    void testExtractHtFromTvaTable_WithCommas() {
        // Test avec des nombres utilisant des virgules comme séparateur décimal
        String ocrText = """
                TAUX | MONTANT T.T.C | DONT TAXE | MONTANT H.T
                20   | 1.234,56      | 205,76    | 1.028,80
                10   | 567,89        | 51,63     | 516,26
                """;

        try {
            java.lang.reflect.Method method = DynamicFieldExtractorService.class
                    .getDeclaredMethod("extractHtFromTvaTable", String.class);
            method.setAccessible(true);
            
            Double htExtracted = (Double) method.invoke(extractor, ocrText);
            
            log.info("HT extrait (format virgule): {}", htExtracted);
            
            // 1028.80 + 516.26 = 1545.06
            assertNotNull(htExtracted, "HT devrait être extrait même avec virgules");
            assertEquals(1545.06, htExtracted, 0.01, "Le total HT devrait être 1545.06");
            
        } catch (Exception e) {
            fail("Erreur lors de l'extraction HT avec virgules: " + e.getMessage());
        }
    }

    @Test
    void testExtractHtFromTvaTable_NoTable() {
        // Test avec un texte qui ne contient pas de tableau TVA
        String ocrText = """
                FACTURE N° 12345
                Total HT: 1000.00
                TVA 20%: 200.00
                Total TTC: 1200.00
                """;

        try {
            java.lang.reflect.Method method = DynamicFieldExtractorService.class
                    .getDeclaredMethod("extractHtFromTvaTable", String.class);
            method.setAccessible(true);
            
            Double htExtracted = (Double) method.invoke(extractor, ocrText);
            
            assertNull(htExtracted, "HT devrait être null quand il n'y a pas de tableau TVA");
            
        } catch (Exception e) {
            fail("Erreur lors du test sans tableau: " + e.getMessage());
        }
    }

    @Test
    void testNormalizeAmountString() {
        try {
            java.lang.reflect.Method method = DynamicFieldExtractorService.class
                    .getDeclaredMethod("normalizeAmountString", String.class);
            method.setAccessible(true);

            // Test avec espaces
            String result1 = (String) method.invoke(extractor, "1 234,56");
            assertEquals("1234.56", result1);

            // Test avec point
            String result2 = (String) method.invoke(extractor, "1234.56");
            assertEquals("1234.56", result2);

            // Test avec espaces et virgule
            String result3 = (String) method.invoke(extractor, "1 234 , 56");
            assertEquals("1234.56", result3);

            // Test vide
            String result4 = (String) method.invoke(extractor, "");
            assertEquals("0.00", result4);

        } catch (Exception e) {
            fail("Erreur lors du test de normalisation: " + e.getMessage());
        }
    }
}
