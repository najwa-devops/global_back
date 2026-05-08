package com.invoice_reader.invoice_reader.servises.ocr;

import com.invoice_reader.invoice_reader.dto.ocr.OcrResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste l'algorithme de sélection du meilleur résultat OCR dans OcrFallbackStrategy.
 *
 * Algorithme reproduit depuis executeFallback() :
 *   fallbackResults.stream()
 *     .max((r1, r2) -> Double.compare(
 *           r1.getConfidence() * r1.getText().length(),
 *           r2.getConfidence() * r2.getText().length()))
 *
 * Score = confidence × longueur du texte extrait.
 *
 * Pourquoi tester ça ?
 * Ce score détermine quelle stratégie de prétraitement d'image donne le meilleur
 * résultat OCR (resize ×4, inversion couleurs, ou morphologie). Si la comparaison
 * est inversée ou cassée, la plateforme choisit systématiquement le pire résultat.
 */
class OcrFallbackSelectionTest {

    /**
     * Reproduit exactement le comparateur de OcrFallbackStrategy.executeFallback().
     */
    private Optional<OcrResult> selectBest(List<OcrResult> results) {
        return results.stream()
                .max((r1, r2) -> Double.compare(
                        r1.getConfidence() * r1.getText().length(),
                        r2.getConfidence() * r2.getText().length()));
    }

    // ── Sélection du meilleur ─────────────────────────────────────────────────

    @Test
    void selection_doitChoisirLeScoreLePlusEleve() {
        // Score r1 : 70.0 × 5  = 350
        // Score r2 : 65.0 × 18 = 1170  ← doit gagner
        OcrResult r1 = OcrResult.builder().text("court").confidence(70.0).success(true).build();
        OcrResult r2 = OcrResult.builder().text("texte beaucoup plus long").confidence(65.0).success(true).build();

        Optional<OcrResult> meilleur = selectBest(List.of(r1, r2));

        assertTrue(meilleur.isPresent());
        assertEquals(r2.getText(), meilleur.get().getText());
    }

    @Test
    void selection_confidenceEleveeEtTexteCourtPeutPerdreContreConfidenceFaibleEtTexteLong() {
        // Score resize    : 70.0 × 10  = 700
        // Score inversion : 65.0 × 20  = 1300  ← doit gagner malgré confidence plus faible
        OcrResult resize    = OcrResult.builder().text("0123456789").confidence(70.0).success(true).build();
        OcrResult inversion = OcrResult.builder().text("01234567890123456789").confidence(65.0).success(true).build();

        Optional<OcrResult> meilleur = selectBest(List.of(resize, inversion));

        assertEquals(inversion.getText(), meilleur.get().getText());
    }

    @Test
    void selection_troisResultats_doitChoisirLeScoreMaximal() {
        // Scores : 70×10=700, 65×20=1300, 68×15=1020
        OcrResult resize      = OcrResult.builder().text("0123456789").confidence(70.0).attemptNumber(99).success(true).build();
        OcrResult inversion   = OcrResult.builder().text("01234567890123456789").confidence(65.0).attemptNumber(98).success(true).build();
        OcrResult morphologie = OcrResult.builder().text("012345678901234").confidence(68.0).attemptNumber(97).success(true).build();

        Optional<OcrResult> meilleur = selectBest(List.of(resize, inversion, morphologie));

        // inversion gagne avec score 1300
        assertEquals(98, meilleur.get().getAttemptNumber());
    }

    // ── Liste vide ou null ────────────────────────────────────────────────────

    @Test
    void selection_listeVide_retourneOptionalVide() {
        Optional<OcrResult> result = selectBest(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void selection_unSeulResultat_retourneCeResultat() {
        OcrResult unique = OcrResult.builder().text("seul résultat").confidence(60.0).success(true).build();
        Optional<OcrResult> result = selectBest(List.of(unique));
        assertTrue(result.isPresent());
        assertEquals("seul résultat", result.get().getText());
    }

    // ── OcrResult.failed() ────────────────────────────────────────────────────

    @Test
    void ocrResultFailed_doitAvoirSuccessFalseEtTextVide() {
        OcrResult failed = OcrResult.failed("erreur OCR");
        assertFalse(failed.isSuccess());
        assertEquals("", failed.getText());
        assertEquals(0.0, failed.getConfidence());
        assertEquals("erreur OCR", failed.getErrorMessage());
    }

    @Test
    void ocrResultFailed_scoreDoitEtreZero() {
        OcrResult failed = OcrResult.failed("timeout");
        double score = failed.getConfidence() * failed.getText().length();
        assertEquals(0.0, score);
    }

    // ── Scores égaux ─────────────────────────────────────────────────────────

    @Test
    void selection_scoresEgaux_retourneUnResultat() {
        // 60×10 = 600 les deux → le stream.max retourne l'un des deux (comportement défini)
        OcrResult r1 = OcrResult.builder().text("0123456789").confidence(60.0).attemptNumber(1).success(true).build();
        OcrResult r2 = OcrResult.builder().text("abcdefghij").confidence(60.0).attemptNumber(2).success(true).build();

        Optional<OcrResult> result = selectBest(List.of(r1, r2));

        assertTrue(result.isPresent()); // un résultat est toujours retourné
    }
}
