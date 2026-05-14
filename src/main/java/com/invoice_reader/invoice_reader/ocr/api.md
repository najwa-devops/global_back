# API — Module `ocr`

Package racine : `com.invoice_reader.invoice_reader.ocr`

Ce module contient l'infrastructure OCR partagée par tous les autres modules (factures dynamiques, factures vente, relevés bancaires). Les OCR spécialisés restent dans leurs modules propres (`bank/service/ocr/`, `sales/service/ocr/`, `bank/centremonetique/service/`).

---

## Structure

```
ocr/
├── config/     ← Configuration Tesseract + migration BDD
├── dto/        ← Objets de transfert (requête, résultat, config preprocessing)
└── service/    ← Tous les services OCR core (moteurs, validation, qualité, utilitaires)
```

---

## ocr/config

| Classe | Rôle |
|--------|------|
| `TesseractConfig` | Bean Spring `@Configuration` : initialise et expose le `Tesseract` (Tess4J) avec la langue, le mode de segmentation et le chemin des données d'entraînement depuis `application.properties` |
| `OcrSchemaMigration` | Migration DDL idempotente : ajoute les colonnes OCR communes (`ocr_raw_text`, `ocr_confidence`, `ocr_engine`, etc.) sur les tables `dynamic_invoice` et `sales_invoice` si elles n'existent pas encore |

---

## ocr/dto

| Classe | Rôle |
|--------|------|
| `OcrRequest` | DTO d'entrée pour une demande OCR : chemin du fichier, langue cible, mode d'extraction (`InvoiceOcrMode`), options de preprocessing |
| `OcrResult` | DTO de sortie OCR : texte extrait, score de confiance, moteur utilisé (Tesseract / PaddleOCR / OlmOCR), durée de traitement, erreurs éventuelles |
| `PreprocessingConfig` | DTO de configuration du prétraitement image : résolution cible (DPI), binarisation, deskew, débruitage, amélioration du contraste |

---

## ocr/service

### Moteurs OCR

| Classe | Rôle |
|--------|------|
| `AdvancedOcrService` | Service OCR principal : orchestre le pipeline complet (prétraitement → moteur → nettoyage → post-traitement). Choisit dynamiquement entre Tesseract, PaddleOCR et OlmOCR selon la qualité du document |
| `TesseractConfigService` | Gère la configuration fine de Tesseract à l'exécution : mode PSM, liste blanche de caractères, variables de configuration avancées par type de document |
| `PaddleOcrService` | Interface avec le service PaddleOCR Python (via HTTP ou process) : envoi de l'image, réception du texte structuré avec positions des blocs |
| `OlmocrFallbackService` | Fallback OCR basé sur OlmOCR (modèle vision) : utilisé pour les documents dégradés ou les images où Tesseract et PaddleOCR échouent |
| `OcrFallbackStrategy` | Stratégie de cascade OCR : tente les moteurs dans l'ordre (Tesseract → PaddleOCR → OlmOCR), retourne le premier résultat au-dessus du seuil de confiance minimum |

### Prétraitement image

| Classe | Rôle |
|--------|------|
| `ImagePreprocessingService` | Prépare une image avant OCR : redressement (deskew), binarisation, augmentation DPI, suppression du bruit, normalisation des contrastes. Utilise OpenCV/Leptonica |

### Nettoyage et post-traitement texte

| Classe | Rôle |
|--------|------|
| `TextCleaningService` | Nettoie le texte OCR brut : corrige les artefacts Tesseract, normalise les espaces et tirets, harmonise les séparateurs de milliers/décimaux marocains. **Préserve les marqueurs `[HEADER]`, `[BODY]`, `[FOOTER]`** utilisés par l'extracteur de champs dynamique |
| `OcrPostProcessor` | Post-traitement structuré : segmente le texte en zones (en-tête, corps, pied de page), insère les marqueurs de zone, prépare le texte pour l'extraction de champs |

### Qualité et classification

| Classe | Rôle |
|--------|------|
| `DocumentQualityScoringService` | Calcule un score de qualité (0–100) du résultat OCR : densité du texte, ratio caractères reconnus, cohérence des blocs. Oriente vers un moteur de fallback si le score est bas |
| `DocumentQualityAssessment` | POJO résultat de l'évaluation qualité : score, niveau de difficulté (`DocumentDifficultyClass`), recommandations de retraitement |
| `DocumentDifficultyClass` | Enum de difficulté du document : `EASY` (PDF texte natif), `MEDIUM` (scan propre), `HARD` (scan dégradé), `VERY_HARD` (image manuscrite ou très basse résolution) |
| `DocumentClassifierService` | Classifie le type de document à partir du texte OCR par scoring de mots-clés. Retourne le `DocumentType` le plus probable (`INVOICE`, `BANK_STATEMENT`, `RECEIPT`, `UNKNOWN`) |

### Validation

| Classe | Rôle |
|--------|------|
| `OcrValidationService` | Valide que le texte OCR extrait est exploitable : longueur minimale, présence de champs obligatoires, absence de corruption binaire |
| `BusinessValidationService` | Valide les règles métier d'une facture extraite : ICE valide (15 chiffres), numéro de facture non-vide, date dans une plage raisonnable |
| `AmountValidatorService` | Vérifie la cohérence des montants : `TTC = HT + TVA` avec une tolérance de 5 centimes. Calcule automatiquement le champ manquant si deux des trois valeurs sont connues |

### Détection des doublons

| Classe | Rôle |
|--------|------|
| `DuplicateDetectionService` | Détecte les factures déjà traitées selon deux stratégies : (1) **doublon certain** — même numéro de facture + même ICE dans le même dossier ; (2) **doublon probable** — similarité de montant et de date dans une fenêtre temporelle |

### Données partagées

| Classe | Rôle |
|--------|------|
| `CommonInvoiceOcrService` | Service commun aux factures dynamiques et vente : extrait les champs universels (ICE, IF, RC, numéro, date, montants) depuis le texte OCR nettoyé, avant spécialisation par module |
| `CommonInvoiceOcrData` | POJO de données OCR communes : contient les champs extraits par `CommonInvoiceOcrService` (ICE, montants, dates, fournisseur, client) partagés entre les pipelines dynamique et vente |
| `InvoiceOcrMode` | Enum du mode de traitement OCR : `FAST` (Tesseract direct, sans preprocessing), `STANDARD` (preprocessing + Tesseract), `HIGH_QUALITY` (preprocessing + cascade moteurs + fallback), `FORCE_PADDLE`, `FORCE_OLMOCR` |

---

## OCR spécialisés (hors module `ocr/`)

Ces services OCR ne sont pas dans ce module car ils sont spécifiques à leur contexte métier :

| Classe | Localisation | Rôle |
|--------|-------------|------|
| `OcrService` | `bank/service/ocr/` | OCR spécifique aux relevés bancaires (pages multi-colonnes, zones ciblées) |
| `OcrPreProcessor` | `bank/service/ocr/` | Prétraitement image adapté aux scans de relevés bancaires |
| `OcrCleaningService` | `bank/service/ocr/` | Nettoyage texte OCR orienté tableaux de transactions bancaires |
| `ImageZoneExtractor` | `bank/service/ocr/` | Découpe une page de relevé en zones (en-tête RIB, tableau transactions, pied soldes) |
| `FooterExtractionService` | `bank/service/ocr/` | Extrait spécifiquement la zone pied de page des relevés pour lire les soldes récapitulatifs |
| `SalesAdvancedOcrService` | `sales/service/ocr/` | Variante OCR adaptée aux factures de vente (mise en page différente des factures d'achat) |
| `CentreMonetiqueOcrService` | `bank/centremonetique/service/` | OCR spécifique aux fichiers Centre Monétique (bordereaux TPE/e-paiement) |

---

## Flux OCR type (facture)

```
Fichier PDF/image
      ↓
ImagePreprocessingService   ← ajuste DPI, deskew, binarise
      ↓
AdvancedOcrService          ← choisit le moteur selon DocumentDifficultyClass
      ├─ TesseractConfigService + Tesseract
      ├─ PaddleOcrService
      └─ OlmocrFallbackService
      ↓
OcrPostProcessor            ← segmente en [HEADER]/[BODY]/[FOOTER]
      ↓
TextCleaningService         ← normalise le texte brut
      ↓
DocumentQualityScoringService → score OK ?
      ↓
CommonInvoiceOcrService     ← extrait ICE, montants, dates
      ↓
OcrValidationService + BusinessValidationService + AmountValidatorService
      ↓
DuplicateDetectionService   ← doublon ?
      ↓
Module métier (dynamic / sales / bank)
```
