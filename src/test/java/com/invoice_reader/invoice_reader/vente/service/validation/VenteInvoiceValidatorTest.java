package com.invoice_reader.invoice_reader.vente.service.validation;

import com.invoice_reader.invoice_reader.vente.dto.VenteExtractionContext;
import com.invoice_reader.invoice_reader.vente.entity.VenteInvoiceStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VenteInvoiceValidatorTest {

    private final VenteInvoiceValidator validator = new VenteInvoiceValidator();

    @Test
    void determineStatus_givenBlankOcr_whenDetermineStatus_thenReturnError() {
        // Given
        VenteExtractionContext ctx = VenteExtractionContext.builder().build();

        // When
        VenteInvoiceStatus status = validator.determineStatus(ctx, " ");

        // Then
        assertEquals(VenteInvoiceStatus.ERROR, status);
    }

    @Test
    void determineStatus_givenCompleteContext_whenDetermineStatus_thenReturnExtractedAndNoMissing() {
        // Given
        VenteExtractionContext ctx = VenteExtractionContext.builder()
                .clientIce("001234567890123")
                .clientName("ATLAS SARL")
                .invoiceNumber("FAC-2026-001")
                .invoiceDate("01/02/2026")
                .totalHt(1000.0)
                .totalTva(200.0)
                .totalTtc(1200.0)
                .build();

        // When
        VenteInvoiceStatus status = validator.determineStatus(ctx, "OCR");

        // Then
        assertEquals(VenteInvoiceStatus.EXTRACTED, status);
        assertTrue(ctx.getMissingFields().isEmpty());
    }

    @Test
    void determineStatus_givenPartialContext_whenDetermineStatus_thenReturnExtractedAndMissingFields() {
        // Given
        VenteExtractionContext ctx = VenteExtractionContext.builder()
                .clientIce("001234567890123")
                .build();

        // When
        VenteInvoiceStatus status = validator.determineStatus(ctx, "OCR");

        // Then
        assertEquals(VenteInvoiceStatus.EXTRACTED, status);
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
        VenteExtractionContext ctx = VenteExtractionContext.builder()
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
        VenteExtractionContext ctx = VenteExtractionContext.builder()
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
