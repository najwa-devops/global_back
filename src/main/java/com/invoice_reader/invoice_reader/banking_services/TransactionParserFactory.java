package com.invoice_reader.invoice_reader.banking_services;

import com.invoice_reader.invoice_reader.banking_services.banking_parser.AttijariwafaTransactionParser;
import com.invoice_reader.invoice_reader.banking_services.banking_parser.BcpTransactionParser;
import com.invoice_reader.invoice_reader.banking_services.banking_parser.DateOpDateValTransactionParser;
import com.invoice_reader.invoice_reader.banking_services.banking_parser.LibelleDateValTransactionParser;
import com.invoice_reader.invoice_reader.banking_services.banking_parser.StandardTransactionParser;
import com.invoice_reader.invoice_reader.banking_services.banking_parser.TransactionParser;
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

    public TransactionParser getParser(BankType bankType) {
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
