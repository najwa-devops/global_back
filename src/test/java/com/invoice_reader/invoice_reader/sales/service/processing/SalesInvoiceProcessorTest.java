package com.invoice_reader.invoice_reader.sales.service.processing;

import com.invoice_reader.invoice_reader.entity.invoice.InvoiceStatus;
import com.invoice_reader.invoice_reader.sales.entity.SalesInvoice;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesInvoiceProcessorTest {

    @Test
    void calculateTtc_givenAmountHtAndTva_whenCompute_thenReturnSum() {
        SalesInvoice invoice = new SalesInvoice();
        Map<String, Object> fields = new HashMap<>();
        fields.put("amountHT", "100.00");
        fields.put("tva", "20.00");
        invoice.setFieldsData(fields);

        assertEquals(120.0, invoice.calculateTTC());
    }

    @Test
    void isTtcConsistent_givenMismatch_whenCheck_thenReturnFalse() {
        SalesInvoice invoice = new SalesInvoice();
        Map<String, Object> fields = new HashMap<>();
        fields.put("amountHT", 100.0);
        fields.put("tva", 20.0);
        fields.put("amountTTC", 130.0);
        invoice.setFieldsData(fields);

        assertFalse(invoice.isTTCConsistent());
        assertEquals(10.0, invoice.getTTCDifference());
    }

    @Test
    void validate_givenInvalidStatus_whenValidate_thenThrowException() {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setStatus(InvoiceStatus.TREATED);

        assertThrows(IllegalStateException.class, () -> invoice.validate("tester"));
    }

    @Test
    void validate_givenReadyToValidateStatus_whenValidate_thenUpdateAuditFields() {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setStatus(InvoiceStatus.READY_TO_VALIDATE);

        invoice.validate("tester");

        assertEquals(InvoiceStatus.VALIDATED, invoice.getStatus());
        assertEquals("tester", invoice.getValidatedBy());
        assertTrue(invoice.getValidatedAt() != null);
    }
}
