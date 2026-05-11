package com.invoice_reader.invoice_reader.centremonetique.dto;

import com.invoice_reader.invoice_reader.liaison_rlv_b_ctr_mntq.dto.RapprochementResultDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CentreMonetiqueUploadResponseDTO {
    private String message;
    private CentreMonetiqueBatchDetailDTO batch;
    private List<CentreMonetiqueExtractionRow> rows;
    private RapprochementResultDTO rapprochement;
}
