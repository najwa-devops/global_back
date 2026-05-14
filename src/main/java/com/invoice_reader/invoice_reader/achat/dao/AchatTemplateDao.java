package com.invoice_reader.invoice_reader.achat.dao;

import com.invoice_reader.invoice_reader.database.entity.template.SignatureType;
import com.invoice_reader.invoice_reader.achat.entity.AchatTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AchatTemplateDao extends JpaRepository<AchatTemplate, Long> {

    /**
     * Trouve un template par sa signature (ICE, IF ou SUPPLIER)
     */
    @Query("SELECT t FROM AchatTemplate t WHERE t.signature.signatureType = :type AND t.signature.signatureValue = :value AND t.active = true")
    Optional<AchatTemplate> findBySignature(@Param("type") SignatureType type, @Param("value") String value);

    /**
     * Trouve un template par ICE
     */
    @Query("SELECT t FROM AchatTemplate t WHERE t.signature.signatureType = 'ICE' AND t.signature.signatureValue = :ice AND t.active = true")
    Optional<AchatTemplate> findByIce(@Param("ice") String ice);

    /**
     * Trouve un template par IF
     */
    @Query("SELECT t FROM AchatTemplate t WHERE t.signature.signatureType = 'IF' AND t.signature.signatureValue = :ifNumber AND t.active = true")
    Optional<AchatTemplate> findByIfNumber(@Param("ifNumber") String ifNumber);

    /**
     * Trouve un template par RC
     */
    @Query("SELECT t FROM AchatTemplate t WHERE t.signature.signatureType = 'RC' AND t.signature.signatureValue = :rc AND t.active = true")
    Optional<AchatTemplate> findByRc(@Param("rc") String rc);

    /**
     * Trouve un template par nom de fournisseur
     */
    @Query("SELECT t FROM AchatTemplate t WHERE t.signature.signatureType = 'SUPPLIER' AND LOWER(t.signature.signatureValue) = LOWER(:supplier) AND t.active = true")
    Optional<AchatTemplate> findBySupplierName(@Param("supplier") String supplier);

    /**
     * Trouve tous les templates par type de fournisseur
     */
    List<AchatTemplate> findBySupplierTypeAndActiveTrue(String supplierType);

    /**
     * Trouve tous les templates actifs
     */
    List<AchatTemplate> findByActiveTrueOrderByTemplateNameAsc();

    /**
     * Trouve les templates les plus utilisés
     */
    @Query("SELECT t FROM AchatTemplate t WHERE t.active = true ORDER BY t.usageCount DESC")
    List<AchatTemplate> findMostUsed();

    /**
     * Trouve les templates les plus fiables (taux de succès > 80%)
     */
    @Query("SELECT t FROM AchatTemplate t WHERE t.active = true AND t.usageCount >= 5 AND (t.successCount * 100.0 / t.usageCount) >= 80 ORDER BY t.successCount DESC")
    List<AchatTemplate> findReliableTemplates();

    /**
     * Vérifie si un template existe déjà pour cette signature
     */
    @Query("SELECT COUNT(t) > 0 FROM AchatTemplate t WHERE t.signature.signatureType = :type AND t.signature.signatureValue = :value")
    boolean existsBySignature(@Param("type") SignatureType type, @Param("value") String value);

    /**
     * Recherche par nom de template (partiel)
     */
    @Query("SELECT t FROM AchatTemplate t WHERE LOWER(t.templateName) LIKE LOWER(CONCAT('%', :name, '%')) AND t.active = true")
    List<AchatTemplate> searchByName(@Param("name") String name);

    /**
     * Recherche par nom de fournisseur dans fixedSupplierData (JSON)
     * Un fournisseur peut avoir plusieurs ICE/templates
     */
    @Query(value = "SELECT * FROM dynamic_template " +
            "WHERE active = true AND " +
            "LOWER(JSON_UNQUOTE(JSON_EXTRACT(fixed_supplier_data, '$.supplier'))) " +
            "LIKE LOWER(CONCAT('%', :supplier, '%'))", nativeQuery = true)
    List<AchatTemplate> searchBySupplierInFixedData(@Param("supplier") String supplier);

    /**
     * Trouve tous les templates d'un fournisseur par son nom exact
     */
    @Query(value = "SELECT * FROM dynamic_template " +
            "WHERE active = true AND " +
            "LOWER(JSON_UNQUOTE(JSON_EXTRACT(fixed_supplier_data, '$.supplier'))) = LOWER(:supplier)", nativeQuery = true)
    List<AchatTemplate> findAllBySupplierName(@Param("supplier") String supplier);

    /**
     * Vérifie si un template existe déjà pour cet ICE
     */
    @Query("SELECT COUNT(t) > 0 FROM AchatTemplate t WHERE t.signature.signatureType = 'ICE' AND t.signature.signatureValue = :ice")
    boolean existsByIce(@Param("ice") String ice);

    /**
     * Vérifie si un template existe déjà pour cet IF
     */
    @Query("SELECT COUNT(t) > 0 FROM AchatTemplate t WHERE t.signature.signatureType = 'IF' AND t.signature.signatureValue = :ifNumber")
    boolean existsByIfNumber(@Param("ifNumber") String ifNumber);

    /**
     * Vérifie si un template existe déjà pour ce RC
     */
    @Query("SELECT COUNT(t) > 0 FROM AchatTemplate t WHERE t.signature.signatureType = 'RC' AND t.signature.signatureValue = :rc")
    boolean existsByRc(@Param("rc") String rc);
}
