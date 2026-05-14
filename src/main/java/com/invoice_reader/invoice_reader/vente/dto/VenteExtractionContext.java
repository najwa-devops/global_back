package com.invoice_reader.invoice_reader.vente.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple value object holding fields extracted from a sales invoice.
 * Used during OCR/extraction to accumulate results and keep track of missing
 * or low confidence fields.  A small builder is provided by Lombok so tests
 * and services can create contexts succinctly.
 */
@Data
@Builder
public class VenteExtractionContext {
    private String clientIce;
    private String clientName;
    private String invoiceNumber;
    private String invoiceDate;
    private Double totalHt;
    private Double totalTva;
    private Double totalTtc;
    private Boolean avoir;

    /**
     * List of fields that were found to be missing during validation.
     * The validator will (re)populate this list every time determineStatus
     * is invoked.
     */
    @Builder.Default
    private List<String> missingFields = new ArrayList<>();
}
