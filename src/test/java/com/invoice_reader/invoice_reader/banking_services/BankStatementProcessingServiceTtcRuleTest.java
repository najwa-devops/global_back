package com.invoice_reader.invoice_reader.banking_services;

import com.invoice_reader.invoice_reader.banking_entity.BankStatement;
import com.invoice_reader.invoice_reader.banking_entity.BankTransaction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BankStatementProcessingServiceTtcRuleTest {

    @Test
    void applyTtcCommissionSplitIfEnabled_splitsCommissionAndKeepsStatementLink() throws Exception {
        BankStatementProcessingService service = newService();

        BankStatement statement = new BankStatement();
        statement.setId(42L);
        statement.setApplyTtcRule(true);

        BankTransaction commissionTx = new BankTransaction();
        commissionTx.setStatement(statement);
        commissionTx.setLibelle("FRAIS COMMISSION CARTE");
        commissionTx.setDebit(new BigDecimal("110.00"));
        commissionTx.setCredit(BigDecimal.ZERO);
        commissionTx.setSens("DEBIT");

        List<BankTransaction> result = invokeSplit(service, statement, List.of(commissionTx));

        assertEquals(2, result.size());
        assertSame(statement, result.get(0).getStatement());
        assertEquals(new BigDecimal("100.00"), result.get(0).getDebit());
        assertEquals("FRAIS COMMISSION CARTE", result.get(0).getLibelle());
        assertEquals(new BigDecimal("10.00"), result.get(1).getDebit());
        assertEquals("COMMISSION", result.get(1).getLibelle());
        assertSame(statement, result.get(1).getStatement());
    }

    private static BankStatementProcessingService newService() throws Exception {
        Constructor<BankStatementProcessingService> constructor =
                BankStatementProcessingService.class.getDeclaredConstructor(
                        BankStatementProcessor.class,
                        com.invoice_reader.invoice_reader.banking_services.banking_ocr.OcrCleaningService.class,
                        MetadataExtractorService.class,
                        TransactionExtractorService.class,
                        BankTransactionAccountLearningService.class,
                        BankStatementValidatorService.class,
                        com.invoice_reader.invoice_reader.banking_repository.BankStatementRepository.class,
                        com.invoice_reader.invoice_reader.banking_repository.BankTransactionRepository.class
                );
        constructor.setAccessible(true);
        return constructor.newInstance(null, null, null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<BankTransaction> invokeSplit(
            BankStatementProcessingService service,
            BankStatement statement,
            List<BankTransaction> txs) throws Exception {
        Method method = BankStatementProcessingService.class.getDeclaredMethod(
                "applyTtcCommissionSplitIfEnabled",
                BankStatement.class,
                List.class
        );
        method.setAccessible(true);
        return (List<BankTransaction>) method.invoke(service, statement, txs);
    }
}
