package com.invoice_reader.invoice_reader.banque.service;

import com.invoice_reader.invoice_reader.banque.entity.BanqueReleve;
import com.invoice_reader.invoice_reader.banque.entity.BanqueTransaction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BanqueReleveProcessingServiceTtcRuleTest {

    @Test
    void applyTtcCommissionSplitIfEnabled_splitsCommissionAndKeepsStatementLink() throws Exception {
        BanqueReleveProcessingService service = newService();

        BanqueReleve statement = new BanqueReleve();
        statement.setId(42L);
        statement.setApplyTtcRule(true);

        // Le libellé doit commencer par "COMMISSION" pour que containsCommission() matche
        // (pattern : ^\\s*COMM?ISSIONS?\\b)
        BanqueTransaction commissionTx = new BanqueTransaction();
        commissionTx.setStatement(statement);
        commissionTx.setLibelle("COMMISSION FRAIS CARTE");
        commissionTx.setDebit(new BigDecimal("110.00"));
        commissionTx.setCredit(BigDecimal.ZERO);
        commissionTx.setSens("DEBIT");

        List<BanqueTransaction> result = invokeSplit(service, statement, List.of(commissionTx));

        // Le split TTC crée 3 lignes : HT (charge), TVA, et ligne banque principale (contrepartie)
        assertEquals(3, result.size());

        // Ligne 1 : montant HT → 110 / 1.1 = 100.00
        assertEquals("COMMISSION HT", result.get(0).getLibelle());
        assertEquals(new BigDecimal("100.00"), result.get(0).getDebit());
        assertSame(statement, result.get(0).getStatement());

        // Ligne 2 : TVA → 110 - 100 = 10.00
        assertEquals("TVA SUR COMMISSION", result.get(1).getLibelle());
        assertEquals(new BigDecimal("10.00"), result.get(1).getDebit());
        assertSame(statement, result.get(1).getStatement());

        // Ligne 3 : transaction d'origine transformée en contrepartie banque (crédit = TTC)
        assertEquals("COMMISSION FRAIS CARTE", result.get(2).getLibelle());
        assertEquals(new BigDecimal("110.00"), result.get(2).getCredit());
        assertEquals(BigDecimal.ZERO.setScale(2), result.get(2).getDebit());
        assertSame(statement, result.get(2).getStatement());
    }

    private static BanqueReleveProcessingService newService() throws Exception {
        Constructor<BanqueReleveProcessingService> constructor =
                BanqueReleveProcessingService.class.getDeclaredConstructor(
                        BanqueReleveProcessor.class,
                        com.invoice_reader.invoice_reader.banque.service.ocr.OcrCleaningService.class,
                        MetadataExtractorService.class,
                        TransactionExtractorService.class,
                        BanqueTransactionAccountLearningService.class,
                        BanqueReleveValidatorService.class,
                        com.invoice_reader.invoice_reader.banque.repository.BanqueReleveRepository.class,
                        com.invoice_reader.invoice_reader.banque.repository.BanqueTransactionRepository.class
                );
        constructor.setAccessible(true);
        return constructor.newInstance(null, null, null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<BanqueTransaction> invokeSplit(
            BanqueReleveProcessingService service,
            BanqueReleve statement,
            List<BanqueTransaction> txs) throws Exception {
        Method method = BanqueReleveProcessingService.class.getDeclaredMethod(
                "applyTtcCommissionSplitIfEnabled",
                BanqueReleve.class,
                List.class
        );
        method.setAccessible(true);
        return (List<BanqueTransaction>) method.invoke(service, statement, txs);
    }
}
