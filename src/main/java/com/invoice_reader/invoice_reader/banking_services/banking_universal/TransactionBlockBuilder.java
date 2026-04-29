package com.invoice_reader.invoice_reader.banking_services.banking_universal;

import java.util.List;

public interface TransactionBlockBuilder {
    List<TransactionBlock> buildBlocks(String text, TransactionExtractionContext context);
}

