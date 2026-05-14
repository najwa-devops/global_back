package com.invoice_reader.invoice_reader.banque.service;
import com.invoice_reader.invoice_reader.banque.entity.BanqueType;

import com.invoice_reader.invoice_reader.banque.entity.BanqueTransaction;
import com.invoice_reader.invoice_reader.banque.service.ocr.OcrCleaningService;
import com.invoice_reader.invoice_reader.banque.service.parser.TransactionParser;
import com.invoice_reader.invoice_reader.banque.service.universal.TransactionExtractionContext;
import com.invoice_reader.invoice_reader.banque.service.universal.UniversalTransactionExtractionEngine;
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
        BanqueDetector bankDetector = mock(BanqueDetector.class);
        HeaderFooterCleaner headerFooterCleaner = mock(HeaderFooterCleaner.class);
        TransactionParserFactory parserFactory = mock(TransactionParserFactory.class);
        UniversalTransactionExtractionEngine universalEngine = mock(UniversalTransactionExtractionEngine.class);
        TransactionParser parser = mock(TransactionParser.class);

        when(cleaningService.cleanOcrText(anyString())).thenReturn("cleaned text");
        when(headerFooterCleaner.removeHeaderFooter(anyString())).thenReturn("headerless text");
        when(bankDetector.detectBankType(anyString())).thenReturn(BanqueType.UNKNOWN);
        when(parserFactory.getParser(any())).thenReturn(parser);
        when(parser.parse(anyString(), anyInt())).thenReturn(List.of());

        BanqueTransaction recovered = new BanqueTransaction();
        recovered.setCredit(new BigDecimal("10.00"));

        when(universalEngine.extract(anyString(), any(TransactionExtractionContext.class)))
                .thenAnswer(invocation -> {
                    TransactionExtractionContext context = invocation.getArgument(1);
                    if (context.bankType() == BanqueType.BCP) {
                        return List.of();
                    }
                    if (context.bankType() == BanqueType.UNKNOWN) {
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

        List<BanqueTransaction> result = service.extractTransactions("ocr", 1, 2025, "BCP");

        assertEquals(1, result.size());
        assertEquals(recovered, result.get(0));
        verify(universalEngine).extract(anyString(), argThat(hasBankType(BanqueType.BCP)));
        verify(universalEngine).extract(anyString(), argThat(hasBankType(BanqueType.UNKNOWN)));
    }

    private TransactionExtractionContext argThat(ArgumentMatcher<TransactionExtractionContext> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }

    private ArgumentMatcher<TransactionExtractionContext> hasBankType(BanqueType bankType) {
        return context -> context != null && context.bankType() == bankType;
    }
}
