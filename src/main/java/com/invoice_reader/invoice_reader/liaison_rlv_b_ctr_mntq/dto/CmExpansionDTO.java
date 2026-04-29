package com.invoice_reader.invoice_reader.liaison_rlv_b_ctr_mntq.dto;

import java.util.List;

public record CmExpansionDTO(
        Long bankTransactionId,
        Long cmBatchId,
        String cmBatchOriginalName,
        String cmReference,
        String cmMontant,
        String commissionHt,
        String tvaSurCommissions,
        List<CmExpansionLineDTO> lines
) {}
