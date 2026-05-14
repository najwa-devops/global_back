package com.invoice_reader.invoice_reader.dto.account_tier;

import com.invoice_reader.invoice_reader.entity.account_tier.Tier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO de réponse pour un Tier
 * Contient tous les champs + champs calculés
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TierDto {

    // ===================== IDENTIFICATION =====================

    private Long id;
    private Long dossierId;
    private String libelle;
    private String activity;

    // ===================== MODE AUXILIAIRE =====================

    /**
     * Mode auxiliaire activé/désactivé
     */
    private Boolean auxiliaireMode;

    /**
     * Numéro de tier (TOUJOURS présent)
     * 9 chiffres
     */
    private String tierNumber;

    /**
     * Code tier métier
     */
    private String codeTier;

    /**
     * Compte collectif (si mode auxiliaire)
     * 9 chiffres: "342100000" ou "441100000"
     */
    private String collectifAccount;

    /**
     * Compte pour affichage (calculé)
     *
     * Si mode auxiliaire: "441100000 / 000000123"
     * Si mode normal: "000000123"
     */
    private String displayAccount;

    // ===================== IDENTIFIANTS FISCAUX =====================

    private String ifNumber;
    private String ice;
    private String rcNumber;

    /**
     * Type d'identifiant principal: "ICE", "IF" ou "NONE"
     */
    private String identifierType;

    /**
     * Identifiant principal (ICE prioritaire sur IF)
     */
    private String primaryIdentifier;

    // ===================== CONFIGURATION COMPTABLE =====================

    /**
     * Compte de charge par défaut (9 chiffres)
     */
    private String defaultChargeAccount;
    private String defaultChargeAccount2;

    /**
     * Compte TVA (9 chiffres)
     */
    private String tvaAccount;
    private String tvaAccount2;

    /**
     * Taux de TVA par défaut
     */
    private Double defaultTvaRate;
    private Double defaultTvaRate2;

    /**
     * A une configuration comptable complète ?
     */
    private Boolean hasAccountingConfig;

    /**
     * Résumé de la configuration comptable
     */
    private String accountingConfigSummary;

    // ===================== STATUT ET AUDIT =====================

    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;

    // ===================== CONVERSION =====================

    private String tvaDisplayFormat;
    private Boolean hasTvaConfiguration;
    /**
     * Convertit une entité Tier en DTO
     */
    public static TierDto fromEntity(Tier tier) {
        if (tier == null) {
            return null;
        }

        return TierDto.builder()
                // Identification
                .id(tier.getId())
                .dossierId(tier.getDossierId())
                .libelle(tier.getLibelle())
                .activity(tier.getActivity())

                // Mode auxiliaire
                .auxiliaireMode(tier.getAuxiliaireMode())
                .tierNumber(tier.getTierNumber())
                .codeTier(tier.getCodeTier())
                .collectifAccount(tier.getCollectifAccount())
                .displayAccount(tier.getDisplayAccount())

                // Identifiants fiscaux
                .ifNumber(tier.getIfNumber())
                .ice(tier.getIce())
                .rcNumber(tier.getRcNumber())
                .identifierType(tier.getIdentifierType())
                .primaryIdentifier(tier.getPrimaryIdentifier())

                // Configuration comptable
                .defaultChargeAccount(tier.getDefaultChargeAccount())
                .defaultChargeAccount2(tier.getDefaultChargeAccount2())
                .tvaAccount(tier.getTvaAccount())
                .tvaAccount2(tier.getTvaAccount2())
                .defaultTvaRate(tier.getDefaultTvaRate())
                .defaultTvaRate2(tier.getDefaultTvaRate2())
                .tvaDisplayFormat(tier.getTvaDisplayFormat())
                .hasTvaConfiguration(tier.hasTvaConfiguration())
                .hasAccountingConfig(tier.hasAccountingConfiguration())
                .accountingConfigSummary(tier.getAccountingConfigSummary())

                // Statut et audit
                .active(tier.getActive())
                .createdAt(tier.getCreatedAt())
                .updatedAt(tier.getUpdatedAt())
                .createdBy(tier.getCreatedBy())
                .updatedBy(tier.getUpdatedBy())

                .build();
    }

    public String getShortSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(libelle);

        if (ifNumber != null && !ifNumber.isBlank()) {
            sb.append(" (IF: ").append(ifNumber).append(")");
        } else if (ice != null && !ice.isBlank()) {
            sb.append(" (ICE: ").append(ice).append(")");
        }

        return sb.toString();
    }

    public String getFullSummary() {
        return String.format(
                "Tier[id=%d, libelle='%s', tierNumber='%s', IF='%s', ICE='%s', tva='%s']",
                id, libelle, tierNumber, ifNumber, ice, tvaDisplayFormat
        );
    }

    public boolean hasAccountingConfiguration() {
        return (defaultChargeAccount != null && !defaultChargeAccount.isBlank()
                && tvaAccount != null && !tvaAccount.isBlank())
                || (defaultChargeAccount2 != null && !defaultChargeAccount2.isBlank()
                && tvaAccount2 != null && !tvaAccount2.isBlank());
    }

    public String getEffectiveChargeAccount() {
        if (defaultChargeAccount != null && !defaultChargeAccount.isBlank()) {
            return defaultChargeAccount;
        }
        return defaultChargeAccount2;
    }

    public String getEffectiveTvaAccount() {
        if (tvaAccount != null && !tvaAccount.isBlank()) {
            return tvaAccount;
        }
        return tvaAccount2;
    }

    public Double getEffectiveTvaRate() {
        if (defaultTvaRate != null) {
            return defaultTvaRate;
        }
        return defaultTvaRate2;
    }
}
