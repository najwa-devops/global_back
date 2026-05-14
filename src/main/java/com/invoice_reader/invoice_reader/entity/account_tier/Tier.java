package com.invoice_reader.invoice_reader.entity.account_tier;

import com.invoice_reader.invoice_reader.entity.auth.Dossier;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.Length;

import java.time.LocalDateTime;

/**
 * Entité Tier - Plan Tier avec gestion mode auxiliaire
 *
 * MODE AUXILIAIRE ACTIVÉ:
 *   - Compte collectif (342100000 ou 441100000) + Numéro tier (9 chiffres)
 *   - supplierAccount NON UTILISÉ
 *
 * MODE AUXILIAIRE DÉSACTIVÉ:
 *   - Numéro tier uniquement (9 chiffres)
 *   - collectifAccount et supplierAccount NON UTILISÉS
 *
 * VALIDATION 9 CHIFFRES:
 *   - tierNumber: TOUJOURS 9 chiffres
 *   - collectifAccount: 9 chiffres si mode auxiliaire activé
 *   - defaultChargeAccount: 9 chiffres
 *   - defaultChargeAccount2: 9 chiffres
 *   - tvaAccount: 9 chiffres
 *   - tvaAccount2: 9 chiffres
 */
@Entity
@Table(name = "tiers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tier_dossier_tier_number", columnNames = {"dossier_id", "tier_number"}),
                // @UniqueConstraint(name = "uk_tier_dossier_if_number", columnNames = {"dossier_id", "if_number"}),
                // @UniqueConstraint(name = "uk_tier_dossier_ice", columnNames = {"dossier_id", "ice"})
        },
        indexes = {
                @Index(name = "idx_tier_dossier", columnList = "dossier_id"),
                @Index(name = "idx_tier_if", columnList = "if_number"),
                @Index(name = "idx_tier_ice", columnList = "ice"),
                @Index(name = "idx_tier_tier_number", columnList = "tier_number"),
                @Index(name = "idx_tier_libelle", columnList = "libelle"),
                @Index(name = "idx_tier_active", columnList = "active"),
                @Index(name = "idx_tier_auxiliaire", columnList = "auxiliaire_mode")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id", nullable = false)
    private Dossier dossier;

    @Column(name = "dossier_id", insertable = false, updatable = false)
    private Long dossierId;

    // ===================== MODE AUXILIAIRE =====================

    @Column(name = "auxiliaire_mode", nullable = false)
    @Builder.Default
    private Boolean auxiliaireMode = false;

    @Column(name = "tier_number", nullable = false, length = 31)
    private String tierNumber;

    @Column(name = "code_tier", length = 80)
    private String codeTier;

    @Column(name = "collectif_account", length = 9)
    private String collectifAccount;

    // ===================== IDENTIFICATION =====================

    /**
     * Libellé du fournisseur (nom commercial)
     * Exemple: "MAROC TELECOM", "IAM", "AMENDIS"
     */
    @Column(name = "libelle", nullable = false, length = 200)
    @NotBlank(message = "Le libellé est obligatoire")
    private String libelle;

    @Column(name = "activity", length = 255)
    private String activity;

    // ===================== IDENTIFIANTS FISCAUX =====================

    /**
     * Identifiant Fiscal (IF) - 7 à 10 chiffres
     * Unique si renseigné
     */
    @Column(name = "if_number", length = 10)
    private String ifNumber;

    /**
     * Identifiant Commun de l'Entreprise (ICE) - 15 chiffres
     * Unique si renseigné
     */
    @Column(name = "ice", length = 15)
    private String ice;

    /**
     * Numéro de Registre de Commerce (RC)
     */
    @Column(name = "rc_number", length = 20)
    private String rcNumber;

    // ===================== CONFIGURATION COMPTABLE =====================

    /**
     * Compte de charge par défaut (classe 6)
     * Format: EXACTEMENT 9 chiffres
     * Exemple: "625100000"
     */
    @Column(name = "default_charge_account", length = 9)
    private String defaultChargeAccount;

    @Column(name = "default_charge_account_2", length = 9)
    private String defaultChargeAccount2;

    /**
     * Compte TVA récupérable (classe 3455)
     * Format: EXACTEMENT 9 chiffres
     * Exemple: "345510000"
     */
    @Column(name = "tva_account", length = 9)
    private String tvaAccount;

    @Column(name = "tva_account_2", length = 9)
    private String tvaAccount2;

    /**
     * Taux de TVA par défaut (en pourcentage)
     * Exemple: 20.0, 10.0, 14.0
     */
    @Column(name = "default_tva_rate")
    private Double defaultTvaRate;

    @Column(name = "default_tva_rate_2")
    private Double defaultTvaRate2;

    // ===================== STATUT ET AUDIT =====================

    /**
     * Statut actif/inactif
     */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    // ===================== LIFECYCLE CALLBACKS =====================

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();

        if (this.active == null) {
            this.active = true;
        }

        if (this.auxiliaireMode == null) {
            this.auxiliaireMode = false;
        }

        // Normaliser les identifiants fiscaux
        normalizeIdentifiers();

        // Validation mode auxiliaire
        validateAuxiliaireMode();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        normalizeIdentifiers();
        validateAuxiliaireMode();
    }

    // ===================== MÉTHODES MÉTIER =====================

    /**
     * Normalise les identifiants fiscaux (supprime les espaces)
     */
    private void normalizeIdentifiers() {
        this.ice = normalizeOptionalCode(this.ice);
        this.ifNumber = normalizeOptionalCode(this.ifNumber);
        this.rcNumber = normalizeOptionalCode(this.rcNumber);
        this.codeTier = normalizeOptionalCode(this.codeTier);
        this.collectifAccount = normalizeOptionalCode(this.collectifAccount);
        this.defaultChargeAccount2 = normalizeOptionalCode(this.defaultChargeAccount2);
        this.tvaAccount2 = normalizeOptionalCode(this.tvaAccount2);

        if (this.tierNumber != null) {
            this.tierNumber = this.tierNumber.replaceAll("\\s+", "").trim().toUpperCase();
            if (this.tierNumber.isEmpty()) {
                this.tierNumber = null;
            }
        }
    }

    private String normalizeOptionalCode(String value) {
        if (value == null) return null;
        String normalized = value.replaceAll("\\s+", "").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Validation du mode auxiliaire
     */
    private void validateAuxiliaireMode() {
        if (Boolean.TRUE.equals(this.auxiliaireMode)) {
            if (this.collectifAccount == null || this.collectifAccount.isBlank()) {
                throw new IllegalStateException(
                        "Mode auxiliaire activé: le compte collectif est obligatoire");
            }
            if (!this.collectifAccount.matches("^(342100000|441100000)$")) {
                throw new IllegalStateException(
                        "Le compte collectif doit être '342100000' ou '441100000'");
            }
        }

        // tierNumber toujours obligatoire
        if (this.tierNumber == null || this.tierNumber.isBlank()) {
            throw new IllegalStateException("Le numéro de tier est obligatoire");
        }

        if (Boolean.TRUE.equals(this.auxiliaireMode)) {
            if (this.codeTier == null || this.codeTier.isBlank()) {
                throw new IllegalStateException("Mode auxiliaire activé: le code tier est obligatoire");
            }
        }

        if (this.tierNumber.length() > 31) {  // Limite max définie dans @Column
            throw new IllegalStateException(
                    "Le numéro de tier ne peut pas dépasser 31 caractères");
        }

        // Vérifier que le tierNumber contient au moins des caractères valides
        if (!this.tierNumber.matches("^[A-Z0-9\\-]+$")) {
            throw new IllegalStateException(
                    "Le numéro de tier ne peut contenir que des lettres majuscules, chiffres et tirets");
        }
    }

    /**
     * Vérifie si le tier a un ICE
     */
    public boolean hasIce() {
        return this.ice != null && !this.ice.isBlank() && this.ice.matches("^\\d{15}$");
    }

    /**
     * Vérifie si le tier a un IF
     */
    public boolean hasIf() {
        return this.ifNumber != null && !this.ifNumber.isBlank()
                && this.ifNumber.matches("^\\d{7,10}$");
    }

    /**
     * Retourne le type d'identifiant principal
     */
    public String getIdentifierType() {
        if (hasIce()) return "ICE";
        if (hasIf()) return "IF";
        return "NONE";
    }

    /**
     * Retourne l'identifiant principal (priorité: ICE > IF)
     */
    public String getPrimaryIdentifier() {
        if (hasIce()) return this.ice;
        if (hasIf()) return this.ifNumber;
        return null;
    }

    /**
     * Vérifie si le tier a une configuration comptable complète
     */
    public boolean hasAccountingConfiguration() {
        return hasAccountingPair(this.defaultChargeAccount, this.tvaAccount)
                || hasAccountingPair(this.defaultChargeAccount2, this.tvaAccount2);
    }

    /**
     * Vérifie si le tier a une configuration comptable COMPLÈTE
     */
    public boolean hasFullAccountingConfiguration() {
        return hasAccountingConfiguration()
                && (this.defaultTvaRate != null || this.defaultTvaRate2 != null);
    }

    /**
     * Retourne un résumé de la configuration comptable
     */
    public String getAccountingConfigSummary() {
        if (!hasAccountingConfiguration()) {
            return "Non configuré";
        }
        StringBuilder sb = new StringBuilder();
        if (hasAccountingPair(this.defaultChargeAccount, this.tvaAccount)) {
            sb.append(String.format("HT1: %s | TVA1: %s",
                    this.defaultChargeAccount, this.tvaAccount));
            if (this.defaultTvaRate != null) {
                sb.append(String.format(" | Taux1: %.1f%%", this.defaultTvaRate));
            }
        }
        if (hasAccountingPair(this.defaultChargeAccount2, this.tvaAccount2)) {
            if (sb.length() > 0) {
                sb.append(" || ");
            }
            sb.append(String.format("HT2: %s | TVA2: %s",
                    this.defaultChargeAccount2, this.tvaAccount2));
            if (this.defaultTvaRate2 != null) {
                sb.append(String.format(" | Taux2: %.1f%%", this.defaultTvaRate2));
            }
        }
        return sb.toString();
    }

    /**
     * Retourne le compte pour affichage (selon mode)
     */
    public String getDisplayAccount() {
        if (Boolean.TRUE.equals(this.auxiliaireMode)) {
            return this.collectifAccount + " / " + this.tierNumber;
        }
        return this.tierNumber;
    }

    /**
     * Vérifie la validité des comptes comptables (9 chiffres)
     */
    public boolean areAccountsValid() {
        boolean valid = this.tierNumber != null && this.tierNumber.matches("^\\d{9}$");

        if (Boolean.TRUE.equals(this.auxiliaireMode)) {
            valid = valid && this.collectifAccount != null
                    && this.collectifAccount.matches("^\\d{9}$");
        }

        if (this.defaultChargeAccount != null && !this.defaultChargeAccount.isBlank()) {
            valid = valid && this.defaultChargeAccount.matches("^\\d{9}$");
        }

        if (this.defaultChargeAccount2 != null && !this.defaultChargeAccount2.isBlank()) {
            valid = valid && this.defaultChargeAccount2.matches("^\\d{9}$");
        }

        if (this.tvaAccount != null && !this.tvaAccount.isBlank()) {
            valid = valid && this.tvaAccount.matches("^\\d{9}$");
        }

        if (this.tvaAccount2 != null && !this.tvaAccount2.isBlank()) {
            valid = valid && this.tvaAccount2.matches("^\\d{9}$");
        }

        return valid;
    }

    /**
     * Retourne un résumé du tier
     */
    public String getSummary() {
        return String.format("Tier[id=%d, libelle='%s', tier='%s', mode=%s, type='%s']",
                this.id,
                this.libelle,
                this.tierNumber,
                this.auxiliaireMode ? "AUXILIAIRE" : "NORMAL",
                getIdentifierType());
    }

    /**
     * Désactive le tier
     */
    public void deactivate() {
        this.active = false;
    }

    /**
     * Active le tier
     */
    public void activate() {
        this.active = true;
    }

    public boolean hasTvaConfiguration() {
        return (tvaAccount != null && !tvaAccount.isBlank() && defaultTvaRate != null)
                || (tvaAccount2 != null && !tvaAccount2.isBlank() && defaultTvaRate2 != null);
    }

    public String getTvaDisplayFormat() {
        if (!hasTvaConfiguration()) {
            return "Aucune TVA configurée";
        }
        if (tvaAccount != null && !tvaAccount.isBlank() && defaultTvaRate != null) {
            return String.format("%s (TVA %.0f%%)", tvaAccount, defaultTvaRate);
        }
        return String.format("%s (TVA %.0f%%)", tvaAccount2, defaultTvaRate2);
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

    private boolean hasAccountingPair(String chargeAccount, String tvaAccount) {
        return chargeAccount != null && !chargeAccount.isBlank()
                && tvaAccount != null && !tvaAccount.isBlank();
    }
}

