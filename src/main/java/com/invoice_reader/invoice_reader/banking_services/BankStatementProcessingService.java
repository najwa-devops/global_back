package com.invoice_reader.invoice_reader.banking_services;

import com.invoice_reader.invoice_reader.banking_entity.BankStatement;
import com.invoice_reader.invoice_reader.banking_entity.BankStatus;
import com.invoice_reader.invoice_reader.banking_entity.BankTransaction;
import com.invoice_reader.invoice_reader.banking_repository.BankStatementRepository;
import com.invoice_reader.invoice_reader.banking_repository.BankTransactionRepository;
import com.invoice_reader.invoice_reader.banking_services.banking_ocr.OcrCleaningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * ✅ SERVICE TRAITEMENT RELEVÉS BANCAIRES - VERSION
 * 
 * Utilise TransactionExtractorService pour une extraction précise
 * basée sur les structures spécifiques de chaque banque
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BankStatementProcessingService {

    private static final String DEFAULT_COMPTE = "349700000";
    private static final String BANK_PRINCIPAL_COMPTE = "514100000";
    private static final String FRAIS_CHARGE_COMPTE = "614700000";
    private static final String FRAIS_TVA_COMPTE = "345520106";
    private static final BigDecimal TTC_DIVISOR = new BigDecimal("1.1");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final String COMMISSION_HT_LABEL = "COMMISSION HT";
    private static final String COMMISSION_TVA_LABEL = "TVA SUR COMMISSION";
    private static final String FRAIS_HT_LABEL = "FRAIS HT";
    private static final String FRAIS_TVA_LABEL = "TVA SUR FRAIS";
    private static final String AGIOS_HT_LABEL = "AGIOS HT";
    private static final String AGIOS_TVA_LABEL = "TVA SUR AGIOS";
    private static final String PACKAGE_HT_LABEL = "PACKAGE HT";
    private static final String PACKAGE_TVA_LABEL = "TVA SUR PACKAGE";
    private static final String FRAIS_HT_ROLE = "FRAIS_HT";
    private static final String FRAIS_TVA_ROLE = "FRAIS_TVA";
    private static final String FRAIS_REMISE_NET_ROLE = "FRAIS_REMISE_NET";
    private static final String COMMISSION_HT_ROLE = "COMMISSION_HT";
    private static final String COMMISSION_TVA_ROLE = "COMMISSION_TVA";
    private static final String COMMISSION_REMISE_NET_ROLE = "COMMISSION_REMISE_NET";
    private static final String AGIOS_HT_ROLE = "AGIOS_HT";
    private static final String AGIOS_TVA_ROLE = "AGIOS_TVA";
    private static final String AGIOS_REMISE_NET_ROLE = "AGIOS_REMISE_NET";
    private static final String PACKAGE_HT_ROLE = "PACKAGE_HT";
    private static final String PACKAGE_TVA_ROLE = "PACKAGE_TVA";
    private static final String PACKAGE_REMISE_NET_ROLE = "PACKAGE_REMISE_NET";
    private static final Pattern COMMISSION_START_LIBELLE_PATTERN = Pattern.compile("^\\s*COMM?ISSIONS?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FRAIS_START_LIBELLE_PATTERN = Pattern.compile("^\\s*FRAIS\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AGIOS_START_LIBELLE_PATTERN = Pattern.compile("^\\s*AGIOS?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PACKAGE_START_LIBELLE_PATTERN = Pattern.compile("^\\s*PACK(?:AGE)?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMISSION_TAX_LIBELLE_PATTERN = Pattern.compile("^\\s*(?:SOUS|SUR)\\s+COMM?ISSION\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIR_RECU_LIBELLE_PATTERN = Pattern.compile(
            "\\bVIR(?:EMENT)?\\s*[\\./-]?\\s*RECU\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIREMENT_VERS_CLIENT_LIBELLE_PATTERN = Pattern.compile(
            "\\bVIREMENT\\s*(?:\\(\\s*S\\s*\\)|S)?\\s+VERS\\s+CLIENT\\s*(?:\\(\\s*S\\s*\\)|S)?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIR_INSTANTANE_EN_FAVEUR_PATTERN = Pattern.compile(
            "\\bVIR(?:EMENT)?\\.?\\s*INSTANTANE\\s+EN\\s+FAVEUR\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIR_INSTANTANE_RECU_PATTERN = Pattern.compile(
            "\\bVIR(?:EMENT)?\\.?\\s*INSTANTANE\\s+RECU\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIR_RTGS_RECU_PATTERN = Pattern.compile(
            "\\bVIR(?:EMENT)?\\.?\\s*RTGS\\s+RECU\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSEMENT_CREDIT_PATTERN = Pattern.compile(
            "\\bVERSEMENT\\s+(?:PAR\\s+VOUS[-\\s]?MEME|EFFECTUE\\s+PAR)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROIT_TIMBRE_SUR_VERSEMENT_PATTERN = Pattern.compile(
            "\\b(?:DROIT\\s+DE\\s+TIMBRE|TIMBRE)\\s+SUR\\s+VERSEMENT\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALWAYS_DEBIT_FEE_PATTERN = Pattern.compile(
            "^\\s*(?:FRAIS|COMM?ISSIONS?|AGIOS?|PACK(?:AGE)?|TIMBRE|ABONNEMENT|COTISATION)\\b",
            Pattern.CASE_INSENSITIVE);
    // Règles métier Saham Bank / relevés marocains
    private static final Pattern VENTE_PAR_CARTE_PATTERN = Pattern.compile(
            "\\bVENTE\\s+PAR\\s+CARTE\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RECEPTION_VIREMENT_PATTERN = Pattern.compile(
            "\\bRECEPTION\\s+D.UN\\s+VIREMENT\\b|\\bRECEPTION\\s+VIREMENT\\b|\\bRECEPTION\\s+D.UN\\s+VIREMENT\\s+CONFRERE\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMISSION_VIREMENT_PATTERN = Pattern.compile(
            "\\bEMISSION\\s+D.UN\\s+VIREMENT\\b|\\bEMISSION\\s+VIREMENT\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRANSFERT_CASH_PATTERN = Pattern.compile(
            "\\bTRANSFERT\\s+CASH\\b",
            Pattern.CASE_INSENSITIVE);

    private final BankStatementProcessor bankStatementProcessor;
    private final OcrCleaningService cleaningService;
    private final MetadataExtractorService metadataExtractor;
    private final TransactionExtractorService transactionExtractor; // ✅ VERSION 2
    private final BankTransactionAccountLearningService accountLearningService;
    private final BankStatementValidatorService validator;
    private final BankStatementRepository statementRepository;
    private final BankTransactionRepository transactionRepository;

    @Value("${banking.duplicate-detection-enabled:false}")
    private boolean duplicateDetectionEnabled;
    @Value("${banking.storage.temporary-file-retention:true}")
    private boolean temporaryFileRetention;

    private BankStatementProcessingService self;

    @Autowired
    public void setSelf(@Lazy BankStatementProcessingService self) {
        this.self = self;
    }

    @Async("bankingTaskExecutor")
    public void processStatementAsync(Long statementId, String bankType, List<String> allowedBanks) {
        log.info("🚀 [ASYNC] Début tâche pour relevé ID: {} (Banque forcée: {}, Autorisées: {})", statementId,
                bankType, allowedBanks);
        try {
            self.processStatement(statementId, bankType, allowedBanks);
        } catch (Exception e) {
            log.error("❌ [ASYNC] Erreur critique lors du traitement {}: {}",
                    statementId, e.getMessage(), e);

            // Marquer le relevé en erreur
            try {
                self.markAsError(statementId, "Erreur traitement: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    @Async("bankingTaskExecutor")
    public void reprocessStatementAsync(Long statementId) {
        log.info("🔄 [ASYNC] Retraitement pour relevé ID: {}", statementId);
        try {
            self.reprocessStatement(statementId, null);
        } catch (Exception e) {
            log.error("❌ [ASYNC] Erreur critique lors du retraitement {}: {}",
                    statementId, e.getMessage(), e);
            try {
                self.markAsError(statementId, "Erreur retraitement: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    @Async("bankingTaskExecutor")
    public void reprocessStatementAsync(Long statementId, List<String> allowedBanks) {
        log.info("🔄 [ASYNC] Retraitement pour relevé ID: {} (Autorisées: {})", statementId, allowedBanks);
        try {
            self.reprocessStatement(statementId, allowedBanks);
        } catch (Exception e) {
            log.error("❌ [ASYNC] Erreur critique lors du retraitement {}: {}",
                    statementId, e.getMessage(), e);
            try {
                self.markAsError(statementId, "Erreur retraitement: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    @Transactional
    public void markAsError(Long statementId, String errorMessage) {
        statementRepository.findById(statementId).ifPresent(stmt -> {
            stmt.setStatus(BankStatus.ERROR);
            stmt.setValidationErrors(errorMessage);
            statementRepository.save(stmt);
        });
    }

    @Transactional
    public BankStatement processStatement(Long statementId, String bankType, List<String> allowedBanks) {
        BankStatement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new RuntimeException("Relevé non trouvé: " + statementId));
        return processStatement(statement, bankType, allowedBanks);
    }

    @Transactional
    public BankStatement processStatement(BankStatement statement, String bankType, List<String> allowedBanks) {
        log.info("=== DÉBUT TRAITEMENT RELEVÉ {} ===", statement.getId());
        // Règle métier: refuser tout doublon (même RIB + période + soldes/totaux).
        final boolean duplicateDetectionActive = true;

        try {
            statement.setStatus(BankStatus.PROCESSING);
            statement = statementRepository.save(statement);

            // 1. Extraction via Processor Bancaire
            log.info("📄 Étape 1/7: Extraction texte OCR");
            String rawOcrText = performExtraction(statement);
            statement.setRawOcrText(rawOcrText);

            log.debug("Texte brut extrait: {} caractères", rawOcrText.length());
            if (log.isTraceEnabled()) {
                log.trace("Aperçu OCR brut:\n{}", rawOcrText.substring(0, Math.min(500, rawOcrText.length())));
            }

            // 2. Nettoyage
            log.info("🧹 Étape 2/7: Nettoyage texte");
            String cleanedText = cleaningService.cleanOcrText(rawOcrText);
            statement.setCleanedOcrText(cleanedText);

            log.debug("Texte nettoyé: {} caractères", cleanedText.length());

            // 3. Extraction métadonnées
            log.info("🔍 Étape 3/7: Extraction métadonnées");
            var metadata = metadataExtractor.extractMetadata(cleanedText);

            if (duplicateDetectionActive) {
                // Vérification doublon métier AVANT d'appliquer (rib,year,month) pour éviter
                // la violation de contrainte unique et le rollback complet.
                DuplicateKey duplicateKey = resolveDuplicateKey(statement, metadata);
                Optional<BankStatement> duplicateTarget = findDuplicateByPeriod(statement, duplicateKey);
                if (duplicateTarget.isPresent()) {
                    markAsDuplicateAndSave(statement, metadata, duplicateTarget.get());
                    log.info("=== FIN TRAITEMENT - Statut: DUPLIQUE (relevé déjà existant) ===");
                    return statement;
                }
            }

            log.info("Métadonnées extraites - RIB: {}, Période: {}/{}, Banque: {}",
                    statement.getRib(), statement.getMonth(), statement.getYear(), statement.getBankName());

            // 3.5 Validation de la politique de banque autorisée
            applyMetadata(statement, metadata);
            if (allowedBanks != null && !allowedBanks.isEmpty()) {
                boolean isAutoAllowed = allowedBanks.contains("AUTO");
                String detectedType = metadata.bankType != null ? metadata.bankType.name() : "UNKNOWN";
                boolean isSpecificAllowed = allowedBanks.contains(detectedType);

                // Si AUTO est coché, on accepte tout. Sinon, seulement les banques cochées.
                boolean finalAllowed = isAutoAllowed || isSpecificAllowed;

                log.info(
                        "🔍 Validation politique - Détecté: [{}], AUTO autorisé: {}, Spécifique autorisé: {}, Résultat: {}",
                        detectedType, isAutoAllowed, isSpecificAllowed, finalAllowed);
                log.info("📋 Liste des types autorisés reçus: {}", allowedBanks);
                for (String allowed : allowedBanks) {
                    log.debug("   - Comparaison avec [{}]: match={}", allowed, allowed.equals(detectedType));
                }

                if (!finalAllowed) {
                    log.error("❌ Politique de banque violée: {} n'est pas autorisé (Choix: {})", detectedType,
                            allowedBanks);
                    String detectedBank = statement.getBankName() != null ? statement.getBankName() : "Inconnue";
                    markAsError(statement.getId(),
                            "Structure non autorisée: " + detectedBank
                                    + ". Ajoutez cette banque dans la liste des banques autorisées puis lancez le reprocessing.");
                    return statement;
                }
            }

            // 4. Gestion doublons (RIB + période + soldes/totaux PDF)
            if (duplicateDetectionActive) {
                markAsDuplicateIfNeeded(statement);
            } else {
                clearDuplicateFlags(statement);
            }

            // 5. Extraction transactions
            log.info("💳 Étape 4/7: Extraction transactions ()");
            List<BankTransaction> transactions = transactionExtractor.extractTransactions(
                    cleanedText,
                    statement.getMonth(),
                    statement.getYear(),
                    bankType);
            enforceDebitCreditFromLibelleHints(transactions);
            transactions = applyFraisRuleSplitIfEnabled(statement, transactions);
            transactions = applyAgiosRuleSplitIfEnabled(statement, transactions);
            transactions = applyPackageRuleSplitIfEnabled(statement, transactions);
            transactions = applyTtcCommissionSplitIfEnabled(statement, transactions);

            log.info("✅ {} transactions extraites", transactions.size());

            // Vérification critique
            if (transactions.isEmpty()) {
                log.warn("⚠️ AUCUNE TRANSACTION EXTRAITE - Vérifier le format du relevé");
                log.debug("Aperçu du texte nettoyé pour débug:\n{}",
                        cleanedText.length() > 2000 ? cleanedText.substring(0, 2000) : cleanedText);
            }

            // 6. Associer transactions au statement
            log.info("🔗 Étape 5/7: Association transactions");
            for (BankTransaction transaction : transactions) {
                String resolvedCompte = resolveAccountingAccount(transaction);
                transaction.setCompte(resolvedCompte);
                transaction.setIsLinked(!DEFAULT_COMPTE.equals(resolvedCompte));

                statement.addTransaction(transaction);
                transaction.setStatement(statement);
                transaction.setRib(statement.getRib());
            }
            reindexTransactions(transactions);

            // Période du relevé: priorité à la période metadata (header).
            // Fallback uniquement si metadata absente.
            List<LocalDate> operationDates = transactions.stream()
                    .map(BankTransaction::getDateOperation)
                    .filter(Objects::nonNull)
                    .toList();
            if (!operationDates.isEmpty()) {
                LocalDate minDate = operationDates.stream().min(LocalDate::compareTo).orElse(null);
                if (minDate != null && (statement.getMonth() == null || statement.getYear() == null)) {
                    statement.setMonth(minDate.getMonthValue());
                    statement.setYear(minDate.getYear());
                }
            }

            // 7. Calculs comptables
            log.info("🧮 Étape 6/7: Calculs totaux");
            calculateTotals(statement);
            statement.updateTransactionCounters();
            applyVerification(statement);

            log.info("Totaux calculés - Crédit: {}, Débit: {}, Nombre: {}",
                    statement.getTotalCredit(),
                    statement.getTotalDebit(),
                    statement.getTransactionCount());

            // 8. Confiance OCR moyenne
            double avgConfidence = calculateAverageConfidence(transactions);
            statement.setOverallConfidence(avgConfidence);
            log.debug("Confiance moyenne: {}", avgConfidence);

            // 9. Validation
            log.info("✅ Étape 7/7: Validation");
            var balanceValidation = validator.validateBalances(statement);
            var continuityValidation = validator.checkContinuity(statement);

            // 10. Déterminer statut final
            determineStatus(statement, balanceValidation, continuityValidation);
            if (isEmptyStatement(statement)) {
                statement.setValidationErrors(null);
            } else if (transactions.isEmpty()) {
                String snippet = cleanedText.length() > 200 ? cleanedText.substring(0, 200) : cleanedText;
                statement.setValidationErrors(
                        "⚠️ Zéro transactions extraites. Aperçu OCR: " + snippet.replace("\n", " | "));
            } else {
                compileValidationErrors(statement, balanceValidation, continuityValidation);
            }

            // Stockage temporaire: supprimer le binaire source après extraction réussie.
            purgeUploadedBinaryIfConfigured(statement);

            // 10. Sauvegarde finale
            log.info("💾 Sauvegarde finale");
            BankStatement saved = statementRepository.save(statement);

            // Sauvegarde explicite des transactions
            if (!transactions.isEmpty()) {
                log.info("💾 Sauvegarde de {} transactions", transactions.size());
                transactionRepository.saveAll(transactions);
                log.info("✅ Transactions sauvegardées avec succès");
            }

            log.info("=== ✅ FIN TRAITEMENT - Statut: {} | Transactions: {} ===",
                    saved.getStatus(), saved.getTransactionCount());

            return saved;

        } catch (DataIntegrityViolationException e) {
            if (isDuplicateKeyViolation(e)) {
                if (!duplicateDetectionActive) {
                    log.error("Conflit unique RIB/période détecté alors que les doublons sont autorisés. " +
                            "Supprimez l'index unique uk_rib_year_month sur bank_statement.");
                    statement.setStatus(BankStatus.ERROR);
                    statement.setValidationErrors(
                            "Contrainte DB unique (rib,year,month) active: supprimez uk_rib_year_month");
                    return statementRepository.save(statement);
                }
                statement.setIsDuplicate(true);
                statement.setStatus(BankStatus.DUPLIQUE);
                statement.setValidationErrors("Doublon DB: même RIB/période/soldes déjà présents en base");
                statementRepository.save(statement);
                log.warn("Doublon DB intercepté pour relevé {}: {}", statement.getId(), e.getMessage());
                return statement;
            }
            log.error("❌ Erreur traitement: {}", e.getMessage(), e);
            statement.setStatus(BankStatus.ERROR);
            statement.setValidationErrors("Erreur: " + e.getMessage());
            statementRepository.save(statement);
            throw new RuntimeException("Échec traitement: " + e.getMessage(), e);
        } catch (Exception e) {
            if (isDuplicateKeyViolation(e) && !duplicateDetectionActive) {
                log.error("Conflit unique RIB/période détecté alors que les doublons sont autorisés. " +
                        "Supprimez l'index unique uk_rib_year_month sur bank_statement.");
                statement.setStatus(BankStatus.ERROR);
                statement.setValidationErrors(
                        "Contrainte DB unique active: supprimez l'index unique sur la clé métier des doublons");
                return statementRepository.saveAndFlush(statement);
            }
            log.error("❌ Erreur traitement: {}", e.getMessage(), e);
            statement.setStatus(BankStatus.ERROR);
            statement.setValidationErrors("Erreur: " + e.getMessage());
            statementRepository.save(statement);
            throw new RuntimeException("Échec traitement: " + e.getMessage(), e);
        }
    }

    private List<BankTransaction> applyTtcCommissionSplitIfEnabled(BankStatement statement, List<BankTransaction> txs) {
        if (txs == null || txs.isEmpty()) {
            return txs;
        }
        if (!Boolean.TRUE.equals(statement.getApplyTtcRule())) {
            return txs;
        }

        List<BankTransaction> expanded = new ArrayList<>();
        Set<BankTransaction> consumedTaxLines = new HashSet<>();
        for (BankTransaction tx : txs) {
            if (consumedTaxLines.contains(tx)) {
                continue;
            }
            if (Boolean.TRUE.equals(tx.getFraisRuleApplied())
                    || !containsCommission(tx.getLibelle())
                    || hasSiblingDetailedFeeLines(txs, tx)) {
                expanded.add(tx);
                continue;
            }

            BigDecimal originalAmount = transactionAmount(tx);
            if (originalAmount == null || originalAmount.compareTo(ZERO) <= 0) {
                expanded.add(tx);
                continue;
            }

            boolean debitTransaction = isDebit(tx);
            BankTransaction taxSibling = findSiblingCommissionTaxLine(txs, tx, debitTransaction);
            BigDecimal amountHt = originalAmount;
            BigDecimal amountTax;
            BigDecimal totalOriginalAmount;
            if (taxSibling != null) {
                amountTax = transactionAmount(taxSibling);
                totalOriginalAmount = amountHt.add(amountTax).setScale(2, RoundingMode.HALF_UP);
                consumedTaxLines.add(taxSibling);
            } else {
                amountHt = originalAmount.divide(TTC_DIVISOR, 2, RoundingMode.HALF_UP);
                amountTax = originalAmount.subtract(amountHt).setScale(2, RoundingMode.HALF_UP);
                totalOriginalAmount = originalAmount.setScale(2, RoundingMode.HALF_UP);
            }
            String splitGroupId = UUID.randomUUID().toString();

            BankTransaction htTx = cloneForSplit(tx);
            htTx.setLibelle(COMMISSION_HT_LABEL);
            assignSplitAmounts(htTx, debitTransaction ? amountHt : ZERO, debitTransaction ? ZERO : amountHt);
            htTx.setCompte(FRAIS_CHARGE_COMPTE);
            htTx.setIsLinked(true);
            htTx.setFraisRuleApplied(true);
            htTx.setFraisSplitGroupId(splitGroupId);
            htTx.setFraisSplitRole(COMMISSION_HT_ROLE);
            htTx.setFraisOriginalAmount(totalOriginalAmount);
            expanded.add(htTx);

            BankTransaction taxTx = cloneForSplit(tx);
            taxTx.setLibelle(COMMISSION_TVA_LABEL);
            assignSplitAmounts(taxTx, debitTransaction ? amountTax : ZERO, debitTransaction ? ZERO : amountTax);
            taxTx.setCompte(FRAIS_TVA_COMPTE);
            taxTx.setIsLinked(true);
            taxTx.setFraisRuleApplied(true);
            taxTx.setFraisSplitGroupId(splitGroupId);
            taxTx.setFraisSplitRole(COMMISSION_TVA_ROLE);
            taxTx.setFraisOriginalAmount(totalOriginalAmount);
            expanded.add(taxTx);

            // La ligne "banque principale" doit toujours partir du côté opposé
            // à la transaction d'origine pour équilibrer les lignes HT/TVA.
            assignSplitAmounts(tx, debitTransaction ? ZERO : totalOriginalAmount, debitTransaction ? totalOriginalAmount : ZERO);
            tx.setCompte(BANK_PRINCIPAL_COMPTE);
            tx.setIsLinked(true);
            tx.setFraisRuleApplied(true);
            tx.setFraisSplitGroupId(splitGroupId);
            tx.setFraisSplitRole(COMMISSION_REMISE_NET_ROLE);
            tx.setFraisOriginalAmount(totalOriginalAmount);
            expanded.add(tx);
        }
        return expanded;
    }

    private List<BankTransaction> applyFeeRuleSplit(
            List<BankTransaction> txs,
            Predicate<String> keywordMatcher,
            String htLabel,
            String tvaLabel,
            String htRole,
            String tvaRole,
            String netRole) {
        if (txs == null || txs.isEmpty()) {
            return txs;
        }

        List<BankTransaction> expanded = new ArrayList<>();
        for (BankTransaction tx : txs) {
            if (tx == null
                    || !keywordMatcher.test(tx.getLibelle())
                    || Boolean.TRUE.equals(tx.getFraisRuleApplied())
                    || hasSiblingDetailedFeeLines(txs, tx)) {
                expanded.add(tx);
                continue;
            }

            BigDecimal originalAmount = transactionAmount(tx);
            if (originalAmount == null || originalAmount.compareTo(ZERO) <= 0) {
                expanded.add(tx);
                continue;
            }

            boolean debitTransaction = isDebit(tx);
            BigDecimal amountHt = originalAmount.divide(TTC_DIVISOR, 2, RoundingMode.HALF_UP);
            BigDecimal amountTax = originalAmount.subtract(amountHt).setScale(2, RoundingMode.HALF_UP);
            String splitGroupId = UUID.randomUUID().toString();

            BankTransaction htTx = cloneForSplit(tx);
            htTx.setLibelle(htLabel);
            assignSplitAmounts(htTx, debitTransaction ? amountHt : ZERO, debitTransaction ? ZERO : amountHt);
            htTx.setCompte(FRAIS_CHARGE_COMPTE);
            htTx.setIsLinked(true);
            htTx.setFraisRuleApplied(true);
            htTx.setFraisSplitGroupId(splitGroupId);
            htTx.setFraisSplitRole(htRole);
            htTx.setFraisOriginalAmount(originalAmount.setScale(2, RoundingMode.HALF_UP));
            expanded.add(htTx);

            BankTransaction taxTx = cloneForSplit(tx);
            taxTx.setLibelle(tvaLabel);
            assignSplitAmounts(taxTx, debitTransaction ? amountTax : ZERO, debitTransaction ? ZERO : amountTax);
            taxTx.setCompte(FRAIS_TVA_COMPTE);
            taxTx.setIsLinked(true);
            taxTx.setFraisRuleApplied(true);
            taxTx.setFraisSplitGroupId(splitGroupId);
            taxTx.setFraisSplitRole(tvaRole);
            taxTx.setFraisOriginalAmount(originalAmount.setScale(2, RoundingMode.HALF_UP));
            expanded.add(taxTx);

            // La ligne "banque principale" doit toujours partir du côté opposé
            // à la transaction d'origine pour équilibrer les lignes HT/TVA.
            assignSplitAmounts(tx, debitTransaction ? ZERO : originalAmount, debitTransaction ? originalAmount : ZERO);
            tx.setCompte(BANK_PRINCIPAL_COMPTE);
            tx.setIsLinked(true);
            tx.setFraisRuleApplied(true);
            tx.setFraisSplitGroupId(splitGroupId);
            tx.setFraisSplitRole(netRole);
            tx.setFraisOriginalAmount(originalAmount.setScale(2, RoundingMode.HALF_UP));
            expanded.add(tx);
        }
        return expanded;
    }

    private List<BankTransaction> applyFraisRuleSplitIfEnabled(BankStatement statement, List<BankTransaction> txs) {
        if (txs == null || txs.isEmpty()) {
            return txs;
        }
        if (statement == null || !Boolean.TRUE.equals(statement.getApplyFraisRule())) {
            return txs;
        }
        return applyFeeRuleSplit(
                txs,
                this::containsFraisKeyword,
                FRAIS_HT_LABEL,
                FRAIS_TVA_LABEL,
                FRAIS_HT_ROLE,
                FRAIS_TVA_ROLE,
                FRAIS_REMISE_NET_ROLE);
    }

    private List<BankTransaction> applyAgiosRuleSplitIfEnabled(BankStatement statement, List<BankTransaction> txs) {
        if (txs == null || txs.isEmpty()) {
            return txs;
        }
        if (statement == null || !Boolean.TRUE.equals(statement.getApplyAgiosRule())) {
            return txs;
        }
        return applyFeeRuleSplit(
                txs,
                this::containsAgiosKeyword,
                AGIOS_HT_LABEL,
                AGIOS_TVA_LABEL,
                AGIOS_HT_ROLE,
                AGIOS_TVA_ROLE,
                AGIOS_REMISE_NET_ROLE);
    }

    private List<BankTransaction> applyPackageRuleSplitIfEnabled(BankStatement statement, List<BankTransaction> txs) {
        if (txs == null || txs.isEmpty()) {
            return txs;
        }
        if (statement == null || !Boolean.TRUE.equals(statement.getApplyPackageRule())) {
            return txs;
        }
        return applyFeeRuleSplit(
                txs,
                this::containsPackageKeyword,
                PACKAGE_HT_LABEL,
                PACKAGE_TVA_LABEL,
                PACKAGE_HT_ROLE,
                PACKAGE_TVA_ROLE,
                PACKAGE_REMISE_NET_ROLE);
    }

    private void enforceDebitCreditFromLibelleHints(List<BankTransaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return;
        }
        for (BankTransaction tx : transactions) {
            if (tx == null) {
                continue;
            }
            if (tx.getFraisSplitRole() != null && !tx.getFraisSplitRole().isBlank()) {
                continue;
            }
            String libelle = tx.getLibelle();
            BigDecimal debit = tx.getDebit() == null ? ZERO : tx.getDebit();
            BigDecimal credit = tx.getCredit() == null ? ZERO : tx.getCredit();
            if (debit.compareTo(ZERO) > 0
                    && credit.compareTo(ZERO) == 0
                    && libelle != null
                    && VIR_RECU_LIBELLE_PATTERN.matcher(libelle).find()) {
                tx.setCredit(debit);
                tx.setDebit(ZERO);
                tx.setSens("CREDIT");
                if (tx.getFlags() != null) {
                    tx.getFlags().add("CREDIT_FORCED_BY_LIBELLE_HINT");
                }
            }
            if (debit.compareTo(ZERO) > 0
                    && credit.compareTo(ZERO) == 0
                    && libelle != null
                    && VIR_INSTANTANE_RECU_PATTERN.matcher(libelle).find()) {
                tx.setCredit(debit);
                tx.setDebit(ZERO);
                tx.setSens("CREDIT");
                if (tx.getFlags() != null) {
                    tx.getFlags().add("CREDIT_FORCED_VIR_INSTANTANE_RECU");
                }
            }
            if (debit.compareTo(ZERO) > 0
                    && credit.compareTo(ZERO) == 0
                    && libelle != null
                    && VIR_RTGS_RECU_PATTERN.matcher(libelle).find()) {
                tx.setCredit(debit);
                tx.setDebit(ZERO);
                tx.setSens("CREDIT");
                if (tx.getFlags() != null) {
                    tx.getFlags().add("CREDIT_FORCED_VIR_RTGS_RECU");
                }
            }
            // VENTE PAR CARTE → toujours CRÉDIT (entrée d'argent par TPE)
            if (debit.compareTo(ZERO) > 0
                    && credit.compareTo(ZERO) == 0
                    && libelle != null
                    && VENTE_PAR_CARTE_PATTERN.matcher(libelle).find()) {
                tx.setCredit(debit);
                tx.setDebit(ZERO);
                tx.setSens("CREDIT");
                if (tx.getFlags() != null) {
                    tx.getFlags().add("CREDIT_FORCED_VENTE_CARTE");
                }
            }
            // RECEPTION D'UN VIREMENT → toujours CRÉDIT
            if (debit.compareTo(ZERO) > 0
                    && credit.compareTo(ZERO) == 0
                    && libelle != null
                    && RECEPTION_VIREMENT_PATTERN.matcher(libelle).find()) {
                tx.setCredit(debit);
                tx.setDebit(ZERO);
                tx.setSens("CREDIT");
                if (tx.getFlags() != null) {
                    tx.getFlags().add("CREDIT_FORCED_RECEPTION_VIREMENT");
                }
            }
            // VIR. INSTANTANE EN FAVEUR ... -> sortie d'argent => toujours DEBIT
            if (credit.compareTo(ZERO) > 0
                    && debit.compareTo(ZERO) == 0
                    && libelle != null
                    && VIR_INSTANTANE_EN_FAVEUR_PATTERN.matcher(libelle).find()) {
                tx.setDebit(credit);
                tx.setCredit(ZERO);
                tx.setSens("DEBIT");
                if (tx.getFlags() != null) {
                    tx.getFlags().add("DEBIT_FORCED_VIR_INSTANTANE_EN_FAVEUR");
                }
            }
            // VERSEMENT PAR VOUS-MEME / EFFECTUE PAR ... -> entrée d'argent => toujours CREDIT
            if (debit.compareTo(ZERO) > 0
                    && credit.compareTo(ZERO) == 0
                    && libelle != null
                    && VERSEMENT_CREDIT_PATTERN.matcher(libelle).find()) {
                tx.setCredit(debit);
                tx.setDebit(ZERO);
                tx.setSens("CREDIT");
                if (tx.getFlags() != null) {
                    tx.getFlags().add("CREDIT_FORCED_VERSEMENT");
                }
            }
            // Droit de timbre sur versement = frais bancaire => toujours DEBIT
            if (credit.compareTo(ZERO) > 0
                    && debit.compareTo(ZERO) == 0
                    && libelle != null
                    && DROIT_TIMBRE_SUR_VERSEMENT_PATTERN.matcher(libelle).find()) {
                tx.setDebit(credit);
                tx.setCredit(ZERO);
                tx.setSens("DEBIT");
                if (tx.getFlags() != null) {
                    tx.getFlags().add("DEBIT_FORCED_TIMBRE_VERSEMENT");
                }
            }
            // Les frais et commissions bancaires ne doivent jamais rester côté crédit.
            if (credit.compareTo(ZERO) > 0
                    && debit.compareTo(ZERO) == 0
                    && libelle != null
                    && ALWAYS_DEBIT_FEE_PATTERN.matcher(normalizeRuleLibelle(libelle)).find()) {
                tx.setDebit(credit);
                tx.setCredit(ZERO);
                tx.setSens("DEBIT");
                if (tx.getFlags() != null) {
                    tx.getFlags().add("DEBIT_FORCED_FEE_LIBELLE");
                }
            }
            if (credit.compareTo(ZERO) > 0
                    && debit.compareTo(ZERO) == 0
                    && libelle != null
                    && VIREMENT_VERS_CLIENT_LIBELLE_PATTERN.matcher(libelle).find()) {
                tx.setDebit(credit);
                tx.setCredit(ZERO);
                tx.setSens("DEBIT");
                if (tx.getFlags() != null) {
                    tx.getFlags().add("DEBIT_FORCED_BY_LIBELLE_HINT");
                }
            }
            // EMISSION D'UN VIREMENT → toujours DÉBIT (sortie d'argent)
            if (credit.compareTo(ZERO) > 0
                    && debit.compareTo(ZERO) == 0
                    && libelle != null
                    && EMISSION_VIREMENT_PATTERN.matcher(libelle).find()) {
                tx.setDebit(credit);
                tx.setCredit(ZERO);
                tx.setSens("DEBIT");
                if (tx.getFlags() != null) {
                    tx.getFlags().add("DEBIT_FORCED_EMISSION_VIREMENT");
                }
            }
            // TRANSFERT CASH → toujours DÉBIT
            if (credit.compareTo(ZERO) > 0
                    && debit.compareTo(ZERO) == 0
                    && libelle != null
                    && TRANSFERT_CASH_PATTERN.matcher(libelle).find()) {
                tx.setDebit(credit);
                tx.setCredit(ZERO);
                tx.setSens("DEBIT");
                if (tx.getFlags() != null) {
                    tx.getFlags().add("DEBIT_FORCED_TRANSFERT_CASH");
                }
            }
        }
    }

    private boolean containsCommission(String libelle) {
        return matchesRuleStart(libelle, COMMISSION_START_LIBELLE_PATTERN)
                && !isAlreadyDetailedFeeLine(normalizeRuleLibelle(libelle));
    }

    private boolean isCommissionTaxLine(String libelle) {
        if (libelle == null || libelle.isBlank()) {
            return false;
        }
        return COMMISSION_TAX_LIBELLE_PATTERN.matcher(normalizeRuleLibelle(libelle)).find();
    }

    private boolean containsFraisKeyword(String libelle) {
        return matchesRuleStart(libelle, FRAIS_START_LIBELLE_PATTERN)
                && !isAlreadyDetailedFeeLine(normalizeRuleLibelle(libelle));
    }

    private boolean containsAgiosKeyword(String libelle) {
        return matchesRuleStart(libelle, AGIOS_START_LIBELLE_PATTERN)
                && !isAlreadyDetailedFeeLine(normalizeRuleLibelle(libelle));
    }

    private boolean containsPackageKeyword(String libelle) {
        return matchesRuleStart(libelle, PACKAGE_START_LIBELLE_PATTERN)
                && !isAlreadyDetailedFeeLine(normalizeRuleLibelle(libelle));
    }

    private boolean isAlreadyDetailedFeeLine(String normalizedLibelle) {
        if (normalizedLibelle == null || normalizedLibelle.isBlank()) {
            return false;
        }
        return normalizedLibelle.equals(COMMISSION_HT_LABEL)
                || normalizedLibelle.equals(COMMISSION_TVA_LABEL)
                || normalizedLibelle.equals(FRAIS_HT_LABEL)
                || normalizedLibelle.equals(FRAIS_TVA_LABEL)
                || normalizedLibelle.equals(AGIOS_HT_LABEL)
                || normalizedLibelle.equals(AGIOS_TVA_LABEL)
                || normalizedLibelle.equals(PACKAGE_HT_LABEL)
                || normalizedLibelle.equals(PACKAGE_TVA_LABEL)
                || normalizedLibelle.equals("TOTAL COMMISSIONS HT")
                || normalizedLibelle.equals("TOTAL TVA SUR COMMISSIONS")
                || normalizedLibelle.equals("TOTAL TVA SUR COMMISSION")
                || normalizedLibelle.equals("TOTAL FRAIS HT")
                || normalizedLibelle.equals("TOTAL TVA SUR FRAIS")
                || normalizedLibelle.equals("TOTAL AGIOS HT")
                || normalizedLibelle.equals("TOTAL AGIOS TVA")
                || normalizedLibelle.equals("TOTAL PACKAGE HT")
                || normalizedLibelle.equals("TOTAL PACKAGE TVA");
    }

    private String normalizeRuleLibelle(String libelle) {
        if (libelle == null) {
            return "";
        }
        return java.text.Normalizer.normalize(libelle, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase()
                .trim();
    }

    private boolean matchesRuleStart(String libelle, Pattern pattern) {
        if (libelle == null || libelle.isBlank()) {
            return false;
        }
        return pattern.matcher(normalizeRuleLibelle(libelle)).find();
    }

    private boolean hasSiblingDetailedFeeLines(List<BankTransaction> transactions, BankTransaction source) {
        if (transactions == null || source == null) {
            return false;
        }
        Integer sourceIndex = source.getTransactionIndex();
        LocalDate sourceDate = source.getDateOperation();
        for (BankTransaction candidate : transactions) {
            if (candidate == null || candidate == source) {
                continue;
            }
            if (Boolean.TRUE.equals(candidate.getFraisRuleApplied())) {
                continue;
            }
            if (!sameOperationGroup(sourceIndex, sourceDate, candidate)) {
                continue;
            }
            String normalized = java.text.Normalizer.normalize(
                    candidate.getLibelle() == null ? "" : candidate.getLibelle(),
                    java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "")
                    .toUpperCase()
                    .trim();
            if (isAlreadyDetailedFeeLine(normalized)) {
                return true;
            }
        }
        return false;
    }

    private BankTransaction findSiblingCommissionTaxLine(List<BankTransaction> transactions, BankTransaction source,
            boolean debitTransaction) {
        if (transactions == null || source == null) {
            return null;
        }
        Integer sourceIndex = source.getTransactionIndex();
        LocalDate sourceDate = source.getDateOperation();
        for (BankTransaction candidate : transactions) {
            if (candidate == null || candidate == source) {
                continue;
            }
            if (Boolean.TRUE.equals(candidate.getFraisRuleApplied())) {
                continue;
            }
            if (!sameOperationGroup(sourceIndex, sourceDate, candidate)) {
                continue;
            }
            if (!isCommissionTaxLine(candidate.getLibelle())) {
                continue;
            }
            if (transactionAmount(candidate).compareTo(ZERO) <= 0) {
                continue;
            }
            if (debitTransaction != isDebit(candidate)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private boolean sameOperationGroup(Integer sourceIndex, LocalDate sourceDate, BankTransaction candidate) {
        if (sourceIndex != null && candidate.getTransactionIndex() != null) {
            return Objects.equals(sourceIndex, candidate.getTransactionIndex());
        }
        return sourceDate != null && Objects.equals(sourceDate, candidate.getDateOperation());
    }

    private boolean isDebit(BankTransaction tx) {
        return tx.getDebit() != null && tx.getDebit().compareTo(ZERO) > 0;
    }

    private BigDecimal transactionAmount(BankTransaction tx) {
        if (tx.getDebit() != null && tx.getDebit().compareTo(ZERO) > 0) {
            return tx.getDebit();
        }
        if (tx.getCredit() != null && tx.getCredit().compareTo(ZERO) > 0) {
            return tx.getCredit();
        }
        return ZERO;
    }

    private void assignSplitAmounts(BankTransaction tx, BigDecimal debit, BigDecimal credit) {
        BigDecimal safeDebit = debit == null ? ZERO : debit.setScale(2, RoundingMode.HALF_UP);
        BigDecimal safeCredit = credit == null ? ZERO : credit.setScale(2, RoundingMode.HALF_UP);
        tx.setDebit(safeDebit);
        tx.setCredit(safeCredit);
        tx.setSens(safeDebit.compareTo(ZERO) > 0 ? "DEBIT" : "CREDIT");
    }

    private BankTransaction cloneForSplit(BankTransaction source) {
        BankTransaction clone = new BankTransaction();
        clone.setTransactionIndex(source.getTransactionIndex());
        clone.setDateOperation(source.getDateOperation());
        clone.setDateValeur(source.getDateValeur());
        clone.setRib(source.getRib());
        clone.setReference(source.getReference());
        clone.setCode(source.getCode());
        clone.setSens(source.getSens());
        clone.setCompte(source.getCompte());
        clone.setIsLinked(source.getIsLinked());
        clone.setCategorie(source.getCategorie());
        clone.setRole(source.getRole());
        clone.setExtractionConfidence(source.getExtractionConfidence());
        clone.setIsValid(source.getIsValid());
        clone.setNeedsReview(source.getNeedsReview());
        clone.setExtractionErrors(source.getExtractionErrors());
        clone.setLineNumber(source.getLineNumber());
        clone.setRawOcrLinePath(source.getRawOcrLinePath());
        clone.setRawOcrLine(source.getRawOcrLine());
        clone.setReviewNotes(source.getReviewNotes());
        clone.setCmApplied(source.getCmApplied());
        return clone;
    }

    private String resolveAccountingAccount(BankTransaction transaction) {
        if (Boolean.TRUE.equals(transaction.getFraisRuleApplied())) {
            String splitRole = transaction.getFraisSplitRole();
            if (splitRole != null && splitRole.endsWith("_TVA")) {
                return FRAIS_TVA_COMPTE;
            }
            if (splitRole != null && splitRole.endsWith("_REMISE_NET")) {
                return BANK_PRINCIPAL_COMPTE;
            }
            return FRAIS_CHARGE_COMPTE;
        }
        return accountLearningService.findSuggestedAccount(transaction.getLibelle())
                .orElse(DEFAULT_COMPTE);
    }

    private void reindexTransactions(List<BankTransaction> transactions) {
        int nextIndex = 1;
        for (BankTransaction transaction : transactions) {
            Integer currentIndex = transaction.getTransactionIndex();
            if (currentIndex != null && currentIndex > 0) {
                nextIndex = Math.max(nextIndex, currentIndex + 1);
                continue;
            }
            transaction.setTransactionIndex(nextIndex++);
        }
    }

    private String performExtraction(BankStatement statement) {
        byte[] fileData = statement.getFileData();
        if ((fileData == null || fileData.length == 0) && statement.getFilePath() != null && !statement.getFilePath().isBlank()) {
            try {
                Path path = Path.of(statement.getFilePath());
                if (Files.exists(path)) {
                    fileData = Files.readAllBytes(path);
                    log.info("Lecture du relevé depuis le disque: {} ({} bytes)", path, fileData.length);
                }
            } catch (Exception e) {
                log.warn("Impossible de lire le fichier relevé depuis {}: {}", statement.getFilePath(), e.getMessage());
            }
        }
        if (fileData == null || fileData.length == 0) {
            // Fallback: le binaire a déjà été supprimé (mode temporaire) →
            // utiliser le texte OCR brut stocké en base pour le retraitement.
            if (statement.getRawOcrText() != null && !statement.getRawOcrText().isBlank()) {
                log.info("Binaire non disponible — retraitement via texte OCR stocké (relevé {})", statement.getId());
                return statement.getRawOcrText();
            }
            throw new RuntimeException("Fichier introuvable en base. id=" + statement.getId()
                    + ", filename=" + statement.getFilename());
        }

        String sourceName = statement.getOriginalName() != null ? statement.getOriginalName() : statement.getFilename();
        log.info("Extraction du fichier en base: {} ({} bytes)", sourceName, fileData.length);

        String extractedText = bankStatementProcessor.process(fileData, sourceName);
        if (extractedText == null || extractedText.trim().isEmpty()) {
            throw new RuntimeException("Le processeur n'a extrait aucun texte du fichier");
        }

        log.info("✅ Extraction terminée: {} caractères", extractedText.length());
        return extractedText;
    }

    private void applyMetadata(BankStatement statement, MetadataExtractorService.BankStatementMetadata metadata) {
        if (metadata.rib != null) {
            statement.setRib(metadata.rib);
            log.debug("RIB: {}", metadata.rib);
        }

        if (metadata.month != null && metadata.month > 0 && metadata.month <= 12) {
            statement.setMonth(metadata.month);
            log.debug("Mois: {}", metadata.month);
        }

        if (metadata.year != null && metadata.year > 2000 && metadata.year <= 2100) {
            statement.setYear(metadata.year);
            log.debug("Année: {}", metadata.year);
        }

        if (metadata.totalDebitPdf != null) {
            statement.setTotalDebitPdf(metadata.totalDebitPdf);
            log.debug("Total débit PDF: {}", metadata.totalDebitPdf);
        }

        if (metadata.totalCreditPdf != null) {
            statement.setTotalCreditPdf(metadata.totalCreditPdf);
            log.debug("Total crédit PDF: {}", metadata.totalCreditPdf);
        }

        if (metadata.openingBalance != null) {
            statement.setOpeningBalance(metadata.openingBalance);
            log.debug("Solde ouverture: {}", metadata.openingBalance);
        }

        if (metadata.closingBalance != null) {
            statement.setClosingBalance(metadata.closingBalance);
            log.debug("Solde clôture: {}", metadata.closingBalance);
        }

        String resolvedBankName = resolveBankName(metadata, statement);
        if (resolvedBankName != null) {
            statement.setBankName(resolvedBankName);
            log.debug("Banque: {}", resolvedBankName);
        }

        if (metadata.accountHolder != null) {
            statement.setAccountHolder(metadata.accountHolder);
            log.debug("Titulaire: {}", metadata.accountHolder);
        }
    }

    private void calculateTotals(BankStatement statement) {
        statement.calculateTotalsFromTransactions();
        log.debug("Totaux calculés - Crédit: {}, Débit: {}",
                statement.getTotalCredit(), statement.getTotalDebit());
    }

    private String resolveBankName(MetadataExtractorService.BankStatementMetadata metadata, BankStatement statement) {
        if (metadata != null && metadata.bankName != null && !metadata.bankName.isBlank()) {
            return metadata.bankName;
        }
        if (metadata != null && metadata.bankType != null && metadata.bankType != BankType.UNKNOWN) {
            return switch (metadata.bankType) {
                case BCP -> "BANQUE POPULAIRE";
                case BMCE -> "BANK OF AFRICA (BMCE)";
                case BARID_BANK -> "AL BARID BANK";
                case ATTIJARIWAFA -> "ATTIJARIWAFA";
                case BMCI -> "BMCI";
                case CIH -> "CIH BANK";
                case SOCIETE_GENERALE -> "SOCIETE GENERALE";
                case CREDIT_DU_MAROC -> "CREDIT DU MAROC";
                case CREDIT_AGRICOLE -> "CREDIT AGRICOLE";
                case SAHAM_BANK -> "SAHAM BANK";
                case AMEX -> "AMERICAN EXPRESS";
                case UNKNOWN -> null;
            };
        }
        String rib = statement != null ? statement.getRib() : null;
        if (rib != null && rib.startsWith("145")) {
            return "BANQUE POPULAIRE";
        }
        return null;
    }

    private void purgeUploadedBinaryIfConfigured(BankStatement statement) {
        if (!temporaryFileRetention || statement == null || statement.getFileData() == null) {
            return;
        }

        statement.setFileData(null);
        statement.setFilePath("TEMP_CLEARED");
        log.info("🗑️ Fichier binaire supprimé (mode temporaire) pour relevé {}", statement.getId());
    }

    private void applyVerification(BankStatement statement) {
        BigDecimal debitPdf = statement.getTotalDebitPdf();
        BigDecimal creditPdf = statement.getTotalCreditPdf();
        BigDecimal debitCalc = statement.getTotalDebit() != null ? statement.getTotalDebit() : BigDecimal.ZERO;
        BigDecimal creditCalc = statement.getTotalCredit() != null ? statement.getTotalCredit() : BigDecimal.ZERO;

        if (debitPdf == null && creditPdf == null) {
            statement.setVerificationStatus(null);
            return;
        }

        boolean debitOk = debitPdf == null || debitPdf.subtract(debitCalc).abs().compareTo(new BigDecimal("0.01")) <= 0;
        boolean creditOk = creditPdf == null
                || creditPdf.subtract(creditCalc).abs().compareTo(new BigDecimal("0.01")) <= 0;

        statement.setVerificationStatus(debitOk && creditOk ? "OK" : "INCOHERENCE");
    }

    private double calculateAverageConfidence(List<BankTransaction> transactions) {
        return transactions.stream()
                .mapToDouble(t -> t.getExtractionConfidence() != null ? t.getExtractionConfidence() : 0.5)
                .average()
                .orElse(0.5);
    }

    private void markAsDuplicateIfNeeded(BankStatement statement) {
        String hash = buildDuplicateHash(statement);
        statement.setDuplicateHash(hash);
        statement.setIsDuplicate(false);
    }

    private void clearDuplicateFlags(BankStatement statement) {
        statement.setIsDuplicate(false);
        statement.setDuplicateHash(null);
        if (statement.getValidationErrors() != null && statement.getValidationErrors().startsWith("DUPLIQUE_OF:")) {
            statement.setValidationErrors(null);
        }
    }

    private Optional<BankStatement> findDuplicateByPeriod(BankStatement statement, DuplicateKey key) {
        if (key == null) {
            return Optional.empty();
        }

        if (key.duplicateHash != null && !key.duplicateHash.isBlank()) {
            Optional<BankStatement> byHash = findFirstOtherByDuplicateHash(statement, key.duplicateHash);
            if (byHash.isPresent()) {
                return byHash;
            }
        }

        if (key.rib == null || key.month == null || key.year == null) {
            return Optional.empty();
        }

        return findFirstOtherByPeriod(statement, key.rib, key.year, key.month);
    }

    private Optional<BankStatement> findFirstOtherByPeriod(BankStatement statement, String rib, Integer year,
            Integer month) {
        if (rib == null || year == null || month == null) {
            return Optional.empty();
        }
        String normalizedRib = normalizeRibKey(rib);
        return statementRepository.findByYearAndMonthOrderByCreatedAtDesc(year, month)
                .stream()
                .filter(candidate -> !candidate.getId().equals(statement.getId()))
                .filter(candidate -> normalizeRibKey(candidate.getRib()).equals(normalizedRib))
                .findFirst();
    }

    private Optional<BankStatement> findFirstOtherByDuplicateHash(BankStatement statement, String duplicateHash) {
        if (duplicateHash == null || duplicateHash.isBlank()) {
            return Optional.empty();
        }
        return statementRepository.findAllByDuplicateHashOrderByCreatedAtDescIdDesc(duplicateHash)
                .stream()
                .filter(candidate -> !candidate.getId().equals(statement.getId()))
                .findFirst();
    }

    private void markAsDuplicateAndSave(BankStatement statement,
            MetadataExtractorService.BankStatementMetadata metadata,
            BankStatement existing) {
        if (metadata != null) {
            if (metadata.bankName != null) {
                statement.setBankName(metadata.bankName);
            }
            if (metadata.totalDebitPdf != null) {
                statement.setTotalDebitPdf(metadata.totalDebitPdf);
            }
            if (metadata.totalCreditPdf != null) {
                statement.setTotalCreditPdf(metadata.totalCreditPdf);
            }
        }
        statement.setIsDuplicate(true);
        statement.setStatus(BankStatus.DUPLIQUE);
        statement.setValidationErrors("DUPLIQUE_OF:" + existing.getId()
                + "; Doublon: même RIB/période/soldes déjà présents en base");
        statement.setDuplicateHash(existing.getDuplicateHash());
        statementRepository.save(statement);
    }

    private DuplicateKey resolveDuplicateKey(BankStatement statement,
            MetadataExtractorService.BankStatementMetadata metadata) {
        DuplicateKey key = new DuplicateKey();
        key.rib = normalizeRibKey(metadata != null && metadata.rib != null ? metadata.rib : statement.getRib());
        key.month = metadata != null && metadata.month != null ? metadata.month : statement.getMonth();
        key.year = metadata != null && metadata.year != null ? metadata.year : statement.getYear();
        key.duplicateHash = buildDuplicateHashFromValues(
                key.rib,
                key.month,
                key.year,
                metadata != null ? metadata.openingBalance : statement.getOpeningBalance(),
                metadata != null ? metadata.closingBalance : statement.getClosingBalance(),
                metadata != null ? metadata.totalDebitPdf : statement.getTotalDebitPdf(),
                metadata != null ? metadata.totalCreditPdf : statement.getTotalCreditPdf());
        return key;
    }

    private boolean isDuplicateKeyViolation(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && (msg.contains("Duplicate entry") || msg.contains("duplicate key")
                    || msg.contains("UKgwgu01850vgaj7qi537rgvm43"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static class DuplicateKey {
        private String rib;
        private Integer month;
        private Integer year;
        private String duplicateHash;
    }

    private String buildDuplicateHash(BankStatement statement) {
        return buildDuplicateHashFromValues(
                statement.getRib(),
                statement.getMonth(),
                statement.getYear(),
                statement.getOpeningBalance(),
                statement.getClosingBalance(),
                statement.getTotalDebitPdf(),
                statement.getTotalCreditPdf());
    }

    private String buildDuplicateHashFromValues(String rib, Integer month, Integer year, BigDecimal openingBalance,
            BigDecimal closingBalance, BigDecimal totalDebitPdf, BigDecimal totalCreditPdf) {
        if (rib == null || month == null || year == null) {
            return null;
        }

        String opening = openingBalance != null ? openingBalance.toPlainString() : "0.0";
        String closing = closingBalance != null ? closingBalance.toPlainString() : "0.0";
        String debit = totalDebitPdf != null ? totalDebitPdf.toPlainString() : "0.0";
        String credit = totalCreditPdf != null ? totalCreditPdf.toPlainString() : "0.0";

        String period = String.format("%02d/%04d", month, year);
        String raw = String.join("|", rib, period, opening, closing, debit, credit);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeRibKey(String rib) {
        if (rib == null) {
            return null;
        }
        String digits = rib.replaceAll("\\D", "");
        if (!digits.isBlank()) {
            return digits;
        }
        return rib.trim().isBlank() ? null : rib.trim();
    }

    private boolean isEmptyStatement(BankStatement statement) {
        boolean noRib = statement.getRib() == null || statement.getRib().isBlank();
        boolean noTotals = statement.getTotalDebitPdf() == null && statement.getTotalCreditPdf() == null;
        boolean noTransactions = statement.getTransactionCount() == 0;
        return noRib && noTotals && noTransactions;
    }

    private void determineStatus(BankStatement statement, Object balanceValidation, Object continuityValidation) {
        if (isEmptyStatement(statement)) {
            statement.setStatus(BankStatus.TREATED);
            statement.setValidationErrors("VIDE");
            log.info("Statut TREATED (VIDE): fichier vide");
            return;
        }

        if (Boolean.TRUE.equals(statement.getIsDuplicate())) {
            statement.setStatus(BankStatus.TREATED);
            log.info("Statut TREATED (DUPLIQUE): doublon détecté");
            return;
        }

        if (statement.getTransactionCount() == 0) {
            statement.setStatus(BankStatus.ERROR);
            log.warn("Statut ERROR: Aucune transaction");
            return;
        }

        if ("INCOHERENCE".equals(statement.getVerificationStatus())) {
            statement.setStatus(BankStatus.TREATED);
            log.info("Statut TREATED: incohérence comptable");
            return;
        }

        statement.setStatus(BankStatus.READY_TO_VALIDATE);
        log.info("Statut READY_TO_VALIDATE");
    }

    private void compileValidationErrors(BankStatement statement, Object balanceValidation,
            Object continuityValidation) {
        List<String> errors = new ArrayList<>();
        if (!errors.isEmpty()) {
            statement.setValidationErrors(String.join("; ", errors));
        }
    }

    @Transactional
    public BankStatement reprocessStatement(Long statementId) {
        return reprocessStatement(statementId, null);
    }

    @Transactional
    public BankStatement reprocessStatement(Long statementId, List<String> allowedBanks) {
        log.info("🔄 Retraitement du relevé ID: {}", statementId);

        BankStatement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new IllegalArgumentException("Relevé non trouvé: " + statementId));

        if (!statement.isModifiable()) {
            throw new IllegalStateException("Relevé validé, retraitement impossible");
        }

        // Supprimer les transactions existantes
        log.info("Suppression des {} transactions existantes", statement.getTransactionCount());
        transactionRepository.deleteByStatementId(statementId);
        statement.getTransactions().clear();

        // Réinitialiser le statut
        statement.setStatus(BankStatus.PENDING);
        statement.setValidationErrors(null);

        // Retraiter
        return processStatement(statement, null, allowedBanks);
    }

    @Transactional
    public void deleteStatement(Long id) {
        log.info("🗑️ Suppression du relevé ID: {}", id);

        BankStatement statement = statementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Relevé non trouvé: " + id));

        // Supprimer les transactions
        transactionRepository.deleteByStatementId(id);

        if (statement.getTransactions() != null) {
            statement.getTransactions().clear();
        }

        // Supprimer le relevé
        statementRepository.delete(statement);

        log.info("✅ Relevé {} supprimé avec succès", id);
    }

    @Transactional(readOnly = true)
    public Optional<BankStatement> detectDuplicateFromUpload(byte[] fileData, String originalName) {
        if (fileData == null || fileData.length == 0) {
            return Optional.empty();
        }
        try {
            String sourceName = originalName != null ? originalName : "upload";
            String rawOcrText = bankStatementProcessor.process(fileData, sourceName);
            if (rawOcrText == null || rawOcrText.trim().isEmpty()) {
                return Optional.empty();
            }
            String cleanedText = cleaningService.cleanOcrText(rawOcrText);
            var metadata = metadataExtractor.extractMetadata(cleanedText);
            if (metadata == null || metadata.rib == null || metadata.month == null || metadata.year == null) {
                return Optional.empty();
            }
            metadata.rib = normalizeRibKey(metadata.rib);
            String duplicateHash = buildDuplicateHashFromValues(
                    metadata.rib,
                    metadata.month,
                    metadata.year,
                    metadata.openingBalance,
                    metadata.closingBalance,
                    metadata.totalDebitPdf,
                    metadata.totalCreditPdf);
            if (duplicateHash != null) {
                Optional<BankStatement> byHash = findFirstOtherByDuplicateHashByValues(
                        metadata.rib, metadata.month, metadata.year, duplicateHash);
                if (byHash.isPresent()) {
                    return byHash;
                }
            }
            return statementRepository.findByYearAndMonthOrderByCreatedAtDesc(metadata.year, metadata.month)
                    .stream()
                    .filter(candidate -> normalizeRibKey(candidate.getRib()).equals(metadata.rib))
                    .findFirst();
        } catch (Exception e) {
            log.warn("Détection de doublon upload ignorée (OCR/metadata KO): {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<BankStatement> findFirstOtherByDuplicateHashByValues(String rib, Integer month, Integer year,
            String duplicateHash) {
        if (duplicateHash == null || duplicateHash.isBlank()) {
            return Optional.empty();
        }
        return statementRepository.findAllByDuplicateHashOrderByCreatedAtDescIdDesc(duplicateHash)
                .stream()
                .findFirst();
    }

    public ProcessingStatistics getStatistics() {
        ProcessingStatistics stats = new ProcessingStatistics();
        stats.totalStatements = statementRepository.count();
        stats.pendingStatements = statementRepository.countByStatus(BankStatus.PENDING);
        stats.processingStatements = statementRepository.countByStatus(BankStatus.PROCESSING);
        stats.treatedStatements = statementRepository.countByStatus(BankStatus.TREATED);
        stats.readyStatements = statementRepository.countByStatus(BankStatus.READY_TO_VALIDATE);
        stats.validatedStatements = statementRepository.countByStatus(BankStatus.VALIDATED);
        stats.accountedStatements = statementRepository.countByStatus(BankStatus.COMPTABILISE);
        stats.errorStatements = statementRepository.countByStatus(BankStatus.ERROR);
        stats.totalRibs = statementRepository.countDistinctRibs();
        return stats;
    }

    public static class ProcessingStatistics {
        public long totalStatements;
        public long pendingStatements;
        public long processingStatements;
        public long treatedStatements;
        public long readyStatements;
        public long validatedStatements;
        public long accountedStatements;
        public long errorStatements;
        public long totalRibs;
        public long invalidStatements;
        public Double averageConfidence;
    }
}
