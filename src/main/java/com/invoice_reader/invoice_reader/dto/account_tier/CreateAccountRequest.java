package com.invoice_reader.invoice_reader.dto.account_tier;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateAccountRequest {

    @NotBlank(message = "Le code du compte est obligatoire")
    @Pattern(regexp = "^\\d{4,10}$", message = "Le code doit contenir entre 4 et 10 chiffres")
    private String code;

    @NotBlank(message = "Le libellé est obligatoire")
    @Size(min = 3, max = 200, message = "Le libellé doit contenir entre 3 et 200 caractères")
    private String libelle;

    @NotNull(message = "La classe est obligatoire")
    @Min(value = 1, message = "La classe doit être comprise entre 1 et 8")
    @Max(value = 8, message = "La classe doit être comprise entre 1 et 8")
    private Integer classe;

    @NotNull(message = "Le taux de TVA est obligatoire")
    @DecimalMin(value = "0.0", message = "Le taux de TVA doit être positif ou nul")
    @DecimalMax(value = "100.0", message = "Le taux de TVA ne peut pas dépasser 100%")
    private Double tvaRate;

    private Boolean active;
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

    private String createdBy;
    private String updatedBy;

    public void validate() {
        if (!isValidCode()) {
            throw new IllegalArgumentException("Le code du compte doit contenir entre 4 et 10 chiffres");
        }

        if (classe == null || classe < 1 || classe > 8) {
            throw new IllegalArgumentException("La classe doit être comprise entre 1 et 8");
        }

        if (tvaRate == null) {
            throw new IllegalArgumentException("Le taux de TVA est obligatoire");
        }

    }

    private boolean isValidCode() {
        return code != null && code.matches("^\\d{4,10}$");
    }

    private boolean hasTvaRate() {
        return tvaRate != null;
    }

    public String getSummary() {
        if (hasTvaRate()) {
            return String.format("Account[code=%s, libelle='%s', tvaRate=%.0f%%]",
                    code, libelle, tvaRate);
        }
        return String.format("Account[code=%s, libelle='%s']", code, libelle);
    }
}

