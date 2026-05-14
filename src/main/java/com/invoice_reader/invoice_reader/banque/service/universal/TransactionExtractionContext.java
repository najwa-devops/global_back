package com.invoice_reader.invoice_reader.banque.service.universal;

import com.invoice_reader.invoice_reader.banque.entity.BanqueType;

public record TransactionExtractionContext(
        BanqueType bankType,
        Integer statementMonth,
        Integer statementYear,
        boolean twoColumnAmountLayout) {

    /** Constructeur de compatibilité : layout par défaut = une colonne (comportement sûr). */
    public TransactionExtractionContext(BanqueType bankType, Integer statementMonth, Integer statementYear) {
        this(bankType, statementMonth, statementYear, false);
    }
}

