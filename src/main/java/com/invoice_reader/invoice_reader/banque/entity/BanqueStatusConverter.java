package com.invoice_reader.invoice_reader.banque.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class BanqueStatusConverter implements AttributeConverter<BanqueStatus, String> {

    @Override
    public String convertToDatabaseColumn(BanqueStatus attribute) {
        return attribute != null ? attribute.name() : null;
    }

    @Override
    public BanqueStatus convertToEntityAttribute(String dbData) {
        BanqueStatus mapped = BanqueStatus.fromExternalValue(dbData);
        return mapped != null ? mapped : BanqueStatus.PENDING;
    }
}
