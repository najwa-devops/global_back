package com.invoice_reader.invoice_reader.banking_services.banking_universal;

import com.invoice_reader.invoice_reader.banking_entity.BankTransaction;

import java.util.List;

public interface UniversalTransactionExtractionEngine {
    List<BankTransaction> extract(String cleanedText, TransactionExtractionContext context);
}

