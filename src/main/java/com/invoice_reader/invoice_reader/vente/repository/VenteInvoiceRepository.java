package com.invoice_reader.invoice_reader.vente.repository;

import com.invoice_reader.invoice_reader.database.entity.invoice.InvoiceStatus;
import com.invoice_reader.invoice_reader.vente.entity.VenteInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository pour les factures de vente.
 * Copie de AchatInvoiceDao — table: sales_invoice
 */
@Repository
public interface VenteInvoiceRepository extends JpaRepository<VenteInvoice, Long> {

    Optional<VenteInvoice> findByFilename(String filename);

    @Query("SELECT CASE WHEN COUNT(i) > 0 THEN true ELSE false END " +
            "FROM VenteInvoice i " +
            "WHERE i.dossierId = :dossierId " +
            "AND (" +
            "LOWER(TRIM(i.filename)) = LOWER(TRIM(:filename)) " +
            "OR LOWER(TRIM(i.originalName)) = LOWER(TRIM(:filename))" +
            ")")
    boolean existsDuplicateFilenameInSameDossier(@Param("dossierId") Long dossierId, @Param("filename") String filename);

    List<VenteInvoice> findByStatusOrderByCreatedAtDesc(InvoiceStatus status);

    List<VenteInvoice> findByTemplateIdOrderByCreatedAtDesc(Long templateId);

    List<VenteInvoice> findByDossierIdOrderByCreatedAtDesc(Long dossierId);
    List<VenteInvoice> findByStatusAndDossierIdOrderByCreatedAtDesc(InvoiceStatus status, Long dossierId);
    List<VenteInvoice> findByTemplateIdAndDossierIdOrderByCreatedAtDesc(Long templateId, Long dossierId);
    List<VenteInvoice> findByDossierIdAndClientValidatedTrueOrderByCreatedAtDesc(Long dossierId);
    List<VenteInvoice> findByStatusAndDossierIdAndClientValidatedTrueOrderByCreatedAtDesc(InvoiceStatus status, Long dossierId);
    List<VenteInvoice> findByTemplateIdAndDossierIdAndClientValidatedTrueOrderByCreatedAtDesc(Long templateId, Long dossierId);
    List<VenteInvoice> findByDossier_Comptable_IdOrderByCreatedAtDesc(Long comptableId);
    List<VenteInvoice> findByDossier_Client_IdOrderByCreatedAtDesc(Long clientId);

    List<VenteInvoice> findByStatusAndDossier_Comptable_IdOrderByCreatedAtDesc(InvoiceStatus status, Long comptableId);
    List<VenteInvoice> findByTemplateIdAndDossier_Comptable_IdOrderByCreatedAtDesc(Long templateId, Long comptableId);
    List<VenteInvoice> findByStatusAndDossier_Client_IdOrderByCreatedAtDesc(InvoiceStatus status, Long clientId);
    List<VenteInvoice> findByTemplateIdAndDossier_Client_IdOrderByCreatedAtDesc(Long templateId, Long clientId);

    @Query("SELECT i FROM VenteInvoice i WHERE i.status = 'READY_TO_VALIDATE' ORDER BY i.createdAt DESC")
    List<VenteInvoice> findReadyToValidate();

    @Query("SELECT i FROM VenteInvoice i WHERE i.status = 'TREATED' AND i.overallConfidence < :threshold ORDER BY i.overallConfidence ASC")
    List<VenteInvoice> findLowConfidence(@Param("threshold") Double threshold);

    @Query("SELECT i FROM VenteInvoice i WHERE i.status = 'TREATED' AND i.overallConfidence < :threshold AND i.dossier.comptable.id = :comptableId ORDER BY i.overallConfidence ASC")
    List<VenteInvoice> findLowConfidenceForComptable(@Param("threshold") Double threshold, @Param("comptableId") Long comptableId);

    @Query("SELECT i FROM VenteInvoice i WHERE i.status = 'TREATED' AND i.overallConfidence < :threshold AND i.dossier.client.id = :clientId ORDER BY i.overallConfidence ASC")
    List<VenteInvoice> findLowConfidenceForClient(@Param("threshold") Double threshold, @Param("clientId") Long clientId);

    @Query("SELECT i FROM VenteInvoice i WHERE i.status = 'TREATED' AND i.overallConfidence < :threshold AND i.dossier.id = :dossierId ORDER BY i.overallConfidence ASC")
    List<VenteInvoice> findLowConfidenceByDossierId(@Param("threshold") Double threshold, @Param("dossierId") Long dossierId);

    @Query("SELECT i FROM VenteInvoice i WHERE i.status = 'TREATED' AND i.overallConfidence < :threshold AND i.dossier.id = :dossierId AND i.clientValidated = true ORDER BY i.overallConfidence ASC")
    List<VenteInvoice> findLowConfidenceByDossierIdClientValidated(@Param("threshold") Double threshold, @Param("dossierId") Long dossierId);

    List<VenteInvoice> findByStatusOrderByUpdatedAtDesc(InvoiceStatus status);

    List<VenteInvoice> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime date);

    long countByStatus(InvoiceStatus status);

    long countByTemplateId(Long templateId);

    long countByDossier_Comptable_Id(Long comptableId);
    long countByDossier_Client_Id(Long clientId);
    long countByDossierId(Long dossierId);
    long countByStatusAndDossier_Comptable_Id(InvoiceStatus status, Long comptableId);
    long countByStatusAndDossier_Client_Id(InvoiceStatus status, Long clientId);
    long countByStatusAndDossierId(InvoiceStatus status, Long dossierId);
    long countByDossierIdAndClientValidatedTrue(Long dossierId);
    long countByStatusAndDossierIdAndClientValidatedTrue(InvoiceStatus status, Long dossierId);

    // noinspection SqlResolve
    @Query(value = "SELECT * FROM sales_invoice " +
            "WHERE LOWER(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.invoiceNumber'))) " +
            "LIKE LOWER(CONCAT('%', :invoiceNumber, '%'))", nativeQuery = true)
    List<VenteInvoice> searchByInvoiceNumber(@Param("invoiceNumber") String invoiceNumber);

    @Query(value = "SELECT COUNT(*) FROM sales_invoice " +
            "WHERE dossier_id = :dossierId " +
            "AND LOWER(TRIM(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.invoiceNumber')))) = LOWER(TRIM(:invoiceNumber))", nativeQuery = true)
    long countByDossierIdAndInvoiceNumber(@Param("dossierId") Long dossierId, @Param("invoiceNumber") String invoiceNumber);

    @Query(value = "SELECT COUNT(*) FROM sales_invoice " +
            "WHERE dossier_id = :dossierId " +
            "AND id <> :invoiceId " +
            "AND LOWER(TRIM(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.invoiceNumber')))) = LOWER(TRIM(:invoiceNumber))", nativeQuery = true)
    long countOtherInvoicesByDossierIdAndInvoiceNumber(
            @Param("dossierId") Long dossierId,
            @Param("invoiceNumber") String invoiceNumber,
            @Param("invoiceId") Long invoiceId);

    // noinspection SqlResolve
    @Query(value = "SELECT * FROM sales_invoice " +
            "WHERE JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.ice')) = :ice", nativeQuery = true)
    List<VenteInvoice> findByIce(@Param("ice") String ice);

    // noinspection SqlResolve
    @Query(value = "SELECT * FROM sales_invoice " +
            "WHERE LOWER(JSON_UNQUOTE(JSON_EXTRACT(fields_data, '$.supplier'))) " +
            "LIKE LOWER(CONCAT('%', :supplier, '%'))", nativeQuery = true)
    List<VenteInvoice> searchBySupplier(@Param("supplier") String supplier);
}
