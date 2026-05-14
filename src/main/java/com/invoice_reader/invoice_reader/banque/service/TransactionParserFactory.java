package com.invoice_reader.invoice_reader.banque.service;
import com.invoice_reader.invoice_reader.banque.entity.BanqueType;

import com.invoice_reader.invoice_reader.banque.service.parser.AttijariwafaTransactionParser;
import com.invoice_reader.invoice_reader.banque.service.parser.BcpTransactionParser;
import com.invoice_reader.invoice_reader.banque.service.parser.DateOpDateValTransactionParser;
import com.invoice_reader.invoice_reader.banque.service.parser.LibelleDateValTransactionParser;
import com.invoice_reader.invoice_reader.banque.service.parser.StandardTransactionParser;
import com.invoice_reader.invoice_reader.banque.service.parser.TransactionParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransactionParserFactory {

    private final StandardTransactionParser standardParser;
    private final BcpTransactionParser bcpParser;
    private final AttijariwafaTransactionParser attijariwafaParser;
    private final DateOpDateValTransactionParser dateOpDateValParser;
    private final LibelleDateValTransactionParser libelleDateValParser;

    public TransactionParser getParser(BanqueType bankType) {
        if (bankType == null) {
            return standardParser;
        }
        return switch (bankType) {
            case BCP -> bcpParser;
            case CREDIT_DU_MAROC, BMCI, CIH, SOCIETE_GENERALE, SAHAM_BANK, BARID_BANK -> dateOpDateValParser;
            case ATTIJARIWAFA -> attijariwafaParser;
            case CREDIT_AGRICOLE, BMCE -> libelleDateValParser;
            default -> standardParser;
        };
    }
}
