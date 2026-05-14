package com.invoice_reader.invoice_reader.vente.entity;

/**
 * Lightweight enum used by the sales module to describe the status of an
 * extraction/validation step.  It is intentionally minimal; production code
 * uses the more comprehensive {@code InvoiceStatus} on the entity side.
 */
public enum VenteInvoiceStatus {
    PENDING,
    EXTRACTED,
    ERROR
}
