# Dossier `utils/`

Package : `com.invoice_reader.invoice_reader.utils`

Ce dossier contient les **utilitaires partagés** entre plusieurs modules. Toutes les classes sont soit des utilitaires statiques (pas d'état, pas de bean Spring), soit des exceptions métier transversales.

> Les utilitaires spécifiques à un module se trouvent dans leurs packages propres : `achat/service/pattern/`, `vente/utils/`, `banque/service/`.

---

## Fichiers

| Fichier | Rôle |
|---------|------|
| `ExtractionPatterns.java` | Constantes de **patterns regex partagés** pour l'extraction des identifiants fiscaux marocains depuis le texte OCR : IF (Identifiant Fiscal), ICE (Identifiant Commun de l'Entreprise), RC (Registre de Commerce), numéro de TVA. Couvre les variantes orthographiques et de mise en forme. Utilisé par les modules `achat` et `vente` |
| `InvoiceTypeDetector.java` | Utilitaire statique de **détection du type de document** : analyse les mots-clés du texte OCR pour distinguer `FACTURE`, `AVOIR`, `PROFORMA`, `BON_COMMANDE`. Expose `isAvoir()` et d'autres méthodes booléennes. Partagé entre `achat`, `vente` et le module comptabilité |
| `AmountToWordsFormatter.java` | Utilitaire statique de **conversion de montant en lettres** en français et en arabe (normes comptables marocaines). Utilisé pour la génération de documents et la validation de factures |
| `ExercisePeriodException.java` | `RuntimeException` levée quand une date de document est **hors de l'exercice comptable actif** du dossier. Contient les dates de début/fin de l'exercice et la date du document incriminé |
| `LogHelper.java` | Classe utilitaire pour la **journalisation structurée** : formate les messages de log avec des préfixes standardisés (module, action, durée), masque les données sensibles (ICE, IF) dans les logs de production |
| `FileStorageService.java` | Service Spring de **stockage physique générique** : enregistre un fichier uploadé (`MultipartFile`) dans le répertoire configuré par `invoice.upload.dir` (application.properties), retourne le chemin relatif. Partagé entre les modules `achat`, `vente` et `banque` |
