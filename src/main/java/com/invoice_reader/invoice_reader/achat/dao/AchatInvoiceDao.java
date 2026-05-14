package com.invoice_reader.invoice_reader.achat.dao;

import com.invoice_reader.invoice_reader.achat.entity.AchatInvoice;
import com.invoice_reader.invoice_reader.database.entity.invoice.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.sql.Date;

/**
 * Repository pour les factures dynamiques.
 */
@Repository
public interface AchatInvoiceDao extends JpaRepository<AchatInvoice, Long> {

    /**
     * Trouve par nom de fichier.
     */
    Optional<AchatInvoice> findByFilename(String filename);

    @Query("SELECT CASE WHEN COUNT(i) > 0 THEN true ELSE false END " +
            "FROM AchatInvoice i " +
            "WHERE i.dossierId = :dossierId " +
            "AND (" +
            "LOWER(TRIM(i.filename)) = LOWER(TRIM(:filename)) " +
            "OR LOWER(TRIM(i.originalName)) = LOWER(TRIM(:filename))" +
            ")")
    boolean existsDuplicateFilenameInSameDossier(@Param("dossierId") Long dossierId, @Param("filename") String filename);

    /**
     * Trouve par statut.
     */
    List<AchatInvoice> findByStatusOrderByCreatedAtDesc(InvoiceStatus status);

    /**
     * Trouve par template.
     */
    List<AchatInvoice> findByTemplateIdOrderByCreatedAtDesc(Long templateId);

    List<AchatInvoice> findByDossierIdOrderByCreatedAtDesc(Long dossierId);
    List<AchatInvoice> findByStatusAndDossierIdOrderByCreatedAtDesc(InvoiceStatus status, Long dossierId);
    List<AchatInvoice> findByTemplateIdAndDossierIdOrderByCreatedAtDesc(Long templateId, Long dossierId);
    List<AchatInvoice> findByDossierIdAndClientValidatedTrueOrderByCreatedAtDesc(Long dossierId);
    List<AchatInvoice> findByStatusAndDossierIdAndClientValidatedTrueOrderByCreatedAtDesc(InvoiceStatus status, Long dossierId);
    List<AchatInvoice> findByTemplateIdAndDossierIdAndClientValidatedTrueOrderByCreatedAtDesc(Long templateId, Long dossierId);
    List<AchatInvoice> findByDossier_Comptable_IdOrderByCreatedAtDesc(Long comptableId);
    List<AchatInvoice> findByDossier_Client_IdOrderByCreatedAtDesc(Long clientId);

    List<AchatInvoice> findByStatusAndDossier_Comptable_IdOrderByCreatedAtDesc(InvoiceStatus status, Long comptableId);
    List<AchatInvoice> findByTemplateIdAndDossier_Comptable_IdOrderByCreatedAtDesc(Long templateId, Long comptableId);
    List<AchatInvoice> findByStatusAndDossier_Client_IdOrderByCreatedAtDesc(InvoiceStatus status, Long clientId);
    List<AchatInvoice> findByTemplateIdAndDossier_Client_IdOrderByCreatedAtDesc(Long templateId, Long clientId);

    /**
     * Factures en attente de validation.
     */
    @Query("SELECT i FROM AchatInvoice i WHERE i.status = 'READY_TO_VALIDATE' ORDER BY i.createdAt DESC")
    List<AchatInvoice> findReadyToValidate();

    /**
     * Factures traitées mais nécessitant révision (confiance < seuil).
     */
    @Query("SELECT i FROM AchatInvoice i WHERE i.status = 'TREATED' AND i.overallConfidence < :threshold ORDER BY i.overallConfidence ASC")
    List<AchatInvoice> findLowConfidence(@Param("threshold") Double threshold);

    @Query("SELECT i FROM AchatInvoice i WHERE i.status = 'TREATED' AND i.overallConfidence < :threshold AND i.dossier.comptable.id = :comptableId ORDER BY i.overallConfidence ASC")
    List<AchatInvoice> findLowConfidenceForComptable(@Param("threshold") Double threshold, @Param("comptableId") Long comptableId);

    @Query("SELECT i FROM AchatInvoice i WHERE i.status = 'TREATED' AND i.overallConfidence < :threshold AND i.dossier.client.id = :clientId ORDER BY i.overallConfidence ASC")
    List<AchatInvoice> findLowConfidenceForClient(@Param("threshold") Double threshold, @Param("clientId") Long clientId);

    @Query("SELECT i FROM AchatInvoice i WHERE i.status = 'TREATED' AND i.overallConfidence < :threshold AND i.dossier.id = :dossierId ORDER BY i.overallConfidence ASC")
    List<AchatInvoice> findLowConfidenceByDossierId(@Param("threshold") Double threshold, @Param("dossierId") Long dossierId);

    @Query("SELECT i FROM AchatInvoice i WHERE i.status = 'TREATED' AND i.overallConfidence < :threshold AND i.dossier.id = :dossierId AND i.clientValidated = true ORDER BY i.overallConfidence ASC")
    List<AchatInvoice> findLowConfidenceByDossierIdClientValidated(@Param("threshold") Double threshold, @Param("dossierId") Long dossierId);

    /**
     * Factures en erreur.
     */
    List<AchatInvoice> findByStatusOrderByUpdatedAtDesc(InvoiceStatus status);

    /**
     * Factures créées après une date.
     */
    List<AchatInvoice> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime date);

    /**
     * Compte par statut.
     */
    long countByStatus(InvoiceStatus status);

    /**
     * Compte par template.
     */
    long countByTemplateId(Long templateId);

    long countByDossier_Comptable_Id(Long comptableId);
    long countByDossier_Client_Id(Long clientId);
    long countByDossierId(Long dossierId);
    long countByStatusAndDossier_Comptable_Id(InvoiceStatus status, Long comptableId);
    long countByStatusAndDossier_Client_Id(InvoiceStatus status, Long clientId);
    long countByStatusAndDossierId(InvoiceStatus status, Long dossierId);
    long countByDossierIdAndClientValidatedTrue(Long dossierId);
    long countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus status, Long dossierId);

    /**
     * Recherche par numéro de facture (dans fieldsData).
     */
    // noinspection SqlResolve
    @Query(value = "SELECT * FROM dynamic_invoice " +
            "WHERE LOWER(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.invoiceNumber'))) " +
            "LIKE LOWER(CONCAT('%', :invoiceNumber, '%'))", nativeQuery = true)
    List<AchatInvoice> searchByInvoiceNumber(@Param("invoiceNumber") String invoiceNumber);

    @Query(value = "SELECT COUNT(*) FROM dynamic_invoice " +
            "WHERE dossier_id = :dossierId " +
            "AND LOWER(TRIM(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.invoiceNumber')))) = LOWER(TRIM(:invoiceNumber))", nativeQuery = true)
    long countByDossierIdAndInvoiceNumber(@Param("dossierId") Long dossierId, @Param("invoiceNumber") String invoiceNumber);

    @Query(value = "SELECT COUNT(*) FROM dynamic_invoice " +
            "WHERE dossier_id = :dossierId " +
            "AND id <> :invoiceId " +
            "AND LOWER(TRIM(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.invoiceNumber')))) = LOWER(TRIM(:invoiceNumber))", nativeQuery = true)
    long countOtherInvoicesByDossierIdAndInvoiceNumber(
            @Param("dossierId") Long dossierId,
            @Param("invoiceNumber") String invoiceNumber,
            @Param("invoiceId") Long invoiceId);

    /**
     * Recherche par ICE (dans fieldsData).
     */
    // noinspection SqlResolve
    @Query(value = "SELECT * FROM dynamic_invoice " +
            "WHERE JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.ice')) = :ice", nativeQuery = true)
    List<AchatInvoice> findByIce(@Param("ice") String ice);

    /**
     * Recherche par fournisseur (dans fieldsData).
     */
    // noinspection SqlResolve
    @Query(value = "SELECT * FROM dynamic_invoice " +
            "WHERE LOWER(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.supplier'))) " +
            "LIKE LOWER(CONCAT('%', :supplier, '%'))", nativeQuery = true)
    List<AchatInvoice> searchBySupplier(@Param("supplier") String supplier);

    // ===================== DÉTECTION DOUBLONS (OCR UPGRADE) =====================

    /**
     * Doublon certain : même N° facture + même ICE dans le même dossier (facture différente).
     * Stratégie 1 du DuplicateDetectionService.
     */
    // noinspection SqlResolve
    @Query(value = "SELECT id FROM dynamic_invoice " +
            "WHERE dossier_id = :dossierId " +
            "AND id <> :currentId " +
            "AND LOWER(TRIM(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.invoiceNumber')))) = LOWER(TRIM(:invoiceNumber)) " +
            "AND LOWER(TRIM(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.ice')))) = LOWER(TRIM(:ice)) " +
            "LIMIT 1",
            nativeQuery = true)
    Optional<Long> findCertainDuplicate(
            @Param("dossierId") Long dossierId,
            @Param("currentId") Long currentId,
            @Param("invoiceNumber") String invoiceNumber,
            @Param("ice") String ice);

    /**
     * Doublon probable : même TTC (±0.05) + même fournisseur + date dans une fenêtre ±7 jours.
     * Stratégie 2 du DuplicateDetectionService.
     * Si invoiceDate n'est pas parseable par STR_TO_DATE('%d/%m/%Y'), la ligne est ignorée silencieusement.
     */
    // noinspection SqlResolve
    @Query(value = "SELECT id FROM dynamic_invoice " +
            "WHERE dossier_id = :dossierId " +
            "AND id <> :currentId " +
            "AND ABS(CAST(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.amountTTC')) AS DECIMAL(15,2)) - :amountTTC) < 0.05 " +
            "AND LOWER(TRIM(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.supplier')))) = LOWER(TRIM(:supplier)) " +
            "AND STR_TO_DATE(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.invoiceDate')), '%d/%m/%Y') " +
            "    BETWEEN DATE_SUB(:invoiceDate, INTERVAL 7 DAY) AND DATE_ADD(:invoiceDate, INTERVAL 7 DAY) " +
            "LIMIT 1",
            nativeQuery = true)
    Optional<Long> findProbableDuplicate(
            @Param("dossierId") Long dossierId,
            @Param("currentId") Long currentId,
            @Param("amountTTC") Double amountTTC,
            @Param("supplier") String supplier,
            @Param("invoiceDate") Date invoiceDate);

    @Query(value = "SELECT COUNT(*) FROM dynamic_invoice " +
            "WHERE dossier_id = :dossierId " +
            "AND status = 'VALIDATED' " +
            "AND LOWER(TRIM(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.supplier')))) = LOWER(TRIM(:supplier)) " +
            "AND (" +
            "LOWER(TRIM(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.invoiceNumber')))) = LOWER(TRIM(:searchValue)) " +
            "OR LOWER(TRIM(filename)) = LOWER(TRIM(:searchValue)) " +
            "OR LOWER(TRIM(original_name)) = LOWER(TRIM(:searchValue))" +
            ")",
            nativeQuery = true)
    Long existsValidatedDuplicateBySupplierAndInvoiceOrFilename(
            @Param("dossierId") Long dossierId,
            @Param("supplier") String supplier,
            @Param("searchValue") String searchValue);
}
