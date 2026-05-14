package com.invoice_reader.invoice_reader.database.entity.invoice;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class InvoiceStatusConverter implements AttributeConverter<InvoiceStatus, String> {

    @Override
    public String convertToDatabaseColumn(InvoiceStatus attribute) {
        return attribute != null ? attribute.name() : InvoiceStatus.PENDING.name();
    }

    @Override
    public InvoiceStatus convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return InvoiceStatus.PENDING;
        }
        try {
            return InvoiceStatus.valueOf(dbData.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return InvoiceStatus.PENDING;
        }
    }
}
