# API — Module `banque`

Package racine : `com.invoice_reader.invoice_reader.banque`

Ce module gère le traitement des **relevés bancaires** (OCR, extraction des transactions, validation, comptabilisation) et intègre le **Centre Monétique** (TPE/e-paiement) ainsi que la **liaison de rapprochement**.

---

## Structure

```
banque/
├── config/
├── controller/
├── dto/
├── entity/
├── mapper/
├── repository/
├── service/
│   ├── ocr/
│   ├── parser/
│   └── universal/
├── centremonetique/
│   ├── config/
│   ├── controller/
│   ├── dto/
│   ├── entity/
│   ├── repository/
│   └── service/
└── liaison/
    ├── controller/
    ├── dto/
    └── service/
```

---

## banque/config

| Classe | Rôle |
|--------|------|
| `BanqueAsyncConfig` | Configure le pool de threads asynchrone (`@Async`) utilisé par le traitement OCR et l'upload de relevés |
| `BanqueReleveScheMigration` | Migration DDL : crée/met à jour la table `bank_statement` au démarrage |
| `BanqueReleveOcrTextMigration` | Migration DDL : ajoute la colonne `ocr_raw_text` à la table `bank_statement` |
| `BanqueTransactionScheMigration` | Migration DDL : crée/met à jour la table `bank_transaction` |
| `CptjournalTableMigration` | Migration DDL : crée la table `cptjournal` pour la synchronisation comptable |
| `CptjournalSyncTrackerMigration` | Migration DDL : crée la table de suivi de synchronisation `cptjournal_sync_tracker` |

---

## banque/controller

| Classe | Route(s) | Rôle |
|--------|----------|------|
| `BanqueReleveController` | `POST /api/bank-statements/upload` · `GET /api/bank-statements` · `DELETE ...` | Upload, listage, suppression et consultation des relevés bancaires ; orchestration du traitement OCR |
| `BanqueTransactionController` | `GET /api/bank-transactions` · `PUT /api/bank-transactions/{id}` · `POST /api/bank-transactions/bulk-update` | CRUD sur les transactions extraites ; mise à jour du statut et de la catégorisation |
| `BanqueReleveAccountingController` | `GET /api/v2/bank-statements/{id}/accounting` · `POST ...` | Lien entre un relevé et ses écritures comptables ; consultation du statut de comptabilisation |
| `AccountingAccountController` | `GET /api/v2/accounting/accounts` · `POST/PUT/DELETE ...` | CRUD sur les comptes comptables (plan comptable interne) |
| `AccountingConfigController` | `GET/POST/PUT /api/v2/accounting-configs` | Gestion des configurations comptables (règles d'affectation automatique) |
| `AccountingGenerationController` | `POST /api/v2/accounting/generate` · `GET /api/v2/accounting/entries` | Déclenche la génération d'écritures comptables depuis les transactions validées |
| `ComptabilisationWorkflowController` | `POST /api/comptabilisation/validate` · `GET /api/comptabilisation/status` | Pilote le workflow de validation et de comptabilisation des relevés |
| `JournalController` | `GET /api/journal/batches` · `GET /api/journal/entries` · `GET /api/journal/export` | Consultation et export des journaux comptables (batches + écritures) |

---

## banque/dto

| Classe | Rôle |
|--------|------|
| `BanqueReleveDTO` | DTO de lecture d'un relevé bancaire (métadonnées, période, soldes) |
| `BanqueReleveDetailDTO` | DTO de lecture détaillé incluant les transactions et le statut OCR |
| `BanqueReleveUploadRequest` | DTO d'upload : fichier multipart + métadonnées (banque, dossier) |
| `BanqueTransactionDTO` | DTO de lecture d'une transaction (date, libellé, montant, type débit/crédit) |
| `BanqueTransactionUpdateRequest` | DTO de mise à jour d'une transaction (statut, compte comptable affecté) |
| `BulkUpdateRequest` | DTO pour la mise à jour groupée de plusieurs transactions |
| `StatisticsDTO` | DTO de statistiques d'un relevé (total débits, crédits, nb transactions) |
| `ValidationResultDTO` | DTO de résultat de validation (erreurs, avertissements, score de confiance) |

---

## banque/entity

| Classe | Rôle |
|--------|------|
| `BanqueReleve` | Entité JPA représentant un relevé bancaire (fichier, banque, période, soldes, statut). Table : `bank_statement` |
| `BanqueTransaction` | Entité JPA représentant une ligne de transaction extraite d'un relevé. Table : `bank_transaction` |
| `BanqueTransactionAccountRule` | Entité JPA pour les règles d'affectation automatique de compte par mot-clé de libellé |
| `BanqueStatus` | Enum des états d'un relevé : `UPLOADED`, `PROCESSING`, `PROCESSED`, `ERROR`, `VALIDATED` |
| `BanqueStatusConverter` | `AttributeConverter` JPA : sérialise/désérialise `BanqueStatus` en base |
| `BanqueType` | Enum des banques supportées : `ATTIJARIWAFA`, `BCP`, `CIH`, `BMCE`, `BMCI`, `SGMB`, `CDM`, `CAM`, `UNKNOWN` |
| `ContinuityStatus` | Enum indiquant si la continuité des soldes entre relevés consécutifs est vérifiée |
| `JournalBatch` | Entité JPA représentant un lot d'export vers le journal comptable externe (cptjournal) |
| `JournalEntry` | Entité JPA représentant une écriture comptable individuelle dans un batch |
| `AccountingConfig` | Entité JPA pour la configuration comptable d'un dossier (comptes par défaut, TVA, etc.) |

---

## banque/mapper

| Classe | Rôle |
|--------|------|
| `BanqueReleveMapper` | Convertit `BanqueReleve` ↔ `BanqueReleveDTO` / `BanqueReleveDetailDTO` |
| `BanqueTransactionMapper` | Convertit `BanqueTransaction` ↔ `BanqueTransactionDTO` |

---

## banque/repository

| Classe | Rôle |
|--------|------|
| `BanqueReleveRepository` | JPA Repository pour `BanqueReleve` ; requêtes par dossier, période, statut |
| `BanqueTransactionRepository` | JPA Repository pour `BanqueTransaction` ; requêtes par relevé, type, statut |
| `BanqueTransactionAccountRuleRepository` | JPA Repository pour les règles d'affectation comptable |
| `AccountingConfigRepository` | JPA Repository pour `AccountingConfig` |
| `AccountingEntryRepository` | JPA Repository pour les écritures comptables (`AccountingEntry`) |
| `AccountingEntryDao` | DAO legacy pour `AccountingEntry` |
| `JournalBatchRepository` | JPA Repository pour `JournalBatch` |
| `JournalEntryRepository` | JPA Repository pour `JournalEntry` |
| `CptjournalJdbcRepository` | Repository JDBC natif pour l'écriture directe dans la table `cptjournal` externe |
| `CptjournalSyncTrackerRepository` | JPA Repository pour le suivi des synchronisations vers cptjournal |

---

## banque/service

| Classe | Rôle |
|--------|------|
| `BanqueReleveProcessingService` | Service principal : orchestre l'extraction OCR/Excel d'un relevé et persiste les transactions |
| `BanqueReleveProcessor` | Moteur de traitement : détecte le format (PDF texte, PDF scan, Excel) et délègue au bon parseur |
| `BanqueReleveValidatorService` | Valide la cohérence d'un relevé (continuité des soldes, doublons, plage de dates) |
| `BanqueDetector` | Identifie la banque émettrice d'un relevé par mots-clés (en-tête) puis par code RIB |
| `BanqueAliasResolver` | Résout les alias et variantes orthographiques de noms de banque vers un `BanqueType` |
| `BanqueFileStorageService` | Gère le stockage physique des fichiers relevés : upload, lecture, suppression, streaming PDF multi-pages |
| `MetadataExtractorService` | Extrait les métadonnées d'un relevé : numéro de compte, titulaire, agence |
| `PrimaryRibExtractor` | Extrait le RIB principal depuis le texte OCR d'un relevé |
| `StatementPeriodExtractor` | Extrait la période (date début / date fin) depuis l'en-tête du relevé |
| `StatementTotalsExtractor` | Extrait les totaux de solde (ouverture, clôture, total débits/crédits) |
| `HeaderFooterCleaner` | Supprime les en-têtes et pieds de page répétitifs des pages OCR avant parsing |
| `TransactionExtractorService` | Coordonne l'extraction des lignes de transactions depuis le texte nettoyé |
| `TransactionParserFactory` | Factory : sélectionne le `TransactionParser` adapté au format de la banque détectée |
| `TransactionClassifier` | Classifie chaque transaction en débit ou crédit selon la position des montants |
| `BanqueTransactionAccountLearningService` | Apprentissage automatique : mémorise les affectations comptables validées pour suggestion future |
| `AccountingGenerationService` | Génère les écritures comptables depuis les transactions validées d'un relevé |
| `ComptabilisationWorkflowService` | Orchestre le workflow complet : validation → génération → export vers cptjournal |
| `ExternalComptesCatalogService` | Charge et met en cache le catalogue des comptes du plan comptable externe (cptjournal) |
| `WinsOsXmlParser` | Parseur XML spécifique au format WinsOs (export bancaire XML) |

---

## banque/service/ocr

| Classe | Rôle |
|--------|------|
| `OcrService` | Appelle Tesseract (ou équivalent) sur une image PDF pour produire le texte brut |
| `OcrPreProcessor` | Prépare l'image avant OCR : deskew, binarisation, amélioration du contraste |
| `OcrCleaningService` | Nettoie le texte OCR brut : corrige les artefacts, normalise les espaces et les chiffres |
| `ImageZoneExtractor` | Découpe une page PDF en zones (en-tête, corps, pied) pour un OCR ciblé |
| `FooterExtractionService` | Extrait spécifiquement la zone pied de page pour y lire les soldes récapitulatifs |

---

## banque/service/parser

| Classe | Rôle |
|--------|------|
| `TransactionParser` | Interface : contrat d'extraction de transactions depuis un texte de relevé |
| `AbstractTransactionParser` | Classe abstraite avec logique commune (normalisation des montants, détection colonnes) |
| `StandardTransactionParser` | Parseur standard : format `Date | Libellé | Débit | Crédit | Solde` |
| `DateOpDateValTransactionParser` | Parseur pour les formats avec deux dates (opération + valeur) sur la même ligne |
| `LibelleDateValTransactionParser` | Parseur pour les formats où la date de valeur suit le libellé |
| `AttijariwafaTransactionParser` | Parseur spécialisé pour les relevés Attijariwafa Bank |
| `BcpTransactionParser` | Parseur spécialisé pour les relevés Banque Centrale Populaire |

---

## banque/service/universal

| Classe | Rôle |
|--------|------|
| `UniversalTransactionExtractionEngine` | Interface du moteur universel d'extraction adaptatif |
| `UniversalTransactionExtractionEngineImpl` | Implémentation : détecte dynamiquement le layout et applique le scoring de confiance |
| `BanqueLayoutProfile` | POJO décrivant le profil de mise en page d'une banque (positions colonnes, séparateurs) |
| `BanqueLayoutProfileRegistry` | Interface du registre des profils de layout par banque |
| `DefaultBanqueLayoutProfileRegistry` | Implémentation par défaut du registre : charge les profils depuis la configuration |
| `TransactionConfidenceScorer` | Interface : calcule un score de confiance pour une transaction extraite |
| `DefaultTransactionConfidenceScorer` | Implémentation du scorer (poids sur la cohérence date, montant, solde) |
| `ScoredTransactionBlockBuilder` | Construit des blocs de transactions scorés à partir des lignes texte brutes |
| `TransactionBlock` | POJO représentant un bloc de lignes candidat à une transaction |
| `TransactionBlockBuilder` | Stratégie de découpage du texte en blocs de transactions |
| `TransactionExtractionContext` | Contexte d'extraction : bank type, profil layout, texte, page courante |
| `BalanceDrivenResolver` | Résout le type débit/crédit par évolution du solde courant |
| `AccountingBalanceDrivenResolver` | Variante du resolver orientée rapprochement comptable |
| `NumericClassifier` | Classifie un token comme montant, solde ou date selon sa forme numérique |
| `SmartNumericClassifier` | Version améliorée avec détection contextuelle des séparateurs (`.`/`,`) |

---

## banque/centremonetique/config

| Classe | Rôle |
|--------|------|
| `CentreMonetiqueSchemaMigration` | Migration DDL : crée la table `centre_monetique_batch` |

---

## banque/centremonetique/controller

| Classe | Route | Rôle |
|--------|-------|------|
| `CentreMonetiqueController` | `/api/centre-monetique` | Endpoints du module Centre Monétique : upload, listage et consultation des batches TPE/e-paiement |

---

## banque/centremonetique/dto

| Classe | Rôle |
|--------|------|
| `CentreMonetiqueBatchDetailDTO` | DTO de lecture d'un batch Centre Monétique avec le détail des opérations |
| `CentreMonetiqueBatchSummaryDTO` | DTO de synthèse d'un batch (total, date, statut de rapprochement) |
| `CentreMonetiqueExtractionRow` | DTO représentant une ligne brute extraite d'un fichier Centre Monétique |
| `CentreMonetiqueUploadResponseDTO` | DTO de réponse à l'upload d'un fichier Centre Monétique (nb lignes traitées, erreurs) |

---

## banque/centremonetique/entity

| Classe | Rôle |
|--------|------|
| `CentreMonetiqueBatch` | Entité JPA représentant un lot d'opérations TPE/e-paiement du Centre Monétique |
| `CentreMonetiqueTransaction` | Entité JPA représentant une opération individuelle dans un batch Centre Monétique |

---

## banque/centremonetique/repository

| Classe | Rôle |
|--------|------|
| `CentreMonetiqueBatchRepository` | JPA Repository pour `CentreMonetiqueBatch` |
| `CentreMonetiqueBatchSummaryProjection` | Projection Spring Data pour les requêtes de synthèse |
| `CentreMonetiqueTransactionRepository` | JPA Repository pour `CentreMonetiqueTransaction` |

---

## banque/centremonetique/service

| Classe | Rôle |
|--------|------|
| `CentreMonetiqueExtractionService` | Extrait et persiste les opérations depuis un fichier Centre Monétique (CSV/Excel) |
| `CentreMonetiqueOcrService` | OCR spécialisé pour les documents Centre Monétique scannés |
| `CentreMonetiqueWorkflowService` | Orchestre le rapprochement entre les batches Centre Monétique et les transactions bancaires |
| `CentreMonetiqueStructureType` | Enum des formats de fichier Centre Monétique supportés |

---

## banque/liaison/controller

| Classe | Route | Rôle |
|--------|-------|------|
| `CentreMonetiqueLiaisonController` | `GET/POST /api/liaison` | Endpoints de rapprochement entre relevés bancaires et batches Centre Monétique |

---

## banque/liaison/dto

| Classe | Rôle |
|--------|------|
| `CmExpansionDTO` | DTO d'expansion d'une opération Centre Monétique avec sa transaction bancaire associée |
| `CmExpansionLineDTO` | DTO représentant une ligne détaillée dans l'expansion d'un batch CM |
| `RapprochementResultDTO` | DTO de résultat de rapprochement (matchés, non-matchés, écarts) |

---

## banque/liaison/service

| Classe | Rôle |
|--------|------|
| `CentreMonetiqueLiaisonService` | Logique de rapprochement : associe chaque opération CM à sa transaction bancaire correspondante |

---

## Résumé des endpoints REST

| Méthode | Route | Contrôleur |
|---------|-------|------------|
| `POST` | `/api/bank-statements/upload` | `BanqueReleveController` |
| `GET` | `/api/bank-statements` | `BanqueReleveController` |
| `DELETE` | `/api/bank-statements/{id}` | `BanqueReleveController` |
| `GET` | `/api/bank-transactions` | `BanqueTransactionController` |
| `PUT` | `/api/bank-transactions/{id}` | `BanqueTransactionController` |
| `POST` | `/api/bank-transactions/bulk-update` | `BanqueTransactionController` |
| `GET` | `/api/v2/bank-statements/{id}/accounting` | `BanqueReleveAccountingController` |
| `GET` | `/api/v2/accounting/accounts` | `AccountingAccountController` |
| `GET/POST/PUT/DELETE` | `/api/v2/accounting-configs` | `AccountingConfigController` |
| `POST` | `/api/v2/accounting/generate` | `AccountingGenerationController` |
| `GET` | `/api/v2/accounting/entries` | `AccountingGenerationController` |
| `POST` | `/api/comptabilisation/validate` | `ComptabilisationWorkflowController` |
| `GET` | `/api/comptabilisation/status/{id}` | `ComptabilisationWorkflowController` |
| `GET` | `/api/journal/batches` | `JournalController` |
| `GET` | `/api/journal/entries` | `JournalController` |
| `GET` | `/api/journal/export/{batchId}` | `JournalController` |
| `GET/POST` | `/api/liaison` | `CentreMonetiqueLiaisonController` |
