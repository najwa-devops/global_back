package com.invoice_reader.invoice_reader.banking_services.banking_parser;

import com.invoice_reader.invoice_reader.banking_entity.BankTransaction;

import java.util.List;

public interface TransactionParser {
    List<BankTransaction> parse(String text, Integer statementYear);
}
