package com.invoice_reader.invoice_reader.banque.service.universal;

import java.util.List;

public interface TransactionBlockBuilder {
    List<TransactionBlock> buildBlocks(String text, TransactionExtractionContext context);
}

