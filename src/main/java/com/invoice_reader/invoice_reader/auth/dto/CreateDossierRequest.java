package com.invoice_reader.invoice_reader.auth.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateDossierRequest {
    private Long clientId;
    private String clientUsername;
    private String clientPassword;
    private String clientDisplayName;
    private String dossierName;
    private Long comptableId;
    private LocalDate exerciseStartDate;
    private LocalDate exerciseEndDate;
}
