package com.invoice_reader.invoice_reader.banking_services.banking_universal;

import com.invoice_reader.invoice_reader.banking_entity.BankTransaction;

public interface TransactionConfidenceScorer {
    int score(BankTransaction transaction);
}

