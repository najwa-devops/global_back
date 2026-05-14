package com.invoice_reader.invoice_reader.banque.service.universal;

import com.invoice_reader.invoice_reader.banque.entity.BanqueTransaction;

import java.util.List;

public interface UniversalTransactionExtractionEngine {
    List<BanqueTransaction> extract(String cleanedText, TransactionExtractionContext context);
}

