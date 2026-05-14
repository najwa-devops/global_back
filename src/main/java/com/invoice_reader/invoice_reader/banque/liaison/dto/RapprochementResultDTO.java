package com.invoice_reader.invoice_reader.banque.liaison.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RapprochementResultDTO {

    private Long batchId;
    private String batchRib;
    private int totalCmTransactions;
    private int matchedCount;
    private List<RapprochementMatchDTO> matches;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RapprochementMatchDTO {
        private String date;
        private String cmReference;
        private String cmMontant;
        private String cmStan;
        private String cmType;
        private String cmMontantTransaction;
        private String bankStatementName;
        private String bankMontant;
        private String bankLibelle;
    }
}
