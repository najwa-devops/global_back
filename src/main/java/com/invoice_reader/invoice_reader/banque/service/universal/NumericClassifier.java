package com.invoice_reader.invoice_reader.banque.service.universal;

import java.math.BigDecimal;
import java.util.List;

public interface NumericClassifier {
    NumericClassification classify(List<String> blockLines, String description, TransactionExtractionContext context);

    record NumericClassification(
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal balance,
            List<String> flags) {
    }
}

