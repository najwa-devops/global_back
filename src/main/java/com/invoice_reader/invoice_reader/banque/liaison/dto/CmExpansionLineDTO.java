package com.invoice_reader.invoice_reader.banque.liaison.dto;

public record CmExpansionLineDTO(
        String date,
        String stan,
        String dcFlag,
        String montant
) {}
