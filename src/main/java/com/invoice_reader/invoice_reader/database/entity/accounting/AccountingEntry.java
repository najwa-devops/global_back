package com.invoice_reader.invoice_reader.database.entity.accounting;

import com.invoice_reader.invoice_reader.database.entity.auth.Dossier;
import com.invoice_reader.invoice_reader.achat.entity.AchatInvoice;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "accounting_entries",
        indexes = {
                @Index(name = "idx_accounting_entries_dossier", columnList = "dossier_id"),
                @Index(name = "idx_accounting_entries_invoice", columnList = "invoice_id"),
                @Index(name = "idx_accounting_entries_date", columnList = "entry_date"),
                @Index(name = "idx_accounting_entries_account", columnList = "account_number"),
                @Index(name = "idx_acc_entries_journal_month_numero", columnList = "ndosjrn,nmois,numero"),
                @Index(name = "idx_acc_entries_date_complete", columnList = "date_complete"),
                @Index(name = "idx_acc_entries_statement_tx", columnList = "source_statement_id,source_transaction_id")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountingEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id")
    private Dossier dossier;

    @Column(name = "dossier_id", insertable = false, updatable = false)
    private Long dossierId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    private AchatInvoice invoice;

    @Column(name = "invoice_id", insertable = false, updatable = false)
    private Long invoiceId;

    @Column(name = "invoice_number", length = 100)
    private String invoiceNumber;

    @Column(name = "supplier_name", length = 200)
    private String supplierName;

    @Column(name = "journal", length = 50)
    private String journal;

    @Column(name = "account_number", length = 50, nullable = false)
    private String accountNumber;

    @Column(name = "entry_date")
    private LocalDate entryDate;

    @Column(name = "debit_amount", precision = 15, scale = 2)
    private BigDecimal debit;

    @Column(name = "credit_amount", precision = 15, scale = 2)
    private BigDecimal credit;

    @Column(name = "label", length = 200)
    private String label;

    @Column(name = "numero")
    private Long numero;

    @Column(name = "mois", length = 20)
    private String mois;

    @Column(name = "nmois")
    private Integer nmois;

    @Column(name = "date_complete")
    private LocalDate dateComplete;

    @Column(name = "ecriture", length = 1000)
    private String ecriture;

    @Column(name = "ncompte", length = 50)
    private String ncompte;

    @Column(name = "jour")
    private Integer date;

    @Column(name = "ndosjrn", length = 30)
    private String ndosjrn;

    @Column(name = "source_statement_id")
    private Long sourceStatementId;

    @Column(name = "source_transaction_id")
    private Long sourceTransactionId;

    @Column(name = "is_counterpart")
    private Boolean isCounterpart;

    @Column(name = "batch_id", length = 64)
    private String batchId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (entryDate == null) {
            entryDate = LocalDate.now();
        }
        if (debit == null) {
            debit = BigDecimal.ZERO;
        }
        if (credit == null) {
            credit = BigDecimal.ZERO;
        }
        if (isCounterpart == null) {
            isCounterpart = false;
        }
    }
}
