package com.invoice_reader.invoice_reader.banque.service.universal;

import com.invoice_reader.invoice_reader.banque.entity.BanqueTransaction;

public interface TransactionConfidenceScorer {
    int score(BanqueTransaction transaction);
}

