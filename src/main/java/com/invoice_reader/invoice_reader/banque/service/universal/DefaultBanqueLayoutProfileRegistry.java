package com.invoice_reader.invoice_reader.banque.service.universal;

import com.invoice_reader.invoice_reader.banque.entity.BanqueType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class DefaultBanqueLayoutProfileRegistry implements BanqueLayoutProfileRegistry {

    private static final Set<String> BASE_CREDIT = Set.of(
            "CREDIT",
            "CREDIT VIREMENT",
            "CREDIT INTERETS",
            "CREDIT CLIENT",
            "CREDIT EN COMPTE",
            "CREDIT INSTANTANE",
            "CREDIT SEPA",
            "CRDT",
            "CR",
            "VIREMENT RECU",
            "VIREMENT RECU DE",
            "VIREMENT INSTANTANE RECU",
            "VIREMENT INSTANTANE RECU DE",
            "VIREMENT ENTRANT",
            "VIREMENT EN FAVEUR",
            "VIR RECU",
            "VIR.RECU",
            "VIR INSTANT RECU",
            "VIR INST RECU",
            "VIR CREDIT",
            "TRANSFERT RECU",
            "RECEPTION VIREMENT",
            "RECEPTION D'UN VIREMENT",
            "VENTE PAR CARTE",
            "VENTE CARTE",
            "REMISE",
            "REMISE CHEQUE",
            "REMISE CHEQUES",
            "REMISE EFFETS",
            "REMISE LCN",
            "VERSEMENT",
            "VERSEMENT ESPECES",
            "DEPOT",
            "DEPOT ESPECES",
            "ALIMENTATION",
            "APPROVISIONNEMENT",
            "ENCAISSEMENT",
            "ENCAISSEMENT CHEQUE",
            "ENCAISSEMENT LCN",
            "REGLEMENT RECU",
            "REGLEMENT CLIENT",
            "PAIEMENT RECU",
            "REMBOURSEMENT",
            "REVERSEMENT",
            "INTERETS",
            "INTERET CREDITEUR",
            "BONIFICATION",
            "SALAIRE",
            "PAYROLL",
            "SALARY",
            "DIVIDENDE",
            "AVOIR",
            "RISTOURNE",
            "CASH IN",
            "REFUND");
    private static final Set<String> BASE_DEBIT = Set.of(
            "DEBIT",
            "OPERATION AU DEBIT",
            "DEBIT DIFFERE",
            "DEBIT CARTE",
            "DEBIT CB",
            "DEBIT SEPA",
            "DBT",
            "DR",
            "RETRAIT",
            "RETRAIT GAB",
            "RETRAIT DAB",
            "GAB",
            "DAB",
            "PRELEVEMENT",
            "PRELEVEMENT SEPA",
            "PRELEVEMENT AUTOMATIQUE",
            "PRLV",
            "PAIEMENT",
            "PAIEMENT CB",
            "PAIEMENT CARTE",
            "ACHAT",
            "ACHAT TPE",
            "ACHAT ECOM",
            "ACHAT EN LIGNE",
            "CHEQUE",
            "CHQ",
            "EMISSION CHEQUE",
            "COTISATION",
            "ABONNEMENT",
            "FRAIS",
            "FRAIS BANCAIRES",
            "FRAIS TIMBRE",
            "FRAIS DOSSIER",
            "COMMISSION",
            "AGIOS",
            "TAXE",
            "TVA",
            "TIMBRE",
            "VIREMENT EMIS",
            "VIR.EMIS",
            "VIR EMIS",
            "VIREMENT SORTANT",
            "EMISSION D'UN VIREMENT",
            "EMISSION VIREMENT",
            "TRANSFERT EMIS",
            "TRANSFERT CASH",
            "REGLEMENT FOURNISSEUR",
            "REGLEMENT",
            "ECHEANCE",
            "LOAN PAYMENT",
            "CASH OUT",
            "WITHDRAWAL",
            "PURCHASE",
            "DIRECT DEBIT");
    private static final Set<String> BASE_IGNORED = Set.of(
            "SOLDE INITIAL", "SOLDE FINAL", "TOTAL", "CUMUL", "DATE OP", "DATE VAL", "LIBELLE", "DEBIT", "CREDIT");

    private final BanqueLayoutProfile defaultProfile = new StaticBankLayoutProfile(BASE_CREDIT, BASE_DEBIT, BASE_IGNORED);
    private final Map<BanqueType, BanqueLayoutProfile> profiles = Map.of(
            BanqueType.ATTIJARIWAFA, new StaticBankLayoutProfile(BASE_CREDIT, BASE_DEBIT, BASE_IGNORED),
            BanqueType.BCP, new StaticBankLayoutProfile(BASE_CREDIT, BASE_DEBIT, BASE_IGNORED),
            BanqueType.BMCE, new StaticBankLayoutProfile(BASE_CREDIT, BASE_DEBIT, BASE_IGNORED),
            BanqueType.CIH, new StaticBankLayoutProfile(BASE_CREDIT, BASE_DEBIT, BASE_IGNORED),
            BanqueType.CREDIT_DU_MAROC, new StaticBankLayoutProfile(BASE_CREDIT, BASE_DEBIT, BASE_IGNORED),
            BanqueType.BMCI, new StaticBankLayoutProfile(BASE_CREDIT, BASE_DEBIT, BASE_IGNORED),
            BanqueType.SOCIETE_GENERALE, new StaticBankLayoutProfile(BASE_CREDIT, BASE_DEBIT, BASE_IGNORED),
            BanqueType.CREDIT_AGRICOLE, new StaticBankLayoutProfile(BASE_CREDIT, BASE_DEBIT, BASE_IGNORED),
            BanqueType.SAHAM_BANK, new StaticBankLayoutProfile(BASE_CREDIT, BASE_DEBIT, BASE_IGNORED),
            BanqueType.BARID_BANK, new StaticBankLayoutProfile(BASE_CREDIT, BASE_DEBIT, BASE_IGNORED));

    @Override
    public BanqueLayoutProfile getProfile(BanqueType bankType) {
        if (bankType == null) {
            return defaultProfile;
        }
        return profiles.getOrDefault(bankType, defaultProfile);
    }

    private record StaticBankLayoutProfile(
            Set<String> creditHints,
            Set<String> debitHints,
            Set<String> ignoredLineHints) implements BanqueLayoutProfile {
    }
}
