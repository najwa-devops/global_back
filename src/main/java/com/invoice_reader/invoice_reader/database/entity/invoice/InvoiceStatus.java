package com.invoice_reader.invoice_reader.database.entity.invoice;

public enum InvoiceStatus {
    PENDING,
    PROCESSING,
    TREATED,
    RECALCULATED,
    OUT_OF_PERIOD,
    DUPLICATE,
    ACCOUNTED,
    READY_TO_VALIDATE,
    VALIDATED,
    ERROR

}
