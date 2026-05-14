package com.invoice_reader.invoice_reader.banque.service.universal;

import com.invoice_reader.invoice_reader.banque.entity.BanqueType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoredTransactionBlockBuilderTest {

    @Test
    void buildBlocks_keepsHrcConsultingTransactionAndIgnoresStandaloneRcFooter() {
        ScoredTransactionBlockBuilder builder =
                new ScoredTransactionBlockBuilder(new DefaultBanqueLayoutProfileRegistry());

        String text = String.join("\n",
                "DATE OP DATE VALEUR LIBELLE DEBIT CREDIT",
                "01/02 01/02 PAIEMENT D'UN CHEQUE EN FAVEUR DE HRC CONSULTING 1.320,00",
                "02/02 02/02 VERSEMENT ESPECES 500,00",
                "RC 123456");

        List<TransactionBlock> blocks = builder.buildBlocks(
                text,
                new TransactionExtractionContext(BanqueType.BCP, 2, 2026));

        assertEquals(2, blocks.size());
        assertTrue(blocks.get(0).joinedText().contains("HRC CONSULTING"));
        assertTrue(blocks.stream().noneMatch(block -> block.joinedText().equals("RC 123456")));
    }
}
