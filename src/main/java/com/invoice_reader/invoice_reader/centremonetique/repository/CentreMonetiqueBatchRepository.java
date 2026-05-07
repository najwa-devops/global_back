package com.invoice_reader.invoice_reader.centremonetique.repository;

import com.invoice_reader.invoice_reader.centremonetique.entity.CentreMonetiqueBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CentreMonetiqueBatchRepository extends JpaRepository<CentreMonetiqueBatch, Long> {
    List<CentreMonetiqueBatch> findTop200ByOrderByCreatedAtDesc();

    List<CentreMonetiqueBatch> findTop200ByDossierIdOrderByCreatedAtDesc(Long dossierId);

    List<CentreMonetiqueBatch> findByDossierIdOrderByCreatedAtDesc(Long dossierId);

    long countByDossierId(Long dossierId);

    long countByDossierIdAndClientValidatedTrue(Long dossierId);

    List<CentreMonetiqueBatch> findTop200ByDossierIdOrDossierIdIsNullOrderByCreatedAtDesc(Long dossierId);

    List<CentreMonetiqueBatch> findByRibAndStatusOrderByCreatedAtDesc(String rib, String status);

    Optional<CentreMonetiqueBatch> findByIdAndDossierId(Long id, Long dossierId);

    @Query("""
            SELECT b
            FROM CentreMonetiqueBatch b
            WHERE b.status = :status
              AND (
                    b.rib = :rib
                    OR (
                        UPPER(COALESCE(b.structure, '')) = 'AMEX'
                        AND b.rib IS NOT NULL
                        AND b.rib <> ''
                        AND LENGTH(b.rib) <= 5
                        AND :rib LIKE CONCAT('%', b.rib)
                    )
              )
            ORDER BY b.createdAt DESC, b.id DESC
            """)
    List<CentreMonetiqueBatch> findProcessedBatchesMatchingRibOrAmexSuffix(
            @Param("rib") String rib,
            @Param("status") String status);

    @Query(value = """
            SELECT
                b.id AS id,
                b.filename AS filename,
                b.original_name AS originalName,
                b.rib AS rib,
                b.status AS status,
                b.structure AS structure,
                b.statement_period AS statementPeriod,
                b.total_montant AS totalMontant,
                b.total_commission_ht AS totalCommissionHt,
                b.total_tva_sur_commissions AS totalTvaSurCommissions,
                b.solde_net_remise AS soldeNetRemise,
                b.total_debit AS totalDebit,
                b.total_credit AS totalCredit,
                b.transaction_count AS transactionCount,
                b.created_at AS createdAt,
                b.updated_at AS updatedAt,
                b.error_message AS errorMessage,
                b.client_validated AS clientValidated,
                b.client_validated_at AS clientValidatedAt,
                b.client_validated_by AS clientValidatedBy
            FROM cm_batch b
            ORDER BY b.created_at DESC, b.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<CentreMonetiqueBatchSummaryProjection> findLatestSummaries(@Param("limit") int limit);
}
