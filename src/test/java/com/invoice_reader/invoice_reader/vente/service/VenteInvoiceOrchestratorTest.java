package com.invoice_reader.invoice_reader.vente.service;

import com.invoice_reader.invoice_reader.database.entity.invoice.InvoiceStatus;
import com.invoice_reader.invoice_reader.vente.entity.VenteInvoice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VenteInvoiceOrchestratorTest {

    @Test
    void startProcessing_givenPendingStatus_whenStart_thenMoveToProcessing() {
        VenteInvoice invoice = new VenteInvoice();
        invoice.setStatus(InvoiceStatus.PENDING);

        invoice.startProcessing();

        assertEquals(InvoiceStatus.PROCESSING, invoice.getStatus());
    }

    @Test
    void markAsTreated_givenProcessingStatus_whenMark_thenMoveToTreated() {
        VenteInvoice invoice = new VenteInvoice();
        invoice.setStatus(InvoiceStatus.PROCESSING);

        invoice.markAsTreated();

        assertEquals(InvoiceStatus.TREATED, invoice.getStatus());
    }

    @Test
    void markAsError_givenAnyStatus_whenMark_thenMoveToError() {
        VenteInvoice invoice = new VenteInvoice();
        invoice.setStatus(InvoiceStatus.PENDING);

        invoice.markAsError();

        assertEquals(InvoiceStatus.ERROR, invoice.getStatus());
    }
}
