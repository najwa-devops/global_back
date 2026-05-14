package com.invoice_reader.invoice_reader.banque.service.parser;

import com.invoice_reader.invoice_reader.banque.entity.BanqueTransaction;

import java.util.List;

public interface TransactionParser {
    List<BanqueTransaction> parse(String text, Integer statementYear);
}
