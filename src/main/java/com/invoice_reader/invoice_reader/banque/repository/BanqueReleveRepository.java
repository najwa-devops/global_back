package com.invoice_reader.invoice_reader.banque.repository;

import com.invoice_reader.invoice_reader.banque.entity.BanqueReleve;
import com.invoice_reader.invoice_reader.banque.entity.ContinuityStatus;
import com.invoice_reader.invoice_reader.banque.entity.BanqueStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BanqueReleveRepository extends JpaRepository<BanqueReleve, Long> {

    // ==================== RECHERCHE PAR RIB ====================

    List<BanqueReleve> findByRibOrderByYearDescMonthDesc(String rib);

    Page<BanqueReleve> findByRib(String rib, Pageable pageable);

    long countByRib(String rib);

    long countByDossierId(Long dossierId);

    long countByDossierIdAndClientValidatedTrue(Long dossierId);

    long countByClientValidatedTrue();

    long countByDossierIdAndRib(Long dossierId, String rib);

    @Query("SELECT COUNT(s) FROM BanqueReleve s WHERE s.rib = :rib AND (s.dossierId = :dossierId OR s.dossierId IS NULL)")
    long countByRibInDossierOrLegacy(@Param("rib") String rib, @Param("dossierId") Long dossierId);

    @Query("""
                SELECT DISTINCT s.rib
                FROM BanqueReleve s
                WHERE s.rib IS NOT NULL
                  AND s.rib <> ''
                  AND s.rib LIKE CONCAT('%', :suffix)
            """)
    List<String> findDistinctRibsEndingWith(@Param("suffix") String suffix);

    // ==================== RECHERCHE PAR PÉRIODE ====================

    List<BanqueReleve> findByYearAndMonthOrderByRib(Integer year, Integer month);

    List<BanqueReleve> findByYearOrderByMonthDesc(Integer year);

    Optional<BanqueReleve> findByRibAndYearAndMonth(String rib, Integer year, Integer month);
    List<BanqueReleve> findAllByRibAndYearAndMonthOrderByCreatedAtDescIdDesc(String rib, Integer year, Integer month);

    Optional<BanqueReleve> findByDuplicateHash(String duplicateHash);
    List<BanqueReleve> findAllByDuplicateHashOrderByCreatedAtDescIdDesc(String duplicateHash);

    // ==================== RECHERCHE PAR STATUT ====================

    List<BanqueReleve> findByStatusOrderByCreatedAtDesc(BanqueStatus status);

    Page<BanqueReleve> findByStatus(BanqueStatus status, Pageable pageable);

    long countByStatus(BanqueStatus status);

    long countByDossierIdAndStatus(Long dossierId, BanqueStatus status);

    List<BanqueReleve> findByDossierIdOrderByCreatedAtDesc(Long dossierId);

    List<BanqueReleve> findByDossierIdAndStatusOrderByCreatedAtDesc(Long dossierId, BanqueStatus status);

    List<BanqueReleve> findByContinuityStatusIn(List<ContinuityStatus> statuses);

    // ==================== LISTE COMPLÈTE ====================

    @Query("SELECT s FROM BanqueReleve s ORDER BY s.createdAt DESC")
    List<BanqueReleve> findAllOrderByCreatedAtDesc();
    Optional<BanqueReleve> findFirstByFilenameOrderByIdDesc(String filename);

    Page<BanqueReleve> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
                SELECT s FROM BanqueReleve s
                WHERE s.year IS NOT NULL AND s.month IS NOT NULL
                ORDER BY s.year DESC, s.month DESC, s.createdAt DESC
            """)
    List<BanqueReleve> findAllWithPeriodOrderByYearMonthDesc();

    @Query("""
                SELECT s FROM BanqueReleve s
                WHERE s.year = :year AND s.month = :month
                ORDER BY s.createdAt DESC
            """)
    List<BanqueReleve> findByYearAndMonthOrderByCreatedAtDesc(@Param("year") Integer year, @Param("month") Integer month);

    List<BanqueReleve> findByDossierIdAndYearAndMonthOrderByCreatedAtDesc(Long dossierId, Integer year, Integer month);

    @Query("""
                SELECT DISTINCT s.year, s.month
                FROM BanqueReleve s
                WHERE s.year IS NOT NULL AND s.month IS NOT NULL
                ORDER BY s.year DESC, s.month DESC
            """)
    List<Object[]> findDistinctPeriods();

    // ==================== MOIS PRÉCÉDENT ====================

    // ==================== RELEVÉS INVALIDES ====================

    @Query("""
                SELECT s FROM BanqueReleve s
                WHERE s.isBalanceValid = false
                   OR s.isContinuityValid = false
                   OR s.errorTransactionCount > 0
                ORDER BY s.createdAt DESC
            """)
    List<BanqueReleve> findInvalidStatements();

    // ==================== STATISTIQUES ====================

    @Query("SELECT COUNT(DISTINCT s.rib) FROM BanqueReleve s")
    long countDistinctRibs();

    @Query("""
                SELECT AVG(s.overallConfidence)
                FROM BanqueReleve s
                WHERE s.status = :status
                  AND s.overallConfidence IS NOT NULL
            """)
    Double averageConfidenceByStatus(@Param("status") BanqueStatus status);

    // ==================== RECHERCHE AVANCÉE ====================

    @Query("""
                SELECT s FROM BanqueReleve s
                WHERE (:rib IS NULL OR s.rib = :rib)
                  AND (:year IS NULL OR s.year = :year)
                  AND (:month IS NULL OR s.month = :month)
                  AND (:status IS NULL OR s.status = :status)
                ORDER BY s.year DESC, s.month DESC
            """)
    Page<BanqueReleve> search(
            @Param("rib") String rib,
            @Param("year") Integer year,
            @Param("month") Integer month,
            @Param("status") BanqueStatus status,
            Pageable pageable);
}
