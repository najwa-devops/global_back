package com.invoice_reader.invoice_reader.sales.service;

import com.invoice_reader.invoice_reader.entity.invoice.InvoiceStatus;
import com.invoice_reader.invoice_reader.sales.entity.SalesInvoice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SalesInvoiceOrchestratorTest {

    @Test
    void startProcessing_givenPendingStatus_whenStart_thenMoveToProcessing() {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setStatus(InvoiceStatus.PENDING);

        invoice.startProcessing();

        assertEquals(InvoiceStatus.PROCESSING, invoice.getStatus());
    }

    @Test
    void markAsTreated_givenProcessingStatus_whenMark_thenMoveToTreated() {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setStatus(InvoiceStatus.PROCESSING);

        invoice.markAsTreated();

        assertEquals(InvoiceStatus.TREATED, invoice.getStatus());
    }

    @Test
    void markAsError_givenAnyStatus_whenMark_thenMoveToError() {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setStatus(InvoiceStatus.PENDING);

        invoice.markAsError();

        assertEquals(InvoiceStatus.ERROR, invoice.getStatus());
    }
}
