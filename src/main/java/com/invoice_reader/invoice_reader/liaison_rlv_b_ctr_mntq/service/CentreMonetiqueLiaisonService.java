package com.invoice_reader.invoice_reader.liaison_rlv_b_ctr_mntq.service;

import com.invoice_reader.invoice_reader.centremonetique.entity.CentreMonetiqueBatch;
import com.invoice_reader.invoice_reader.centremonetique.entity.CentreMonetiqueTransaction;
import com.invoice_reader.invoice_reader.centremonetique.repository.CentreMonetiqueBatchRepository;
import com.invoice_reader.invoice_reader.centremonetique.repository.CentreMonetiqueTransactionRepository;
import com.invoice_reader.invoice_reader.centremonetique.service.CentreMonetiqueStructureType;
import com.invoice_reader.invoice_reader.liaison_rlv_b_ctr_mntq.dto.CmExpansionDTO;
import com.invoice_reader.invoice_reader.liaison_rlv_b_ctr_mntq.dto.CmExpansionLineDTO;
import com.invoice_reader.invoice_reader.liaison_rlv_b_ctr_mntq.dto.RapprochementResultDTO;
import com.invoice_reader.invoice_reader.banking_entity.BankStatement;
import com.invoice_reader.invoice_reader.banking_entity.BankTransaction;
import com.invoice_reader.invoice_reader.banking_repository.BankStatementRepository;
import com.invoice_reader.invoice_reader.banking_repository.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CentreMonetiqueLiaisonService {

    /** Extrait le numero TPE depuis un libelle bancaire "VENTE PAR CARTE  000285". */
    private static final Pattern BANK_TPE_PATTERN = Pattern.compile(
            "VENTE\\s+PAR\\s+CARTE\\s+([A-Z0-9]{4,10})\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Extrait le code TPE depuis les derniers chiffres d'un libelle bancaire.
     * Couvre : "AZAR RESTAURAN294055", "ATTEATUDE CAF000294", "AZAR REST MARRAKEC000028".
     * Le TPE est toujours les 5 ou 6 derniers chiffres du libelle.
     */
    private static final Pattern BANK_TPE_TRAILING_PATTERN = Pattern.compile(
            "([0-9]{5,6})\\s*$");

    /** Extrait le code commercant depuis le texte OCR BARID_BANK "COMMERCANT : 86097". */
    private static final Pattern BARID_COMMERCANT_CODE_PATTERN = Pattern.compile(
            "(?i)COMMERCANT\\s*[:\\-]?\\s*(?:[^\\d\\n]{0,40})?([0-9]{4,10})");

    private final CentreMonetiqueBatchRepository batchRepository;
    private final CentreMonetiqueTransactionRepository transactionRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final BankStatementRepository bankStatementRepository;

    /**
     * Rapprochement CMI : liaison par numero TPE.
     * Chaque bloc "ACHAT REMISE TPE N° :000285" dans le CM correspond a
     * "VENTE PAR CARTE  000285" dans le releve bancaire.
     * Le montant CM a comparer est le SOLDE NET REMISE du bloc.
     * Fallback sur correspondance par date si les donnees ne contiennent pas de lignes REMISE ACHAT.
     */
    @Transactional(readOnly = true)
    public Optional<RapprochementResultDTO> rapprochement(Long batchId) {
        Optional<CentreMonetiqueBatch> optional = findBatchForDossier(batchId, null);
        if (optional.isEmpty()) {
            return Optional.empty();
        }
        CentreMonetiqueBatch batch = optional.get();
        String rib = batch.getRib();
        Long dossierId = batch.getDossierId();
        List<CentreMonetiqueTransaction> cmTxs = transactionRepository.findByBatchIdOrderByRowIndexAsc(batchId);

        if (cmTxs.isEmpty()) {
            return Optional.of(new RapprochementResultDTO(batchId, rib, 0, 0, List.of()));
        }

        // Nombre reel de transactions (hors lignes d'en-tete et totaux)
        int realTxCount = batch.getTransactionCount() != null && batch.getTransactionCount() > 0
                ? batch.getTransactionCount()
                : (int) cmTxs.stream().filter(tx -> {
                    String s = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);
                    return !s.startsWith("TOTAL") && !s.startsWith("SOLDE NET")
                            && !s.equals("REMISE ACHAT") && !s.startsWith("REGLEMENT META")
                            && !s.startsWith("REGLEMENT TOTALS")
                            && !s.equals("AMEX SETTLEMENT") && !s.equals("AMEX TERMINAL")
                            && !s.equals("AMEX TOTAL TERMINAL") && !s.equals("AMEX SUB TOTAL");
                }).count();

        // Detecter si les donnees sont au nouveau format (avec lignes REMISE ACHAT contenant le TPE).
        boolean hasRemiseAchat = cmTxs.stream()
                .anyMatch(tx -> "REMISE ACHAT".equalsIgnoreCase(nvl(tx.getSection()).trim()));

        boolean isBarid = CentreMonetiqueStructureType.BARID_BANK.name()
                .equalsIgnoreCase(nvl(batch.getStructure()).trim());
        boolean isAmex = CentreMonetiqueStructureType.AMEX.name()
                .equalsIgnoreCase(nvl(batch.getStructure()).trim());
        boolean isVps = CentreMonetiqueStructureType.VPS.name()
                .equalsIgnoreCase(nvl(batch.getStructure()).trim());

        Optional<RapprochementResultDTO> result;
        if (isAmex) {
            result = buildAmexRapprochement(batchId, rib, dossierId, cmTxs);
        } else if (isBarid) {
            result = buildBaridRapprochement(batchId, rib, dossierId, cmTxs, batch.getRawOcrText());
        } else if (isVps) {
            result = buildVpsRapprochement(batchId, rib, dossierId, cmTxs);
        } else if (hasRemiseAchat) {
            result = buildTpeBasedRapprochement(batchId, rib, dossierId, cmTxs);
        } else {
            result = buildDateBasedRapprochement(batchId, rib, dossierId, cmTxs);
        }

        // Remplacer totalCmTransactions par le vrai comptage (sans les lignes d'en-tete/totaux)
        return result.map(r -> new RapprochementResultDTO(
                r.getBatchId(), r.getBatchRib(), realTxCount, r.getMatchedCount(), r.getMatches()));
    }

    /**
     * Rapprochement CMI par numero TPE (nouveau format).
     * Groupe les transactions par bloc REMISE ACHAT, lie au releve bancaire via "VENTE PAR CARTE {tpe}".
     */
    private Optional<RapprochementResultDTO> buildTpeBasedRapprochement(
            Long batchId, String rib, Long dossierId, List<CentreMonetiqueTransaction> cmTxs) {

        // Charger TOUTES les transactions credit pour ce RIB.
        // Lookup strict : tpe -> Map<amountKey, BankTransaction>.
        // La liaison necessite TPE identique ET montant SOLDE NET REMISE == credit bancaire.
        Map<String, Map<String, BankTransaction>> bankByTpeAndAmount = new LinkedHashMap<>();
        if (rib != null && !rib.isBlank()) {
            // Compatibilité legacy: inclure aussi les transactions bancaires sans dossier (migration ancienne),
            // tout en restant isolé du dossier courant.
            List<BankTransaction> bankTxs = bankTransactionRepository.findCreditTransactionsByRib(rib).stream()
                    .filter(tx -> belongsToDossierOrLegacyNull(
                            tx.getStatement() != null ? tx.getStatement().getDossierId() : null,
                            dossierId))
                    .toList();
            for (BankTransaction bt : bankTxs) {
                String tpe = extractTpeFromBankLibelle(nvl(bt.getLibelle()));
                if (tpe != null && !tpe.isBlank() && bt.getCredit() != null) {
                    bankByTpeAndAmount
                            .computeIfAbsent(tpe, k -> new LinkedHashMap<>())
                            .putIfAbsent(amountKey(bt.getCredit()), bt);
                }
            }
        }

        // Parcourir les lignes CM en machine d'etat : bloc = REMISE ACHAT -> transactions -> SOLDE NET REMISE.
        List<RapprochementResultDTO.RapprochementMatchDTO> matches = new ArrayList<>();
        int matchedCount = 0;

        String currentTpe = null;
        String currentHeaderDate = null;
        List<CentreMonetiqueTransaction> currentBlockTxs = new ArrayList<>();
        BigDecimal currentSoldeNet = null;

        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);

            if (section.equals("REMISE ACHAT")) {
                // Fermer le bloc precedent
                if (currentTpe != null) {
                    matchedCount += flushTpeBlock(currentTpe, currentHeaderDate, currentBlockTxs,
                            currentSoldeNet, bankByTpeAndAmount, matches);
                }
                currentTpe = nvl(tx.getReference()).trim();
                currentHeaderDate = nvl(tx.getDate()).trim();
                currentBlockTxs = new ArrayList<>();
                currentSoldeNet = null;
            } else if (section.startsWith("REMISE ") && !section.equals("REMISE ACHAT")) {
                currentBlockTxs.add(tx);
            } else if (section.equals("SOLDE NET REMISE")) {
                currentSoldeNet = tx.getCredit();
            }
        }
        // Fermer le dernier bloc
        if (currentTpe != null) {
            matchedCount += flushTpeBlock(currentTpe, currentHeaderDate, currentBlockTxs,
                    currentSoldeNet, bankByTpeAndAmount, matches);
        }

        return Optional.of(new RapprochementResultDTO(batchId, rib, cmTxs.size(), matchedCount, matches));
    }

    /**
     * Construit les lignes de correspondance pour un bloc CMI (un TPE terminal).
     * Liaison stricte : TPE identique ET montant SOLDE NET REMISE == credit bancaire.
     * Retourne le nombre de transactions CM ajoutees comme appariees.
     */
    private int flushTpeBlock(String tpe,
                               String headerDate,
                               List<CentreMonetiqueTransaction> blockTxs,
                               BigDecimal soldeNet,
                               Map<String, Map<String, BankTransaction>> bankByTpeAndAmount,
                               List<RapprochementResultDTO.RapprochementMatchDTO> matches) {
        if (blockTxs.isEmpty()) {
            return 0;
        }

        int count = blockTxs.size();
        // Reference du groupe : TPE terminal
        String cmRef = "TPE N° " + tpe;
        // Montant CM = SOLDE NET REMISE du bloc (ce qui arrive sur le compte bancaire)
        String cmMontant = toAmount(soldeNet);

        // Liaison stricte : TPE + montant SOLDE NET REMISE doivent etre identiques.
        // Aucun fallback — si les montants different, pas de liaison bancaire.
        BankTransaction bankTx = null;
        if (soldeNet != null && bankByTpeAndAmount.containsKey(tpe)) {
            bankTx = bankByTpeAndAmount.get(tpe).get(amountKey(soldeNet));
        }
        String bankStatementName = "";
        String bankMontant = "";
        String bankLibelle = "";
        if (bankTx != null) {
            bankStatementName = bankTx.getStatement() != null
                    ? nvl(bankTx.getStatement().getOriginalName()) : "";
            bankMontant = toAmount(bankTx.getCredit());
            bankLibelle = nvl(bankTx.getLibelle());
        }

        // Date : depuis l'en-tete si disponible, sinon date de la premiere transaction
        String date = (headerDate != null && !headerDate.isBlank())
                ? headerDate
                : nvl(blockTxs.get(0).getDate());

        for (CentreMonetiqueTransaction tx : blockTxs) {
            matches.add(new RapprochementResultDTO.RapprochementMatchDTO(
                    date,
                    cmRef,
                    cmMontant,
                    nvl(tx.getReference()),
                    nvl(tx.getDcFlag()),
                    toAmount(tx.getMontant()),
                    bankStatementName,
                    bankMontant,
                    bankLibelle));
        }
        return bankTx != null ? count : 0;
    }

    /**
     * Extrait le numero TPE depuis un libelle bancaire.
     * Essaie d'abord "VENTE PAR CARTE {tpe}", puis prend les 5-6 derniers chiffres du libelle.
     * Couvre : "VENTE PAR CARTE 000293", "AZAR RESTAURAN294055", "ATTEATUDE CAF000294".
     */
    private String extractTpeFromBankLibelle(String libelle) {
        if (libelle == null || libelle.isBlank()) {
            return null;
        }
        Matcher m = BANK_TPE_PATTERN.matcher(libelle);
        if (m.find()) {
            return m.group(1);
        }
        Matcher m2 = BANK_TPE_TRAILING_PATTERN.matcher(libelle.trim());
        return m2.find() ? m2.group(1) : null;
    }

    /**
     * Rapprochement BARID BANK : liaison par montant de reglement + code commercant ACQ.
     * Chaque bloc REGLEMENT dans le CM correspond a un virement bancaire unique :
     *   - Montant de reglement (CM) == Credit (releve bancaire)
     *   - Code commercant "COMMERCANT :" (CM) == ACQ{code} dans le libelle bancaire
     */
    private Optional<RapprochementResultDTO> buildBaridRapprochement(
            Long batchId, String rib, Long dossierId, List<CentreMonetiqueTransaction> cmTxs, String rawOcrText) {

        // Extraire le code commercant depuis le texte OCR ("COMMERCANT : 86097")
        String merchantCode = extractBaridMerchantCode(rawOcrText);

        // Charger les transactions bancaires BARID CASH pour ce RIB
        List<BankTransaction> bankTxs = List.of();
        if (rib != null && !rib.isBlank()) {
            // Compatibilité legacy: inclure aussi les relevés sans dossier, mais jamais d'un autre dossier.
            bankTxs = bankTransactionRepository.findByRibAndLibelleBaridCash(rib).stream()
                    .filter(tx -> belongsToDossierOrLegacyNull(
                            tx.getStatement() != null ? tx.getStatement().getDossierId() : null,
                            dossierId))
                    .toList();
        }

        // Construire la table de lookup : cle = montant credit normalise -> transaction bancaire
        // Si le code commercant est connu, filtrer aussi par ACQ{merchantCode} dans le libelle
        Map<String, BankTransaction> bankByAmount = new LinkedHashMap<>();
        for (BankTransaction bt : bankTxs) {
            if (bt.getCredit() == null) continue;
            boolean acqMatches = merchantCode == null || merchantCode.isBlank()
                    || nvl(bt.getLibelle()).toUpperCase(Locale.ROOT).contains("ACQ" + merchantCode);
            if (acqMatches) {
                bankByAmount.putIfAbsent(amountKey(bt.getCredit()), bt);
            }
        }
        // Fallback sans filtre commercant si aucun match n'a ete trouve par code
        if (bankByAmount.isEmpty() && !bankTxs.isEmpty()) {
            for (BankTransaction bt : bankTxs) {
                if (bt.getCredit() != null) {
                    bankByAmount.putIfAbsent(amountKey(bt.getCredit()), bt);
                }
            }
        }

        List<RapprochementResultDTO.RapprochementMatchDTO> matches = new ArrayList<>();
        int matchedCount = 0;

        // Ordre reel des lignes dans l'extraction BARID_BANK pour un bloc :
        //   1. REGLEMENT {id}  (transactions individuelles)
        //   2. REGLEMENT META  (date du reglement — String tx.getDate())
        //   3. REGLEMENT TOTALS (montant de reglement — BigDecimal tx.getMontant(),
        //                        id du reglement    — String tx.getReference())
        //   4. TOTAL REMISE / SOLDE NET REMISE (totaux — ignores ici)
        // => flush declenche a la reception de REGLEMENT TOTALS.
        String currentDate = null;
        List<CentreMonetiqueTransaction> currentBlockTxs = new ArrayList<>();

        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);

            if (section.equals("REGLEMENT META")) {
                // Sauvegarder la date (String)
                String d = nvl(tx.getDate());
                if (!d.isBlank()) currentDate = d;

            } else if (section.equals("REGLEMENT TOTALS")) {
                // Montant de reglement (BigDecimal) et id (String reference)
                BigDecimal montantReglement = tx.getMontant();
                String reglementId = nvl(tx.getReference());
                if (!currentBlockTxs.isEmpty()) {
                    matchedCount += flushBaridBlock(currentDate, reglementId,
                            montantReglement, currentBlockTxs, bankByAmount, matches);
                }
                // Reinitialiser pour le bloc suivant
                currentDate = null;
                currentBlockTxs = new ArrayList<>();

            } else if (section.startsWith("REGLEMENT ")
                    && !section.equals("REGLEMENT META")
                    && !section.equals("REGLEMENT TOTALS")) {
                currentBlockTxs.add(tx);
            }
        }
        // Flush du dernier bloc si REGLEMENT TOTALS absent (donnees incompletes)
        if (!currentBlockTxs.isEmpty()) {
            matchedCount += flushBaridBlock(currentDate, "",
                    null, currentBlockTxs, bankByAmount, matches);
        }

        return Optional.of(new RapprochementResultDTO(batchId, rib, cmTxs.size(), matchedCount, matches));
    }

    /**
     * Construit les lignes de correspondance pour un bloc BARID BANK (un REGLEMENT).
     * Retourne le nombre de transactions CM ajoutees comme appariees.
     */
    private int flushBaridBlock(String date,
                                 String reglementId,
                                 BigDecimal montantReglement,
                                 List<CentreMonetiqueTransaction> blockTxs,
                                 Map<String, BankTransaction> bankByAmount,
                                 List<RapprochementResultDTO.RapprochementMatchDTO> matches) {
        if (blockTxs.isEmpty()) return 0;

        int count = blockTxs.size();
        String cmRef = count == 1
                ? ("REGLEMENT " + reglementId)
                : (count + " transactions CM");
        String cmMontant = toAmount(montantReglement);

        // Trouver la transaction bancaire correspondante par montant de reglement
        BankTransaction bankTx = montantReglement != null
                ? bankByAmount.get(amountKey(montantReglement))
                : null;

        String bankStatementName = "";
        String bankMontant = "";
        String bankLibelle = "";
        if (bankTx != null) {
            bankStatementName = bankTx.getStatement() != null
                    ? nvl(bankTx.getStatement().getOriginalName()) : "";
            bankMontant = toAmount(bankTx.getCredit());
            bankLibelle = nvl(bankTx.getLibelle());
        }

        String blockDate = (date != null && !date.isBlank())
                ? date
                : nvl(blockTxs.get(0).getDate());

        for (CentreMonetiqueTransaction tx : blockTxs) {
            matches.add(new RapprochementResultDTO.RapprochementMatchDTO(
                    blockDate,
                    cmRef,
                    cmMontant,
                    nvl(tx.getReference()),
                    nvl(tx.getDcFlag()),
                    toAmount(tx.getMontant()),
                    bankStatementName,
                    bankMontant,
                    bankLibelle));
        }
        return bankTx != null ? count : 0;
    }

    /** Extrait le code commercant BARID BANK depuis le texte OCR ("COMMERCANT : 86097"). */
    private String extractBaridMerchantCode(String rawOcrText) {
        if (rawOcrText == null || rawOcrText.isBlank()) return null;
        Matcher m = BARID_COMMERCANT_CODE_PATTERN.matcher(rawOcrText);
        return m.find() ? m.group(1) : null;
    }

    /** Cle normalisee pour comparer des montants (2 decimales fixes). */
    private String amountKey(BigDecimal amount) {
        if (amount == null) return "";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Rapprochement par date (fallback pour CMI sans lignes REMISE ACHAT).
     */
    private Optional<RapprochementResultDTO> buildDateBasedRapprochement(
            Long batchId, String rib, Long dossierId, List<CentreMonetiqueTransaction> cmTxs) {

        Map<LocalDate, List<CentreMonetiqueTransaction>> cmByDate = new LinkedHashMap<>();
        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = tx.getSection() == null ? "" : tx.getSection().trim().toUpperCase(Locale.ROOT);
            boolean isDetailSection = section.equals("REMISE")
                    || section.startsWith("REGLEMENT ")
                    || (section.startsWith("AMEX ") && !section.equals("AMEX SETTLEMENT")
                            && !section.equals("AMEX TERMINAL") && !section.equals("AMEX TOTAL TERMINAL")
                            && !section.equals("AMEX SUB TOTAL"));
            if (!isDetailSection) {
                continue;
            }
            LocalDate d = parseRowDate(tx.getDate());
            if (d == null) {
                continue;
            }
            cmByDate.computeIfAbsent(d, k -> new ArrayList<>()).add(tx);
        }

        if (cmByDate.isEmpty()) {
            return Optional.of(new RapprochementResultDTO(batchId, rib, cmTxs.size(), 0, List.of()));
        }

        Map<LocalDate, List<BankTransaction>> bankByDate = new HashMap<>();
        if (rib != null && !rib.isBlank()) {
            List<BankTransaction> bankTxs = bankTransactionRepository
                    .findByStatementRibAndDateOperationIn(rib, cmByDate.keySet());
            if (dossierId != null) {
                bankTxs = bankTxs.stream()
                        .filter(tx -> tx.getStatement() != null
                                && belongsToDossierOrLegacyNull(tx.getStatement().getDossierId(), dossierId))
                        .toList();
            }
            for (BankTransaction bt : bankTxs) {
                if (bt.getDateOperation() != null) {
                    bankByDate.computeIfAbsent(bt.getDateOperation(), k -> new ArrayList<>()).add(bt);
                }
            }
        }

        List<RapprochementResultDTO.RapprochementMatchDTO> matches = new ArrayList<>();
        int matchedCount = 0;
        for (Map.Entry<LocalDate, List<CentreMonetiqueTransaction>> entry : cmByDate.entrySet()) {
            LocalDate date = entry.getKey();
            List<CentreMonetiqueTransaction> txsForDate = entry.getValue();

            int cmCount = txsForDate.size();
            BigDecimal cmTotal = txsForDate.stream()
                    .map(CentreMonetiqueTransaction::getMontant)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String cmRef = cmCount == 1
                    ? nvl(txsForDate.get(0).getReference())
                    : cmCount + " transactions CM";
            String cmTotalStr = toAmount(cmTotal);

            List<BankTransaction> bankForDate = bankByDate.getOrDefault(date, List.of());
            String bankStatementName = "";
            String bankMontant = "";
            String bankLibelle = "";
            if (!bankForDate.isEmpty()) {
                matchedCount += txsForDate.size();
                bankStatementName = bankForDate.stream()
                        .map(bt -> bt.getStatement() != null ? nvl(bt.getStatement().getOriginalName()) : "")
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .collect(Collectors.joining(" | "));
                BigDecimal bankTotal = bankForDate.stream()
                        .map(BankTransaction::getCredit)
                        .filter(java.util.Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                bankMontant = toAmount(bankTotal);
                bankLibelle = bankForDate.stream()
                        .map(bt -> nvl(bt.getLibelle()))
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .collect(Collectors.joining(" / "));
            }

            for (CentreMonetiqueTransaction tx : txsForDate) {
                matches.add(new RapprochementResultDTO.RapprochementMatchDTO(
                        date.toString(),
                        cmRef,
                        cmTotalStr,
                        nvl(tx.getReference()),
                        nvl(tx.getDcFlag()),
                        toAmount(tx.getMontant()),
                        bankStatementName,
                        bankMontant,
                        bankLibelle));
            }
        }

        return Optional.of(new RapprochementResultDTO(batchId, rib, cmTxs.size(), matchedCount, matches));
    }

    private LocalDate parseRowDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().replace('.', '/').replace('-', '/');
        String[] parts = cleaned.split("/");
        if (parts.length < 3) {
            return null;
        }
        try {
            int day = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int year = Integer.parseInt(parts[2]);
            year = year < 100 ? 2000 + year : year;
            return LocalDate.of(year, month, day);
        } catch (Exception ignored) {
            return null;
        }
    }

    // ==================== CM EXPANSIONS POUR RELEVE BANCAIRE ====================

    /**
     * Retourne pour chaque transaction bancaire du releve donne la liste des lignes CM
     * qui la remplacent dans la vue detail (liaison par RIB, TPE+montant ou montant BARID).
     */
    @Transactional(readOnly = true)
    public List<CmExpansionDTO> getCmExpansionsForStatement(Long statementId) {
        BankStatement statement = findStatementForDossier(statementId, null).orElse(null);
        if (statement == null) return List.of();

        String rib = statement.getRib();
        if (rib == null || rib.isBlank()) return List.of();

        Long dossierId = statement.getDossierId();
        Set<Long> usedBankTxIds = new HashSet<>();
        // Compatibilité legacy: inclure les batches sans dossier si on est dans un dossier actif.
        List<CentreMonetiqueBatch> batches = batchRepository.findProcessedBatchesMatchingRibOrAmexSuffix(rib, "PROCESSED")
                .stream()
                .filter(b -> belongsToDossierOrLegacyNull(b.getDossierId(), dossierId))
                .toList();
        if (batches.isEmpty()) {
            String normalizedStatementRib = normalizeRibDigits(rib);
            List<CentreMonetiqueBatch> fallback = batchRepository.findTop200ByDossierIdOrDossierIdIsNullOrderByCreatedAtDesc(dossierId)
                    .stream()
                    .filter(b -> "PROCESSED".equalsIgnoreCase(nvl(b.getStatus())))
                    .filter(b -> matchesAmexRib(nvl(b.getRib()), normalizedStatementRib))
                    .toList();
            if (!fallback.isEmpty()) {
                batches = fallback;
            }
        }
        if (batches.isEmpty()) return List.of();

        List<BankTransaction> statementTxs =
                bankTransactionRepository.findByStatementIdOrderByTransactionIndexAsc(statementId);

        List<CmExpansionDTO> result = new ArrayList<>();
        for (CentreMonetiqueBatch batch : batches) {
            List<CentreMonetiqueTransaction> cmTxs =
                    transactionRepository.findByBatchIdOrderByRowIndexAsc(batch.getId());
            if (cmTxs.isEmpty()) continue;

            String structure = nvl(batch.getStructure()).trim().toUpperCase(Locale.ROOT);
            boolean isBarid = CentreMonetiqueStructureType.BARID_BANK.name().equals(structure);
            boolean isAmex = CentreMonetiqueStructureType.AMEX.name().equals(structure);
            boolean isVps = CentreMonetiqueStructureType.VPS.name().equals(structure);
            boolean hasTpe = cmTxs.stream()
                    .anyMatch(t -> "REMISE ACHAT".equalsIgnoreCase(nvl(t.getSection()).trim()));

            if (isAmex) {
                result.addAll(buildAmexExpansions(batch, cmTxs, statementTxs, rib, usedBankTxIds));
            } else if (isBarid) {
                result.addAll(buildBaridExpansions(batch, cmTxs, statementTxs, usedBankTxIds));
            } else if (isVps && batchPeriodMatchesStatement(batch, statement)) {
                result.addAll(buildVpsExpansions(batch, cmTxs, statementTxs, usedBankTxIds));
            } else if (hasTpe) {
                result.addAll(buildTpeExpansions(batch, cmTxs, statementTxs, usedBankTxIds));
            }
        }
        return result;
    }

    private List<CmExpansionDTO> buildTpeExpansions(CentreMonetiqueBatch batch,
                                                     List<CentreMonetiqueTransaction> cmTxs,
                                                     List<BankTransaction> statementTxs,
                                                     Set<Long> usedBankTxIds) {
        Map<String, Map<String, BankTransaction>> bankByTpeAndAmount = new LinkedHashMap<>();
        for (BankTransaction bt : statementTxs) {
            if (usedBankTxIds.contains(bt.getId())) continue;
            if (bt.getCredit() == null) continue;
            String tpe = extractTpeFromBankLibelle(nvl(bt.getLibelle()));
            if (tpe != null && !tpe.isBlank()) {
                bankByTpeAndAmount
                        .computeIfAbsent(tpe, k -> new LinkedHashMap<>())
                        .putIfAbsent(amountKey(bt.getCredit()), bt);
            }
        }

        List<CmExpansionDTO> result = new ArrayList<>();
        String currentTpe = null;
        String currentHeaderDate = null;
        List<CentreMonetiqueTransaction> currentBlockTxs = new ArrayList<>();
        BigDecimal currentSoldeNet = null;
        BigDecimal currentCommissionHt = null;
        BigDecimal currentTvaSurCommissions = null;

        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);
            if (section.equals("REMISE ACHAT")) {
                if (currentTpe != null) {
                    CmExpansionDTO exp = buildTpeExpansionBlock(batch, currentTpe, currentHeaderDate,
                            currentBlockTxs, currentSoldeNet, currentCommissionHt, currentTvaSurCommissions,
                            bankByTpeAndAmount, usedBankTxIds);
                    if (exp != null) result.add(exp);
                }
                currentTpe = nvl(tx.getReference()).trim();
                currentHeaderDate = nvl(tx.getDate()).trim();
                currentBlockTxs = new ArrayList<>();
                currentSoldeNet = null;
                currentCommissionHt = null;
                currentTvaSurCommissions = null;
            } else if (section.startsWith("REMISE ") && !section.equals("REMISE ACHAT")) {
                currentBlockTxs.add(tx);
            } else if (section.equals("TOTAL COMMISSIONS HT")) {
                currentCommissionHt = tx.getDebit();
            } else if (section.equals("TOTAL TVA SUR COMMISSIONS")) {
                currentTvaSurCommissions = tx.getDebit();
            } else if (section.equals("SOLDE NET REMISE")) {
                currentSoldeNet = tx.getCredit();
            }
        }
        if (currentTpe != null) {
            CmExpansionDTO exp = buildTpeExpansionBlock(batch, currentTpe, currentHeaderDate,
                    currentBlockTxs, currentSoldeNet, currentCommissionHt, currentTvaSurCommissions,
                    bankByTpeAndAmount, usedBankTxIds);
            if (exp != null) result.add(exp);
        }
        return result;
    }

    private CmExpansionDTO buildTpeExpansionBlock(CentreMonetiqueBatch batch, String tpe, String headerDate,
                                                   List<CentreMonetiqueTransaction> blockTxs,
                                                   BigDecimal soldeNet,
                                                   BigDecimal commissionHt,
                                                   BigDecimal tvaSurCommissions,
                                                   Map<String, Map<String, BankTransaction>> bankByTpeAndAmount,
                                                   Set<Long> usedBankTxIds) {
        if (blockTxs.isEmpty() || soldeNet == null) return null;
        Map<String, BankTransaction> byAmount = bankByTpeAndAmount.get(tpe);
        if (byAmount == null) return null;
        BankTransaction bankTx = byAmount.get(amountKey(soldeNet));
        if (bankTx == null) return null;
        if (usedBankTxIds.contains(bankTx.getId())) return null;
        usedBankTxIds.add(bankTx.getId());

        String date = (headerDate != null && !headerDate.isBlank()) ? headerDate : nvl(blockTxs.get(0).getDate());
        List<CmExpansionLineDTO> lines = blockTxs.stream()
                .map(t -> new CmExpansionLineDTO(date, nvl(t.getReference()), nvl(t.getDcFlag()), toAmount(t.getMontant())))
                .toList();
        return new CmExpansionDTO(bankTx.getId(), batch.getId(), nvl(batch.getOriginalName()), nvl(batch.getStructure()),
                "TPE N° " + tpe, toAmount(soldeNet),
                toAmount(commissionHt), toAmount(tvaSurCommissions),
                lines);
    }

    private List<CmExpansionDTO> buildBaridExpansions(CentreMonetiqueBatch batch,
                                                       List<CentreMonetiqueTransaction> cmTxs,
                                                       List<BankTransaction> statementTxs,
                                                       Set<Long> usedBankTxIds) {
        String merchantCode = extractBaridMerchantCode(batch.getRawOcrText());

        Map<String, BankTransaction> bankByAmount = new LinkedHashMap<>();
        for (BankTransaction bt : statementTxs) {
            if (usedBankTxIds.contains(bt.getId())) continue;
            if (bt.getCredit() == null) continue;
            String libelle = nvl(bt.getLibelle()).toUpperCase(Locale.ROOT);
            if (!libelle.contains("BARID CASH")) continue;
            boolean acqOk = merchantCode == null || merchantCode.isBlank()
                    || libelle.contains("ACQ" + merchantCode);
            if (acqOk) bankByAmount.putIfAbsent(amountKey(bt.getCredit()), bt);
        }
        if (bankByAmount.isEmpty()) {
            for (BankTransaction bt : statementTxs) {
                if (bt.getCredit() == null) continue;
                if (!nvl(bt.getLibelle()).toUpperCase(Locale.ROOT).contains("BARID CASH")) continue;
                bankByAmount.putIfAbsent(amountKey(bt.getCredit()), bt);
            }
        }

        List<CmExpansionDTO> result = new ArrayList<>();
        String currentDate = null;
        BigDecimal currentMontant = null;
        String currentReglementId = "";
        BigDecimal currentCommissionHt = null;
        BigDecimal currentTvaSurCommissions = null;
        List<CentreMonetiqueTransaction> currentBlockTxs = new ArrayList<>();

        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);
            if (section.equals("REGLEMENT META")) {
                if (!currentBlockTxs.isEmpty() && (currentMontant != null || !currentReglementId.isBlank())) {
                    appendBaridExpansion(result, batch, currentDate, currentReglementId, currentMontant,
                            currentCommissionHt, currentTvaSurCommissions, currentBlockTxs, bankByAmount,
                            usedBankTxIds);
                    currentBlockTxs = new ArrayList<>();
                }
                String d = nvl(tx.getDate());
                if (!d.isBlank()) currentDate = d;
            } else if (section.startsWith("REGLEMENT ")
                    && !section.equals("REGLEMENT META")
                    && !section.equals("REGLEMENT TOTALS")) {
                currentBlockTxs.add(tx);
            } else if (section.equals("REGLEMENT TOTALS")) {
                currentMontant = firstNonZero(tx.getMontant(), tx.getCredit(), tx.getDebit());
                currentReglementId = nvl(tx.getReference());
                if (currentCommissionHt == null || currentTvaSurCommissions == null) {
                    BigDecimal fallbackDebit = safeAmount(tx.getDebit());
                    BigDecimal fallbackCredit = safeAmount(tx.getCredit());
                    if (currentCommissionHt == null && fallbackDebit != null) {
                        currentCommissionHt = fallbackDebit;
                    }
                    if (currentTvaSurCommissions == null && fallbackCredit != null) {
                        currentTvaSurCommissions = fallbackCredit;
                    }
                }
                appendBaridExpansion(result, batch, currentDate, currentReglementId, currentMontant,
                        currentCommissionHt, currentTvaSurCommissions, currentBlockTxs, bankByAmount,
                        usedBankTxIds);
                currentDate = null;
                currentMontant = null;
                currentReglementId = "";
                currentCommissionHt = null;
                currentTvaSurCommissions = null;
                currentBlockTxs = new ArrayList<>();
            } else if (section.equals("TOTAL COMMISSIONS HT")) {
                currentCommissionHt = firstNonZero(tx.getMontant(), tx.getDebit(), tx.getCredit());
            } else if (section.equals("TOTAL TVA SUR COMMISSIONS")) {
                currentTvaSurCommissions = firstNonZero(tx.getMontant(), tx.getCredit(), tx.getDebit());
            }
        }
        return result;
    }

    private void appendBaridExpansion(List<CmExpansionDTO> out,
                                      CentreMonetiqueBatch batch,
                                      String currentDate,
                                      String currentReglementId,
                                      BigDecimal currentMontant,
                                      BigDecimal currentCommissionHt,
                                      BigDecimal currentTvaSurCommissions,
                                      List<CentreMonetiqueTransaction> currentBlockTxs,
                                      Map<String, BankTransaction> bankByAmount,
                                      Set<Long> usedBankTxIds) {
        if (currentBlockTxs == null || currentBlockTxs.isEmpty() || currentMontant == null) {
            return;
        }
        BankTransaction bankTx = bankByAmount.get(amountKey(currentMontant));
        if (bankTx == null) {
            return;
        }
        if (usedBankTxIds.contains(bankTx.getId())) {
            return;
        }
        usedBankTxIds.add(bankTx.getId());
        String blockDate = (currentDate != null && !currentDate.isBlank())
                ? currentDate : nvl(currentBlockTxs.get(0).getDate());
        int count = currentBlockTxs.size();
        String reglementLabel = count == 1
                ? (currentReglementId == null || currentReglementId.isBlank()
                    ? "REGLEMENT"
                    : "REGLEMENT " + currentReglementId)
                : count + " transactions CM";
        List<CmExpansionLineDTO> lines = currentBlockTxs.stream()
                .map(t -> new CmExpansionLineDTO(blockDate, nvl(t.getReference()),
                        nvl(t.getDcFlag()), toAmount(t.getMontant())))
                .toList();
        out.add(new CmExpansionDTO(bankTx.getId(), batch.getId(),
                nvl(batch.getOriginalName()), nvl(batch.getStructure()), reglementLabel,
                toAmount(currentMontant), toAmount(currentCommissionHt), toAmount(currentTvaSurCommissions), lines));
    }

    private Optional<RapprochementResultDTO> buildVpsRapprochement(
            Long batchId, String rib, Long dossierId, List<CentreMonetiqueTransaction> cmTxs) {
        CentreMonetiqueBatch batch = batchRepository.findById(batchId).orElse(null);
        Integer periodMonth = extractPeriodMonth(batch != null ? batch.getStatementPeriod() : null);
        Integer periodYear = extractPeriodYear(batch != null ? batch.getStatementPeriod() : null);

        Map<String, List<BankTransaction>> bankByAmount = new LinkedHashMap<>();
        if (rib != null && !rib.isBlank() && periodMonth != null && periodYear != null) {
            List<BankTransaction> bankTxs =
                    bankTransactionRepository.findByStatementRibAndStatementMonthAndYear(rib, periodMonth, periodYear);
            for (BankTransaction bt : bankTxs) {
                if (!belongsToDossierOrLegacyNull(
                        bt.getStatement() != null ? bt.getStatement().getDossierId() : null, dossierId)) {
                    continue;
                }
                BigDecimal amount = bankTxAmount(bt);
                if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) continue;
                bankByAmount.computeIfAbsent(amountKey(amount), k -> new ArrayList<>()).add(bt);
            }
        }

        List<RapprochementResultDTO.RapprochementMatchDTO> matches = new ArrayList<>();
        int matchedCount = 0;
        Set<Long> usedBankTxIds = new HashSet<>();

        for (CentreMonetiqueTransaction tx : cmTxs) {
            if (!"VPS".equalsIgnoreCase(nvl(tx.getSection()).trim())) continue;
            if (shouldSkipVpsLiaison(tx)) continue;

            BigDecimal creditDebit = tx.getCredit();
            String dateStr = nvl(tx.getDate()).trim();
            BankTransaction bankTx = null;
            if (creditDebit != null) {
                bankTx = pickBestBankTxForAmount(
                        bankByAmount.getOrDefault(amountKey(creditDebit), List.of()),
                        null,
                        usedBankTxIds);
            }

            String bankStatementName = "";
            String bankMontant = "";
            String bankLibelle = "";
            if (bankTx != null) {
                matchedCount++;
                usedBankTxIds.add(bankTx.getId());
                bankStatementName = bankTx.getStatement() != null
                        ? nvl(bankTx.getStatement().getOriginalName()) : "";
                bankMontant = toAmount(bankTxAmount(bankTx));
                bankLibelle = nvl(bankTx.getLibelle());
            }

            matches.add(new RapprochementResultDTO.RapprochementMatchDTO(
                    dateStr,
                    "VPS",
                    toAmount(creditDebit),
                    "",
                    "C",
                    toAmount(tx.getMontant()),
                    bankStatementName,
                    bankMontant,
                    bankLibelle));
        }

        return Optional.of(new RapprochementResultDTO(batchId, rib, cmTxs.size(), matchedCount, matches));
    }

    private List<CmExpansionDTO> buildVpsExpansions(CentreMonetiqueBatch batch,
                                                     List<CentreMonetiqueTransaction> cmTxs,
                                                     List<BankTransaction> statementTxs,
                                                     Set<Long> usedBankTxIds) {
        Map<String, List<BankTransaction>> bankByAmount = new LinkedHashMap<>();
        for (BankTransaction bt : statementTxs) {
            if (usedBankTxIds.contains(bt.getId())) continue;
            BigDecimal amount = bankTxAmount(bt);
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) continue;
            bankByAmount.computeIfAbsent(amountKey(amount), k -> new ArrayList<>()).add(bt);
        }

        List<CmExpansionDTO> result = new ArrayList<>();
        for (CentreMonetiqueTransaction tx : cmTxs) {
            if (!"VPS".equalsIgnoreCase(nvl(tx.getSection()).trim())) continue;
            if (shouldSkipVpsLiaison(tx)) continue;

            BigDecimal creditDebit = tx.getCredit();
            BigDecimal montantTrx = tx.getMontant();
            BigDecimal commHt = tx.getDebit();
            BigDecimal tva = parseDecimalString(nvl(tx.getDcFlag()));
            if (creditDebit == null) continue;

            BankTransaction bankTx = pickBestBankTxForAmount(
                    bankByAmount.getOrDefault(amountKey(creditDebit), List.of()),
                    null,
                    usedBankTxIds);
            if (bankTx == null) continue;
            usedBankTxIds.add(bankTx.getId());

            List<CmExpansionLineDTO> lines = new ArrayList<>();
            lines.add(new CmExpansionLineDTO(nvl(tx.getDate()), "Montant Total Tranx", "C", toAmount(montantTrx)));
            lines.add(new CmExpansionLineDTO(nvl(tx.getDate()), "Total Com HT", "D", toAmount(commHt)));
            if (tva != null && tva.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(new CmExpansionLineDTO(nvl(tx.getDate()), "TVA 10%", "D", toAmount(tva)));
            }

            result.add(new CmExpansionDTO(
                    bankTx.getId(), batch.getId(), nvl(batch.getOriginalName()), nvl(batch.getStructure()),
                    "VPS", toAmount(creditDebit), toAmount(commHt), toAmount(tva), lines));
        }
        return result;
    }

    private Optional<RapprochementResultDTO> buildAmexRapprochement(
            Long batchId, String rib, Long dossierId, List<CentreMonetiqueTransaction> cmTxs) {
        CentreMonetiqueBatch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            return Optional.empty();
        }

        List<BankTransaction> bankCandidates = loadAmexBankCandidates(rib).stream()
                .filter(bt -> belongsToDossierOrLegacyNull(
                        bt.getStatement() != null ? bt.getStatement().getDossierId() : null,
                        dossierId))
                .toList();
        List<BankTransaction> bankTxs = bankCandidates.stream()
                .filter(bt -> {
                    BigDecimal amount = bankTxAmount(bt);
                    return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
                })
                .toList();

        Map<String, List<BankTransaction>> bankByAmount = new LinkedHashMap<>();
        for (BankTransaction bt : bankTxs) {
            bankByAmount.computeIfAbsent(amountKey(bankTxAmount(bt)), k -> new ArrayList<>()).add(bt);
        }
        Set<Long> usedBankTxIds = new HashSet<>();
        return buildAmexRapprochementSettlementLevel(batchId, rib, cmTxs, bankByAmount, usedBankTxIds);
    }

    private List<CmExpansionDTO> buildAmexExpansions(CentreMonetiqueBatch batch,
                                                     List<CentreMonetiqueTransaction> cmTxs,
                                                     List<BankTransaction> statementTxs,
                                                     String statementRib,
                                                     Set<Long> usedBankTxIds) {
        boolean hasSettlementDetails = cmTxs.stream().anyMatch(tx ->
                "AMEX SETTLEMENT".equals(nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT))
                        && tx.getCredit() != null
                        && tx.getCredit().compareTo(BigDecimal.ZERO) > 0
                        && !nvl(tx.getDcFlag()).isBlank());

        boolean processSubmissionDetails = !hasSettlementDetails
                && amexBatchRibMatchesStatement(nvl(batch.getRib()), statementRib);

        if (!processSubmissionDetails && !hasSettlementDetails) {
            return List.of();
        }

        Map<String, List<BankTransaction>> bankByAmount = new LinkedHashMap<>();
        for (BankTransaction bt : statementTxs) {
            if (usedBankTxIds.contains(bt.getId())) continue;
            BigDecimal amount = bankTxAmount(bt);
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) continue;
            bankByAmount.computeIfAbsent(amountKey(amount), k -> new ArrayList<>()).add(bt);
        }

        List<CmExpansionDTO> result = new ArrayList<>();

        CentreMonetiqueTransaction pendingSubmissionTx = null;
        String submissionActualRef = "";
        List<CentreMonetiqueTransaction> submissionTerminalTotals = new ArrayList<>();
        BigDecimal submNetAcc = BigDecimal.ZERO;
        BigDecimal submDiscAcc = BigDecimal.ZERO;

        CentreMonetiqueTransaction pendingSettlementTx = null;
        List<CentreMonetiqueTransaction> settlementDetailLines = new ArrayList<>();

        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);

            if ("AMEX SETTLEMENT".equals(section)) {
                if (pendingSettlementTx != null) {
                    CmExpansionDTO exp = buildSettlementDetailsExpansion(
                            pendingSettlementTx, settlementDetailLines,
                            bankByAmount, usedBankTxIds, statementRib, batch);
                    if (exp != null) result.add(exp);
                    pendingSettlementTx = null;
                    settlementDetailLines = new ArrayList<>();
                }

                if (pendingSubmissionTx != null && submNetAcc.compareTo(BigDecimal.ZERO) > 0) {
                    CmExpansionDTO exp = buildSubmissionDetailsExpansion(
                            pendingSubmissionTx, submissionActualRef, submissionTerminalTotals,
                            submNetAcc, submDiscAcc, bankByAmount, usedBankTxIds, batch);
                    if (exp != null) result.add(exp);
                }
                pendingSubmissionTx = null;
                submissionActualRef = "";
                submissionTerminalTotals = new ArrayList<>();
                submNetAcc = BigDecimal.ZERO;
                submDiscAcc = BigDecimal.ZERO;

                boolean isSettlementDetails = tx.getCredit() != null
                        && tx.getCredit().compareTo(BigDecimal.ZERO) > 0
                        && !nvl(tx.getDcFlag()).isBlank();
                if (isSettlementDetails) {
                    pendingSettlementTx = tx;
                    settlementDetailLines = new ArrayList<>();
                } else if (processSubmissionDetails) {
                    pendingSubmissionTx = tx;
                }
                continue;
            }

            if (pendingSettlementTx != null) {
                if ("AMEX TOTAL TERMINAL".equals(section)) {
                    settlementDetailLines.add(tx);
                }
                continue;
            }

            if (!processSubmissionDetails) {
                continue;
            }

            if ("AMEX TOTAL TERMINAL".equals(section)) {
                if (pendingSubmissionTx != null) {
                    submissionTerminalTotals.add(tx);
                }
                continue;
            }
            if (!"AMEX SUB TOTAL".equals(section)) {
                continue;
            }

            if (pendingSubmissionTx == null) {
                continue;
            }

            BigDecimal subNet = tx.getCredit();
            BigDecimal subDisc = tx.getDebit();
            if (subNet != null) submNetAcc = submNetAcc.add(subNet);
            if (subDisc != null) submDiscAcc = submDiscAcc.add(subDisc);
            if (submissionActualRef.isBlank()) {
                submissionActualRef = nvl(tx.getReference()).trim();
            }
        }

        if (pendingSettlementTx != null) {
            CmExpansionDTO exp = buildSettlementDetailsExpansion(
                    pendingSettlementTx, settlementDetailLines,
                    bankByAmount, usedBankTxIds, statementRib, batch);
            if (exp != null) result.add(exp);
        }

        if (pendingSubmissionTx != null && submNetAcc.compareTo(BigDecimal.ZERO) > 0) {
            CmExpansionDTO exp = buildSubmissionDetailsExpansion(
                    pendingSubmissionTx, submissionActualRef, submissionTerminalTotals,
                    submNetAcc, submDiscAcc, bankByAmount, usedBankTxIds, batch);
            if (exp != null) result.add(exp);
        }

        return result;
    }

    private CmExpansionDTO buildSubmissionDetailsExpansion(
            CentreMonetiqueTransaction settlementTx,
            String settlementRef,
            List<CentreMonetiqueTransaction> terminalTotals,
            BigDecimal totalNet,
            BigDecimal totalDisc,
            Map<String, List<BankTransaction>> bankByAmount,
            Set<Long> usedBankTxIds,
            CentreMonetiqueBatch batch) {

        String settlDate = nvl(settlementTx.getDate());
        BankTransaction bankTx = pickBestBankTxForAmount(
                bankByAmount.getOrDefault(amountKey(totalNet), List.of()),
                parseRowDate(settlDate), usedBankTxIds);
        if (bankTx == null) return null;
        usedBankTxIds.add(bankTx.getId());

        List<CmExpansionLineDTO> lines = new ArrayList<>();
        if (!terminalTotals.isEmpty()) {
            for (CentreMonetiqueTransaction tt : terminalTotals) {
                String ttDate = !nvl(tt.getDate()).isBlank() ? tt.getDate() : settlDate;
                String ttTid = nvl(tt.getReference()).trim();
                BigDecimal ttNet = tt.getCredit();
                if (ttNet == null || ttNet.compareTo(BigDecimal.ZERO) <= 0) {
                    ttNet = tt.getMontant();
                }
                lines.add(new CmExpansionLineDTO(ttDate,
                        ttTid.isBlank() ? "Total Terminal" : "TID " + ttTid,
                        "C", toAmount(ttNet)));
            }
        } else {
            lines.add(new CmExpansionLineDTO(settlDate, "Net Amount", "C", toAmount(totalNet)));
        }

        String ref = nvl(settlementRef).trim();
        String cmRef = "AMEX" + (ref.isBlank() ? "" : " / " + ref);
        return new CmExpansionDTO(
                bankTx.getId(), batch.getId(), nvl(batch.getOriginalName()), nvl(batch.getStructure()),
                cmRef, toAmount(totalNet), toAmount(totalDisc), "",
                lines);
    }

    private CmExpansionDTO buildSettlementDetailsExpansion(
            CentreMonetiqueTransaction settlementTx,
            List<CentreMonetiqueTransaction> detailLines,
            Map<String, List<BankTransaction>> bankByAmount,
            Set<Long> usedBankTxIds,
            String statementRib,
            CentreMonetiqueBatch batch) {

        String ibanLast5 = normalizeRibDigits(nvl(settlementTx.getDcFlag()));
        BigDecimal netAmount = settlementTx.getCredit();
        BigDecimal submissionAmount = settlementTx.getMontant() != null ? settlementTx.getMontant() : null;
        BigDecimal discountAmount = settlementTx.getDebit();

        String normalizedStatementRib = normalizeRibDigits(statementRib);
        if (ibanLast5 != null && normalizedStatementRib != null && !normalizedStatementRib.endsWith(ibanLast5)) {
            return null;
        }

        String settlDate = nvl(settlementTx.getDate());
        BankTransaction bankTx = pickBestBankTxForAmount(
                bankByAmount.getOrDefault(amountKey(netAmount), List.of()),
                parseRowDate(settlDate), usedBankTxIds);
        if (bankTx == null) return null;
        usedBankTxIds.add(bankTx.getId());

        List<CmExpansionLineDTO> lines = new ArrayList<>();
        if (!detailLines.isEmpty()) {
            for (CentreMonetiqueTransaction detail : detailLines) {
                BigDecimal detailNet = detail.getCredit();
                if (detailNet == null || detailNet.compareTo(BigDecimal.ZERO) <= 0) {
                    detailNet = detail.getMontant();
                }
                String detailDate = !nvl(detail.getDate()).isBlank() ? detail.getDate() : settlDate;
                String tid = nvl(detail.getReference()).trim();
                String detailLabel = "TID " + (tid.isBlank() ? "—" : tid);
                lines.add(new CmExpansionLineDTO(detailDate, detailLabel, "C", toAmount(detailNet)));
            }
        } else {
            lines.add(new CmExpansionLineDTO(settlDate, "Net Settlement", "C", toAmount(netAmount)));
        }

        String ref = nvl(settlementTx.getReference()).trim();
        String cmRef = "AMEX" + (ref.isBlank() ? "" : " / " + ref);
        BigDecimal resolvedSubmission = submissionAmount != null ? submissionAmount
                : (discountAmount != null ? netAmount.add(discountAmount) : null);

        return new CmExpansionDTO(
                bankTx.getId(), batch.getId(), nvl(batch.getOriginalName()), nvl(batch.getStructure()),
                cmRef, toAmount(netAmount), toAmount(discountAmount), "",
                lines);
    }

    private Optional<RapprochementResultDTO> buildAmexRapprochementSettlementLevel(
            Long batchId, String rib,
            List<CentreMonetiqueTransaction> cmTxs,
            Map<String, List<BankTransaction>> bankByAmount,
            Set<Long> usedBankTxIds) {

        Map<String, BigDecimal> netByRef = new LinkedHashMap<>();
        Map<String, String> dateByRef = new LinkedHashMap<>();
        String currentRef = "";
        String currentDate = "";
        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);
            if ("AMEX SETTLEMENT".equals(section)) {
                currentRef = nvl(tx.getReference()).trim();
                currentDate = nvl(tx.getDate()).trim();
                if (!currentRef.isBlank()) {
                    dateByRef.putIfAbsent(currentRef, currentDate);
                }
            } else if ("AMEX SUB TOTAL".equals(section)) {
                String ref = nvl(tx.getReference()).trim();
                if (ref.isBlank()) ref = currentRef;
                if (ref.isBlank()) continue;
                BigDecimal net = tx.getCredit() == null ? BigDecimal.ZERO : tx.getCredit();
                netByRef.merge(ref, net, BigDecimal::add);
                dateByRef.putIfAbsent(ref, nvl(tx.getDate()).trim());
            }
        }

        if (netByRef.isEmpty()) {
            return Optional.of(new RapprochementResultDTO(batchId, rib, 0, 0, List.of()));
        }

        Map<String, List<CentreMonetiqueTransaction>> terminalsByRef = new LinkedHashMap<>();
        String trackingRef = "";
        for (CentreMonetiqueTransaction tx : cmTxs) {
            String section = nvl(tx.getSection()).trim().toUpperCase(Locale.ROOT);
            if ("AMEX SETTLEMENT".equals(section)) {
                trackingRef = nvl(tx.getReference()).trim();
            } else if ("AMEX TOTAL TERMINAL".equals(section) && !trackingRef.isBlank()) {
                terminalsByRef.computeIfAbsent(trackingRef, k -> new ArrayList<>()).add(tx);
            }
        }

        List<RapprochementResultDTO.RapprochementMatchDTO> matches = new ArrayList<>();
        int matchedCount = 0;
        int totalCount = 0;

        for (Map.Entry<String, BigDecimal> entry : netByRef.entrySet()) {
            String settlementRef = entry.getKey();
            BigDecimal netAmount = entry.getValue();
            if (netAmount == null || netAmount.compareTo(BigDecimal.ZERO) <= 0) continue;

            String settlementDate = nvl(dateByRef.get(settlementRef));
            BankTransaction bankTx = pickBestBankTxForAmount(
                    bankByAmount.getOrDefault(amountKey(netAmount), List.of()),
                    parseRowDate(settlementDate),
                    usedBankTxIds);

            String bankStatementName = "";
            String bankMontant = "";
            String bankLibelle = "";
            if (bankTx != null) {
                usedBankTxIds.add(bankTx.getId());
                bankStatementName = bankTx.getStatement() != null
                        ? nvl(bankTx.getStatement().getOriginalName()) : "";
                bankMontant = toAmount(bankTxAmount(bankTx));
                bankLibelle = nvl(bankTx.getLibelle());
            }

            String cmRef = "AMEX / " + settlementRef;
            List<CentreMonetiqueTransaction> terminals = terminalsByRef.getOrDefault(settlementRef, List.of());
            if (terminals.isEmpty()) {
                totalCount++;
                matches.add(new RapprochementResultDTO.RapprochementMatchDTO(
                        settlementDate,
                        cmRef,
                        toAmount(netAmount),
                        settlementRef,
                        "C",
                        toAmount(netAmount),
                        bankStatementName,
                        bankMontant,
                        bankLibelle));
                if (bankTx != null) matchedCount++;
            } else {
                for (CentreMonetiqueTransaction terminal : terminals) {
                    totalCount++;
                    String tid = nvl(terminal.getReference()).trim();
                    matches.add(new RapprochementResultDTO.RapprochementMatchDTO(
                            settlementDate,
                            cmRef,
                            toAmount(netAmount),
                            tid.isBlank() ? settlementRef : "TID " + tid,
                            "C",
                            toAmount(terminal.getCredit()),
                            bankStatementName,
                            bankMontant,
                            bankLibelle));
                    if (bankTx != null) matchedCount++;
                }
            }
        }

        return Optional.of(new RapprochementResultDTO(batchId, rib, totalCount, matchedCount, matches));
    }

    private List<BankTransaction> loadAmexBankCandidates(String rib) {
        String normalizedRib = normalizeRibDigits(rib);
        if (normalizedRib == null || normalizedRib.isBlank()) return List.of();
        if (normalizedRib.length() <= 5) {
            List<BankTransaction> result = new ArrayList<>();
            for (String fullRib : bankStatementRepository.findDistinctRibsEndingWith(normalizedRib)) {
                result.addAll(bankTransactionRepository.findCreditTransactionsByRib(fullRib));
            }
            return result;
        }
        List<BankTransaction> direct = bankTransactionRepository.findCreditTransactionsByRib(normalizedRib);
        if (!direct.isEmpty()) {
            return direct;
        }

        String suffix = normalizedRib.substring(Math.max(0, normalizedRib.length() - 5));
        if (suffix.isBlank() || suffix.equals(normalizedRib)) {
            return direct;
        }

        List<BankTransaction> fallback = new ArrayList<>();
        for (String fullRib : bankStatementRepository.findDistinctRibsEndingWith(suffix)) {
            fallback.addAll(bankTransactionRepository.findCreditTransactionsByRib(fullRib));
        }
        return fallback;
    }

    private BankTransaction pickBestBankTxForAmount(List<BankTransaction> candidates,
                                                    LocalDate preferredDate,
                                                    Set<Long> usedBankTxIds) {
        if (candidates == null || candidates.isEmpty()) return null;
        for (BankTransaction bt : candidates) {
            if (usedBankTxIds.contains(bt.getId())) continue;
            if (preferredDate != null && preferredDate.equals(bt.getDateOperation())) return bt;
        }
        for (BankTransaction bt : candidates) {
            if (!usedBankTxIds.contains(bt.getId())) return bt;
        }
        return null;
    }

    private Integer extractPeriodMonth(String statementPeriod) {
        if (statementPeriod == null || statementPeriod.isBlank()) return null;
        Matcher matcher = Pattern.compile("(\\d{2})/(\\d{4})").matcher(statementPeriod);
        if (!matcher.find()) return null;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Exception e) {
            return null;
        }
    }

    private Integer extractPeriodYear(String statementPeriod) {
        if (statementPeriod == null || statementPeriod.isBlank()) return null;
        Matcher matcher = Pattern.compile("(\\d{2})/(\\d{4})").matcher(statementPeriod);
        if (!matcher.find()) return null;
        try {
            return Integer.parseInt(matcher.group(2));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean batchPeriodMatchesStatement(CentreMonetiqueBatch batch, BankStatement statement) {
        if (batch == null || statement == null) return false;
        Integer month = statement.getMonth();
        Integer year = statement.getYear();
        if (month == null || year == null) return false;

        Integer batchMonth = extractPeriodMonth(batch.getStatementPeriod());
        Integer batchYear = extractPeriodYear(batch.getStatementPeriod());
        if (batchMonth == null || batchYear == null) return false;
        return batchMonth.equals(month) && batchYear.equals(year);
    }

    private boolean isCreditTransaction(BankTransaction tx) {
        BigDecimal credit = tx.getCredit() == null ? BigDecimal.ZERO : tx.getCredit();
        BigDecimal debit = tx.getDebit() == null ? BigDecimal.ZERO : tx.getDebit();
        return credit.compareTo(debit) >= 0;
    }

    private BigDecimal bankTxAmount(BankTransaction tx) {
        if (tx == null) return null;
        BigDecimal credit = tx.getCredit() == null ? BigDecimal.ZERO : tx.getCredit();
        BigDecimal debit = tx.getDebit() == null ? BigDecimal.ZERO : tx.getDebit();
        return credit.compareTo(BigDecimal.ZERO) > 0 ? credit : debit;
    }

    private boolean amexBatchRibMatchesStatement(String batchRib, String statementRib) {
        return matchesAmexRib(batchRib, statementRib);
    }

    private String normalizeRibDigits(String rib) {
        if (rib == null) return null;
        String digits = rib.replaceAll("\\D", "");
        return digits.isBlank() ? null : digits;
    }

    private boolean matchesAmexRib(String batchRib, String statementRib) {
        String b = normalizeRibDigits(batchRib);
        String s = normalizeRibDigits(statementRib);
        if (b == null || b.isBlank() || s == null || s.isBlank()) return false;
        if (s.equals(b)) return true;

        String bSuffix = lastDigits(b, 5);
        String sSuffix = lastDigits(s, 5);
        if (bSuffix != null && bSuffix.equals(sSuffix)) return true;

        if (b.length() <= 5 && s.endsWith(b)) return true;
        if (s.length() <= 5 && b.endsWith(s)) return true;
        return false;
    }

    private String lastDigits(String value, int length) {
        if (value == null || value.isBlank() || length <= 0) {
            return null;
        }
        if (value.length() <= length) {
            return value;
        }
        return value.substring(value.length() - length);
    }

    private boolean shouldSkipVpsLiaison(CentreMonetiqueTransaction tx) {
        if (tx == null) return true;
        BigDecimal commHt = tx.getDebit() == null ? BigDecimal.ZERO : tx.getDebit();
        BigDecimal tva = parseDecimalString(nvl(tx.getDcFlag()));
        BigDecimal normalizedTva = tva == null ? BigDecimal.ZERO : tva;
        return commHt.compareTo(BigDecimal.ZERO) == 0
                && normalizedTva.compareTo(BigDecimal.ZERO) == 0;
    }

    private BigDecimal parseDecimalString(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.trim().replace(",", "."));
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal firstNonZero(BigDecimal... values) {
        if (values == null) return null;
        for (BigDecimal v : values) {
            if (v != null && v.compareTo(BigDecimal.ZERO) != 0) {
                return v;
            }
        }
        return null;
    }

    private BigDecimal safeAmount(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) != 0 ? value : null;
    }

    private String toAmount(BigDecimal amount) {
        if (amount == null) return "";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private boolean belongsToDossierOrLegacyNull(Long recordDossierId, Long activeDossierId) {
        if (activeDossierId == null) {
            return true;
        }
        return Objects.equals(recordDossierId, activeDossierId) || recordDossierId == null;
    }

    private Optional<CentreMonetiqueBatch> findBatchForDossier(Long batchId, Long activeDossierId) {
        if (batchId == null) {
            return Optional.empty();
        }
        return batchRepository.findById(batchId)
                .filter(batch -> belongsToDossierOrLegacyNull(batch.getDossierId(), activeDossierId));
    }

    private Optional<BankStatement> findStatementForDossier(Long statementId, Long activeDossierId) {
        if (statementId == null) {
            return Optional.empty();
        }
        return bankStatementRepository.findById(statementId)
                .filter(statement -> belongsToDossierOrLegacyNull(statement.getDossierId(), activeDossierId));
    }
}
