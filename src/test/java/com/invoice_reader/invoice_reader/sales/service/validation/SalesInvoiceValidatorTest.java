package com.invoice_reader.invoice_reader.sales.service.validation;

import com.invoice_reader.invoice_reader.sales.dto.SalesExtractionContext;
import com.invoice_reader.invoice_reader.sales.entity.SalesInvoiceStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SalesInvoiceValidatorTest {

    private final SalesInvoiceValidator validator = new SalesInvoiceValidator();

    @Test
    void determineStatus_givenBlankOcr_whenDetermineStatus_thenReturnError() {
        // Given
        SalesExtractionContext ctx = SalesExtractionContext.builder().build();

        // When
        SalesInvoiceStatus status = validator.determineStatus(ctx, " ");

        // Then
        assertEquals(SalesInvoiceStatus.ERROR, status);
    }

    @Test
    void determineStatus_givenCompleteContext_whenDetermineStatus_thenReturnExtractedAndNoMissing() {
        // Given
        SalesExtractionContext ctx = SalesExtractionContext.builder()
                .clientIce("001234567890123")
                .clientName("ATLAS SARL")
                .invoiceNumber("FAC-2026-001")
                .invoiceDate("01/02/2026")
                .totalHt(1000.0)
                .totalTva(200.0)
                .totalTtc(1200.0)
                .build();

        // When
        SalesInvoiceStatus status = validator.determineStatus(ctx, "OCR");

        // Then
        assertEquals(SalesInvoiceStatus.EXTRACTED, status);
        assertTrue(ctx.getMissingFields().isEmpty());
    }

    @Test
    void determineStatus_givenPartialContext_whenDetermineStatus_thenReturnExtractedAndMissingFields() {
        // Given
        SalesExtractionContext ctx = SalesExtractionContext.builder()
                .clientIce("001234567890123")
                .build();

        // When
        SalesInvoiceStatus status = validator.determineStatus(ctx, "OCR");

        // Then
        assertEquals(SalesInvoiceStatus.EXTRACTED, status);
        assertTrue(ctx.getMissingFields().contains("clientName"));
        assertTrue(ctx.getMissingFields().contains("invoiceNumber"));
        assertTrue(ctx.getMissingFields().contains("invoiceDate"));
        assertTrue(ctx.getMissingFields().contains("totalHt"));
        assertTrue(ctx.getMissingFields().contains("totalTva"));
        assertTrue(ctx.getMissingFields().contains("totalTtc"));
    }

    @Test
    void detectLowConfidenceFields_givenInvalidValues_whenDetect_thenReturnExpectedFlags() {
        // Given
        SalesExtractionContext ctx = SalesExtractionContext.builder()
                .clientIce("123")
                .totalHt(-10.0)
                .totalTva(20.0)
                .totalTtc(50.0)
                .avoir(false)
                .build();

        // When
        List<String> lowConf = validator.detectLowConfidenceFields(ctx);

        // Then
        assertTrue(lowConf.contains("clientIce"));
        assertTrue(lowConf.contains("totalHt"));
        assertTrue(lowConf.contains("totalTtc"));
    }

    @Test
    void detectLowConfidenceFields_givenAvoirWithNegativeHt_whenDetect_thenDoNotFlagTotalHt() {
        // Given
        SalesExtractionContext ctx = SalesExtractionContext.builder()
                .clientIce("001234567890123")
                .totalHt(-10.0)
                .totalTva(2.0)
                .totalTtc(-8.0)
                .avoir(true)
                .build();

        // When
        List<String> lowConf = validator.detectLowConfidenceFields(ctx);

        // Then
        assertFalse(lowConf.contains("totalHt"));
    }
}
