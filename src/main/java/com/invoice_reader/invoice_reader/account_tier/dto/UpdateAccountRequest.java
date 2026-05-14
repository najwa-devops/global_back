package com.invoice_reader.invoice_reader.account_tier.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateAccountRequest {

    /**
     * Nouveau libellé du compte
     */
    @Size(min = 3, max = 200, message = "Le libellé doit contenir entre 3 et 200 caractères")
    private String libelle;

    /**
     * Statut actif ou non
     */
    private Boolean active;

    /**
     * Taux de TVA
     */
    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Le taux de TVA doit être positif ou nul")
    @jakarta.validation.constraints.DecimalMax(value = "100.0", message = "Le taux de TVA ne peut pas dépasser 100%")
    private Double tvaRate;

    private String xCom;
    private Integer delai;
    private String ville;
    private String adresse;
    private String activite;
    private Integer cdClt;
    private Integer cdFrs;
    private String typeCmpt;
    private Integer numcat;
    private String idF;
    private String cod;
    private String cnss;
    private String tp;
    private String ice;
    private String rc;
    private String rib;
    private String tva;
    private String taxCode;
    private String charge;

    /**
     * Utilisateur qui modifie
     */
    private String updatedBy;
}

