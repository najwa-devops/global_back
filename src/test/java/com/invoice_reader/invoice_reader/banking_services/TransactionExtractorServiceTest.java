package com.invoice_reader.invoice_reader.banking_services;

import com.invoice_reader.invoice_reader.banking_entity.BankTransaction;
import com.invoice_reader.invoice_reader.banking_services.banking_ocr.OcrCleaningService;
import com.invoice_reader.invoice_reader.banking_services.banking_parser.TransactionParser;
import com.invoice_reader.invoice_reader.banking_services.banking_universal.TransactionExtractionContext;
import com.invoice_reader.invoice_reader.banking_services.banking_universal.UniversalTransactionExtractionEngine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionExtractorServiceTest {

    @Test
    void extractTransactions_whenManualBcpReturnsZero_fallsBackToDetectedStrategy() {
        OcrCleaningService cleaningService = mock(OcrCleaningService.class);
        BankDetector bankDetector = mock(BankDetector.class);
        HeaderFooterCleaner headerFooterCleaner = mock(HeaderFooterCleaner.class);
        TransactionParserFactory parserFactory = mock(TransactionParserFactory.class);
        UniversalTransactionExtractionEngine universalEngine = mock(UniversalTransactionExtractionEngine.class);
        TransactionParser parser = mock(TransactionParser.class);

        when(cleaningService.cleanOcrText(anyString())).thenReturn("cleaned text");
        when(headerFooterCleaner.removeHeaderFooter(anyString())).thenReturn("headerless text");
        when(bankDetector.detectBankType(anyString())).thenReturn(BankType.UNKNOWN);
        when(parserFactory.getParser(any())).thenReturn(parser);
        when(parser.parse(anyString(), anyInt())).thenReturn(List.of());

        BankTransaction recovered = new BankTransaction();
        recovered.setCredit(new BigDecimal("10.00"));

        when(universalEngine.extract(anyString(), any(TransactionExtractionContext.class)))
                .thenAnswer(invocation -> {
                    TransactionExtractionContext context = invocation.getArgument(1);
                    if (context.bankType() == BankType.BCP) {
                        return List.of();
                    }
                    if (context.bankType() == BankType.UNKNOWN) {
                        return List.of(recovered);
                    }
                    return List.of();
                });

        TransactionExtractorService service = new TransactionExtractorService(
                cleaningService,
                bankDetector,
                headerFooterCleaner,
                parserFactory,
                universalEngine);

        List<BankTransaction> result = service.extractTransactions("ocr", 1, 2025, "BCP");

        assertEquals(1, result.size());
        assertEquals(recovered, result.get(0));
        verify(universalEngine).extract(anyString(), argThat(hasBankType(BankType.BCP)));
        verify(universalEngine).extract(anyString(), argThat(hasBankType(BankType.UNKNOWN)));
    }

    private TransactionExtractionContext argThat(ArgumentMatcher<TransactionExtractionContext> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }

    private ArgumentMatcher<TransactionExtractionContext> hasBankType(BankType bankType) {
        return context -> context != null && context.bankType() == bankType;
    }
}
