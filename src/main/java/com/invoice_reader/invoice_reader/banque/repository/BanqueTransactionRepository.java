package com.invoice_reader.invoice_reader.banque.repository;

import com.invoice_reader.invoice_reader.banque.entity.BanqueTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface BanqueTransactionRepository extends JpaRepository<BanqueTransaction, Long> {

    @Query("SELECT t FROM BanqueTransaction t JOIN FETCH t.statement WHERE t.id = :id")
    Optional<BanqueTransaction> findByIdWithStatement(@Param("id") Long id);

    // ==================== RECHERCHE PAR RELEVÉ ====================

    @Query("""
            SELECT t
            FROM BanqueTransaction t
            WHERE t.statement.id = :statementId
            ORDER BY t.transactionIndex ASC, t.id ASC
            """)
    List<BanqueTransaction> findByStatementIdOrderByTransactionIndexAsc(@Param("statementId") Long statementId);

    Page<BanqueTransaction> findByStatementId(Long statementId, Pageable pageable);

    long countByStatementId(Long statementId);

    @Modifying
    @Transactional
    @Query("DELETE FROM BanqueTransaction t WHERE t.statement.id = :statementId")
    void deleteByStatementId(@Param("statementId") Long statementId);

    @Modifying
    @Transactional
    @Query("""
            UPDATE BanqueTransaction t
            SET t.transactionIndex = t.transactionIndex + 1
            WHERE t.statement.id = :statementId
              AND t.transactionIndex >= :fromIndex
            """)
    int shiftIndexesFrom(@Param("statementId") Long statementId, @Param("fromIndex") Integer fromIndex);

    @Query("SELECT COALESCE(MAX(t.transactionIndex), -1) FROM BanqueTransaction t WHERE t.statement.id = :statementId")
    Integer findMaxTransactionIndex(@Param("statementId") Long statementId);

    // ==================== RECHERCHE PAR CATÉGORIE ====================

    List<BanqueTransaction> findByCategorie(String categorie);

    List<BanqueTransaction> findByStatementIdAndCategorie(Long statementId, String categorie);

    // ==================== RECHERCHE PAR SENS ====================

    List<BanqueTransaction> findBySens(String sens);

    List<BanqueTransaction> findByStatementIdAndSens(Long statementId, String sens);

    // ==================== RECHERCHE PAR VALIDATION ====================

    List<BanqueTransaction> findByNeedsReviewTrue();

    List<BanqueTransaction> findByIsValidFalse();

    Page<BanqueTransaction> findByNeedsReviewTrue(Pageable pageable);

    // ==================== RECHERCHE PAR LIBELLÉ ====================

    @Query("""
                SELECT t FROM BanqueTransaction t
                WHERE LOWER(t.libelle) LIKE LOWER(CONCAT('%', :keyword, '%'))
                ORDER BY t.dateOperation DESC
            """)
    List<BanqueTransaction> searchByLibelle(@Param("keyword") String keyword);

    @Query("""
                SELECT t FROM BanqueTransaction t
                WHERE t.statement.id = :statementId
                  AND LOWER(t.libelle) LIKE LOWER(CONCAT('%', :keyword, '%'))
                ORDER BY t.transactionIndex ASC, t.id ASC
            """)
    List<BanqueTransaction> searchByLibelleForStatement(
            @Param("statementId") Long statementId,
            @Param("keyword") String keyword);

    // ==================== STATISTIQUES ====================

    @Query("""
                SELECT t.categorie,
                       COUNT(t),
                       COALESCE(SUM(t.debit), 0),
                       COALESCE(SUM(t.credit), 0)
                FROM BanqueTransaction t
                WHERE t.statement.id = :statementId
                GROUP BY t.categorie
            """)
    List<Object[]> sumAmountsByCategorieForStatement(@Param("statementId") Long statementId);

    @Query("""
                SELECT t.categorie,
                       COUNT(t),
                       COALESCE(SUM(t.debit), 0),
                       COALESCE(SUM(t.credit), 0)
                FROM BanqueTransaction t
                GROUP BY t.categorie
            """)
    List<Object[]> getGlobalStatisticsByCategorie();

    @Query("""
            SELECT t
            FROM BanqueTransaction t
            JOIN t.statement s
            WHERE MONTH(t.dateOperation) = :nmois
              AND (:year IS NULL OR YEAR(t.dateOperation) = :year)
              AND s.status IN (com.invoice_reader.invoice_reader.banque.entity.BanqueStatus.VALIDATED, com.invoice_reader.invoice_reader.banque.entity.BanqueStatus.COMPTABILISE)
            ORDER BY t.dateOperation ASC, t.id ASC
            """)
    List<BanqueTransaction> findAccountingCandidates(@Param("nmois") int nmois, @Param("year") Integer year);

    // ==================== RAPPROCHEMENT CENTRE MONÉTIQUE ====================

    /**
     * Trouve les transactions bancaires dont le relevé a le RIB donné et dont
     * la date d'opération est dans la liste fournie.
     */
    @Query("""
            SELECT t
            FROM BanqueTransaction t
            JOIN FETCH t.statement s
            WHERE s.rib = :rib
              AND t.dateOperation IN :dates
            ORDER BY t.dateOperation ASC, t.id ASC
            """)
    List<BanqueTransaction> findByStatementRibAndDateOperationIn(
            @Param("rib") String rib,
            @Param("dates") java.util.Collection<java.time.LocalDate> dates);

    /**
     * Trouve les transactions bancaires CMI de type "VENTE PAR CARTE" pour un RIB donné.
     * Le libellé contient le numéro TPE : "VENTE PAR CARTE  000285".
     */
    @Query("""
            SELECT t
            FROM BanqueTransaction t
            JOIN FETCH t.statement s
            WHERE s.rib = :rib
              AND UPPER(t.libelle) LIKE '%VENTE PAR CARTE%'
            ORDER BY t.dateOperation ASC, t.id ASC
            """)
    List<BanqueTransaction> findByRibAndLibelleVenteParCarte(@Param("rib") String rib);

    /**
     * Trouve toutes les transactions crédit pour un RIB donné (CMI TPE rapprochement élargi).
     * Couvre "VENTE PAR CARTE 000293", "AZAR RESTAURAN294055", "ATTEATUDE CAF000294", etc.
     */
    @Query("""
            SELECT t
            FROM BanqueTransaction t
            JOIN FETCH t.statement s
            WHERE s.rib = :rib
              AND t.credit IS NOT NULL
              AND t.credit > 0
            ORDER BY t.dateOperation ASC, t.id ASC
            """)
    List<BanqueTransaction> findCreditTransactionsByRib(@Param("rib") String rib);

    @Query("""
            SELECT t
            FROM BanqueTransaction t
            JOIN FETCH t.statement s
            WHERE s.rib = :rib
              AND s.month = :month
              AND s.year = :year
            ORDER BY t.dateOperation ASC, t.id ASC
            """)
    List<BanqueTransaction> findByStatementRibAndStatementMonthAndYear(
            @Param("rib") String rib,
            @Param("month") Integer month,
            @Param("year") Integer year);

    /**
     * Trouve les transactions bancaires BARID BANK de type virement commerçant pour un RIB donné.
     * Le libellé contient "BARID CASH" : "RECEPTION D'UN VIREMENT DE Merchant ACQ86097 ... BARID CASH".
     */
    @Query("""
            SELECT t
            FROM BanqueTransaction t
            JOIN FETCH t.statement s
            WHERE s.rib = :rib
              AND UPPER(t.libelle) LIKE '%BARID CASH%'
            ORDER BY t.dateOperation ASC, t.id ASC
            """)
    List<BanqueTransaction> findByRibAndLibelleBaridCash(@Param("rib") String rib);
}
