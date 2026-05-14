package com.invoice_reader.invoice_reader.banque.service.universal;

import com.invoice_reader.invoice_reader.banque.entity.BanqueType;
import com.invoice_reader.invoice_reader.banque.service.ocr.OcrCleaningService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmartNumericClassifierTest {

    @Test
    void classify_keepsFraisSurRemiseChequeAsDebit() {
        SmartNumericClassifier classifier = new SmartNumericClassifier(
                new OcrCleaningService(),
                new DefaultBanqueLayoutProfileRegistry());

        NumericClassifier.NumericClassification result = classifier.classify(
                List.of("01/02 01/02 FRAIS SUR REMISE CHEQUE 022026 11,00"),
                "FRAIS SUR REMISE CHEQUE 022026",
                new TransactionExtractionContext(BanqueType.BCP, 2, 2026));

        assertEquals(new BigDecimal("11.00"), result.debit());
        assertEquals(BigDecimal.ZERO, result.credit());
    }

    @Test
    void classify_keepsFraisSurRemiseAsDebit() {
        SmartNumericClassifier classifier = new SmartNumericClassifier(
                new OcrCleaningService(),
                new DefaultBanqueLayoutProfileRegistry());

        NumericClassifier.NumericClassification result = classifier.classify(
                List.of("27/02 27/02 FRAIS SUR REMISE 11,00"),
                "FRAIS SUR REMISE",
                new TransactionExtractionContext(BanqueType.BCP, 2, 2026));

        assertEquals(new BigDecimal("11.00"), result.debit());
        assertEquals(BigDecimal.ZERO, result.credit());
    }
}
