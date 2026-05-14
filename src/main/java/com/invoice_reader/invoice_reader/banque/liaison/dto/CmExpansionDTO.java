package com.invoice_reader.invoice_reader.banque.liaison.dto;

import java.util.List;

public record CmExpansionDTO(
        Long bankTransactionId,
        Long cmBatchId,
        String cmBatchOriginalName,
        String cmBatchStructure,
        String cmReference,
        String cmMontant,
        String commissionHt,
        String tvaSurCommissions,
        List<CmExpansionLineDTO> lines
) {}
