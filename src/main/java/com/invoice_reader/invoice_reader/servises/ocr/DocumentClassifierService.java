package com.invoice_reader.invoice_reader.servises.ocr;

import com.invoice_reader.invoice_reader.entity.dynamic.DocumentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SERVICE DE CLASSIFICATION DE DOCUMENTS
 *
 * Classifie automatiquement le type d'un document OCR par scoring de mots-clés.
 * Retourne le DocumentType le plus probable, ou UNKNOWN si le score est insuffisant.
 */
@Service
public class DocumentClassifierService {

    private static final Logger log = LoggerFactory.getLogger(DocumentClassifierService.class);

    private static final int MIN_SCORE_THRESHOLD = 5;

    /**
     * Map de mots-clés par type de document.
     * Ordre important : les types les plus spécifiques sont évalués en premier.
     */
    private static final Map<DocumentType, List<String>> KEYWORDS = new LinkedHashMap<>();

    static {
        // Types spécifiques avec score élevé (priorité haute)
        KEYWORDS.put(DocumentType.AVOIR, List.of(
                "avoir", "note de crédit", "note de credit", "annulation",
                "remboursement", "credit note"
        ));
        KEYWORDS.put(DocumentType.BON_LIVRAISON, List.of(
                "bon de livraison", "bl n°", "bon livraison", "bon de l"
        ));
        KEYWORDS.put(DocumentType.DEVIS, List.of(
                "devis", "offre de prix", "pro forma", "proforma", "cotation"
        ));
        KEYWORDS.put(DocumentType.RECU, List.of(
                "recu", "reçu", "ticket", "quittance", "récépissé"
        ));
        KEYWORDS.put(DocumentType.FACTURE_VENTE, List.of(
                "facture client", "facture de vente", "vendu à", "vendu a"
        ));
        // Type générique — score faible, évalué en dernier
        KEYWORDS.put(DocumentType.FACTURE_ACHAT, List.of(
                "facture", "fournisseur", "net à payer", "net a payer",
                "total ttc", "montant ttc", "à régler", "a regler"
        ));
    }

    /**
     * Classifie un document par scoring de mots-clés.
     *
     * @param cleanedText texte OCR nettoyé (produit par TextCleaningService)
     * @return le DocumentType le plus probable, ou UNKNOWN si score < seuil
     */
    public DocumentType classify(String cleanedText) {
        if (cleanedText == null || cleanedText.isBlank()) {
            return DocumentType.UNKNOWN;
        }

        String lower = cleanedText.toLowerCase();

        DocumentType best = DocumentType.UNKNOWN;
        int bestScore = 0;

        for (Map.Entry<DocumentType, List<String>> entry : KEYWORDS.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword)) {
                    // Mots-clés spécifiques valent plus
                    score += keyword.length() > 8 ? 10 : 5;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = entry.getKey();
            }
        }

        if (bestScore < MIN_SCORE_THRESHOLD) {
            log.debug("DocumentClassifierService: score insuffisant ({}) — retour UNKNOWN", bestScore);
            return DocumentType.UNKNOWN;
        }

        log.debug("DocumentClassifierService: type={} score={}", best, bestScore);
        return best;
    }
}
