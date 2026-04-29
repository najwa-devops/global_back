package com.invoice_reader.invoice_reader.banking_entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "journal_entry", indexes = {
        @Index(name = "idx_journal_entry_batch", columnList = "batch_id"),
        @Index(name = "idx_journal_entry_period", columnList = "nmois,journal")
})
@Data
@NoArgsConstructor
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private JournalBatch batch;

    @Column(nullable = false)
    private Long numero;

    @Column(nullable = false, length = 20)
    private String mois;

    @Column(nullable = false)
    private Integer nmois;

    @Column(name = "date_complete", nullable = false)
    private LocalDate dateComplete;

    @Column(nullable = false, length = 30)
    private String journal;

    @Column(name = "ncompte", nullable = false, length = 30)
    private String ncompte;

    @Column(nullable = false, length = 1000)
    private String libelle;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(name = "source_transaction_id")
    private Long sourceTransactionId;

    @Column(name = "is_counterpart", nullable = false)
    private Boolean isCounterpart = false;
}
