package com.invoice_reader.invoice_reader.banking_repository;

import com.invoice_reader.invoice_reader.banking_entity.BankStatement;
import com.invoice_reader.invoice_reader.banking_entity.ContinuityStatus;
import com.invoice_reader.invoice_reader.banking_entity.BankStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BankStatementRepository extends JpaRepository<BankStatement, Long> {

    // ==================== RECHERCHE PAR RIB ====================

    List<BankStatement> findByRibOrderByYearDescMonthDesc(String rib);

    Page<BankStatement> findByRib(String rib, Pageable pageable);

    long countByRib(String rib);

    long countByDossierId(Long dossierId);

    long countByDossierIdAndRib(Long dossierId, String rib);

    @Query("SELECT COUNT(s) FROM BankStatement s WHERE s.rib = :rib AND (s.dossierId = :dossierId OR s.dossierId IS NULL)")
    long countByRibInDossierOrLegacy(@Param("rib") String rib, @Param("dossierId") Long dossierId);

    @Query("""
                SELECT DISTINCT s.rib
                FROM BankStatement s
                WHERE s.rib IS NOT NULL
                  AND s.rib <> ''
                  AND s.rib LIKE CONCAT('%', :suffix)
            """)
    List<String> findDistinctRibsEndingWith(@Param("suffix") String suffix);

    // ==================== RECHERCHE PAR PÉRIODE ====================

    List<BankStatement> findByYearAndMonthOrderByRib(Integer year, Integer month);

    List<BankStatement> findByYearOrderByMonthDesc(Integer year);

    Optional<BankStatement> findByRibAndYearAndMonth(String rib, Integer year, Integer month);
    List<BankStatement> findAllByRibAndYearAndMonthOrderByCreatedAtDescIdDesc(String rib, Integer year, Integer month);

    Optional<BankStatement> findByDuplicateHash(String duplicateHash);
    List<BankStatement> findAllByDuplicateHashOrderByCreatedAtDescIdDesc(String duplicateHash);

    // ==================== RECHERCHE PAR STATUT ====================

    List<BankStatement> findByStatusOrderByCreatedAtDesc(BankStatus status);

    Page<BankStatement> findByStatus(BankStatus status, Pageable pageable);

    long countByStatus(BankStatus status);

    long countByDossierIdAndStatus(Long dossierId, BankStatus status);

    List<BankStatement> findByDossierIdOrderByCreatedAtDesc(Long dossierId);

    List<BankStatement> findByDossierIdAndStatusOrderByCreatedAtDesc(Long dossierId, BankStatus status);

    List<BankStatement> findByContinuityStatusIn(List<ContinuityStatus> statuses);

    // ==================== LISTE COMPLÈTE ====================

    @Query("SELECT s FROM BankStatement s ORDER BY s.createdAt DESC")
    List<BankStatement> findAllOrderByCreatedAtDesc();
    Optional<BankStatement> findFirstByFilenameOrderByIdDesc(String filename);

    Page<BankStatement> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
                SELECT s FROM BankStatement s
                WHERE s.year IS NOT NULL AND s.month IS NOT NULL
                ORDER BY s.year DESC, s.month DESC, s.createdAt DESC
            """)
    List<BankStatement> findAllWithPeriodOrderByYearMonthDesc();

    @Query("""
                SELECT s FROM BankStatement s
                WHERE s.year = :year AND s.month = :month
                ORDER BY s.createdAt DESC
            """)
    List<BankStatement> findByYearAndMonthOrderByCreatedAtDesc(@Param("year") Integer year, @Param("month") Integer month);

    @Query("""
                SELECT DISTINCT s.year, s.month
                FROM BankStatement s
                WHERE s.year IS NOT NULL AND s.month IS NOT NULL
                ORDER BY s.year DESC, s.month DESC
            """)
    List<Object[]> findDistinctPeriods();

    // ==================== MOIS PRÉCÉDENT ====================

    // ==================== RELEVÉS INVALIDES ====================

    @Query("""
                SELECT s FROM BankStatement s
                WHERE s.isBalanceValid = false
                   OR s.isContinuityValid = false
                   OR s.errorTransactionCount > 0
                ORDER BY s.createdAt DESC
            """)
    List<BankStatement> findInvalidStatements();

    // ==================== STATISTIQUES ====================

    @Query("SELECT COUNT(DISTINCT s.rib) FROM BankStatement s")
    long countDistinctRibs();

    @Query("""
                SELECT AVG(s.overallConfidence)
                FROM BankStatement s
                WHERE s.status = :status
                  AND s.overallConfidence IS NOT NULL
            """)
    Double averageConfidenceByStatus(@Param("status") BankStatus status);

    // ==================== RECHERCHE AVANCÉE ====================

    @Query("""
                SELECT s FROM BankStatement s
                WHERE (:rib IS NULL OR s.rib = :rib)
                  AND (:year IS NULL OR s.year = :year)
                  AND (:month IS NULL OR s.month = :month)
                  AND (:status IS NULL OR s.status = :status)
                ORDER BY s.year DESC, s.month DESC
            """)
    Page<BankStatement> search(
            @Param("rib") String rib,
            @Param("year") Integer year,
            @Param("month") Integer month,
            @Param("status") BankStatus status,
            Pageable pageable);
}
