package com.invoice_reader.invoice_reader.achat.dto;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateAchatTemplateRequest {

    private String templateName;       // Renommer le template
    private String supplierType;       // Changer le type de fournisseur
    private String description;        // Mettre à jour la description

    @Valid
    private List<CreateAchatTemplateRequest.FieldDefinitionRequest> fieldDefinitions;

    @Valid
    private CreateAchatTemplateRequest.FixedSupplierDataRequest fixedSupplierData;
}
