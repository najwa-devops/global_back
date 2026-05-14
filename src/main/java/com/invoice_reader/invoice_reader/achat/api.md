# API — Module `achat` (Dossier Achat)

Package racine : `com.invoice_reader.invoice_reader.achat`

Ce module gère le traitement des **factures d'achat** (dossier achat) par extraction basée sur des templates appris. Il couvre le cycle complet : upload → OCR → détection du template → extraction des champs → apprentissage → comptabilisation.

---

## Structure

```
achat/
├── controller/       ← REST endpoints (factures, templates, patterns, compat)
├── dao/              ← Accès données JPA pour les entités du module
├── dto/              ← Objets de transfert (requêtes, réponses)
├── entity/           ← Entités JPA + enums propres au module
└── service/          ← Logique métier
    └── pattern/      ← Services de gestion des patterns d'extraction
```

---

## achat/controller

| Classe | Routes | Rôle |
|--------|--------|------|
| `AchatInvoiceController` | `POST /api/dynamic/invoices/upload` · `GET /api/dynamic/invoices` · `GET /{id}` · `DELETE /{id}` | Upload, listage, consultation et suppression des factures d'achat ; orchestre le pipeline OCR + extraction |
| `AchatTemplateController` | `GET /api/dynamic/templates` · `POST` · `PUT /{id}` · `DELETE /{id}` | CRUD sur les templates de reconnaissance de factures fournisseurs |
| `FieldLearningController` | `POST /api/dynamic/learning/save` · `GET /api/dynamic/learning/{dossierId}` | Enregistre les corrections manuelles de champs OCR pour l'apprentissage |
| `FieldPatternController` | `GET /api/patterns` · `POST` · `PUT /{id}` · `DELETE /{id}` | CRUD sur les patterns d'extraction de champs (regex, règles positionnelles) |
| `LegacyAchatVenteCompatibilityController` | `GET /api/achat/invoices` · `POST /api/achat/invoices/upload` · `POST /api/achat/invoices/upload/batch` | Endpoints legacy `/api/achat/...` maintenus pour rétrocompatibilité avec les anciens clients |

---

## achat/dao

| Classe | Entité cible | Méthodes notables |
|--------|-------------|-------------------|
| `AchatInvoiceDao` | `AchatInvoice` | `findByDossierAndStatus`, `findByNumeroFacture`, `countByDossierAndDateBetween`, `findDuplicates` |
| `AchatTemplateDao` | `AchatTemplate` | `findByDossierIdAndActive`, `findBySignature`, `findByFournisseurIce` |
| `FieldLearningDataDao` | `FieldLearningData` | `findByDossierAndFieldName`, `findTopNByConfidence`, `findValidatedByDossier` |
| `FieldPatternDao` | `FieldPattern` | `findByTemplateAndFieldName`, `findActiveByDossier`, `findByType` |

---

## achat/dto

| Classe | Rôle |
|--------|------|
| `AchatExtractionResponse` | Réponse d'extraction : tous les champs extraits, scores de confiance, template détecté, statut de validation |
| `AchatTemplateDto` | DTO de lecture d'un template : champs configurés, signature, fournisseur associé, statistiques d'utilisation |
| `CreateAchatTemplateRequest` | DTO de création d'un template : nom, fournisseur, ICE, patterns initiaux |
| `UpdateAchatTemplateRequest` | DTO de mise à jour d'un template : champs modifiables, nouveaux patterns |
| `FieldLearningDto` | DTO d'apprentissage : nom du champ, valeur corrigée, contexte de la correction |
| `FieldPatternInfo` | DTO de lecture d'un pattern : type, expression, champ cible, confiance |
| `SaveFieldsWithPatternsRequest` | DTO de sauvegarde groupée : liste de champs + patterns associés en une seule requête |

---

## achat/entity

### Entités JPA

| Classe | Table | Rôle |
|--------|-------|------|
| `AchatInvoice` | `dynamic_invoice` | Facture d'achat extraite : tous les champs OCR (ICE, IF, RC, numéro, date, montants TTC/HT/TVA), statut, score de confiance, template utilisé, dossier |
| `AchatTemplate` | `dynamic_template` | Template de reconnaissance : signature d'identification, liste de champs attendus, fournisseur associé, taux de succès |
| `FieldLearningData` | `field_learning_data` | Donnée d'apprentissage : association validée entre un nom de champ et sa valeur corrigée manuellement |
| `FieldPattern` | `field_pattern` | Pattern d'extraction : regex ou règle positionnelle pour extraire un champ spécifique depuis le texte OCR |

### Enums

| Classe | Rôle |
|--------|------|
| `DetectionMethod` | Méthode de détection du template : `BY_HEADER`, `BY_ICE`, `BY_KEYWORD`, `BY_LAYOUT`, `MANUAL` |
| `DocumentType` | Type de document : `INVOICE`, `BANK_STATEMENT`, `RECEIPT`, `CREDIT_NOTE`, `UNKNOWN` |
| `DuplicateLevel` | Niveau de doublon détecté : `NONE`, `PROBABLE` (même montant + date proche), `CERTAIN` (même N° + même ICE) |
| `FieldType` | Type de champ extrait : `TEXT`, `NUMBER`, `DATE`, `AMOUNT`, `ICE`, `RIB`, `ADDRESS` |
| `LearningStatus` | Statut d'apprentissage : `PENDING` (non encore validé), `VALIDATED` (correction acceptée), `REJECTED` |

---

## achat/service

### Services core

| Classe | Rôle |
|--------|------|
| `AchatInvoiceProcessingService` | Service principal : orchestre le pipeline complet d'une facture d'achat (OCR → nettoyage → détection template → extraction → validation → persistance) |
| `AchatFieldExtractorService` | Extrait les champs d'une facture à partir du texte OCR nettoyé, en utilisant les patterns du template détecté + fallback regex génériques |
| `AchatTemplateService` | CRUD et logique des templates : création, mise à jour, calcul de signature, activation/désactivation |
| `ExtractionEngine` | Moteur d'extraction bas niveau : applique séquentiellement les patterns d'un template sur le texte OCR et retourne les champs trouvés avec leur score |
| `AchatExtractionResult` | POJO résultat d'extraction : champs extraits, scores, template utilisé, texte brut, zones détectées |
| `FieldLearningService` | Gère l'apprentissage : enregistre les corrections utilisateur, met à jour les patterns, recalcule les scores de confiance |
| `AlphaAgentExtractionService` | Extraction de secours basée sur un agent AI (AlphaAgent) : utilisé quand les patterns classiques échouent. Envoie le texte à un LLM pour extraction structurée |
| `TtcCalculationService` | Calcule et valide `TTC = HT + TVA` avec une tolérance de 5 centimes. Calcule le champ manquant si deux des trois valeurs sont connues |
| `MiniCompatibilityScanService` | Service bridge achat + vente : traite un fichier PDF/image, détecte le type (achat ou vente) et délègue au bon extracteur. Utilisé par les endpoints legacy `/scan` |

### Services de pattern

| Classe | Rôle |
|--------|------|
| `FieldPatternService` | CRUD et logique des patterns d'extraction : validation des regex, test sur des exemples, calcul de confiance |
| `PatternIntegrationService` | Intègre les patterns appris dans les templates : quand un champ est corrigé N fois avec le même pattern, l'ajoute automatiquement au template |
| `RegexEnrichmentService` | Enrichit les regex existants : génère des variantes robustes d'une expression pour couvrir les variations OCR (espaces, tirets, encodages) |
| `RegexGenerator` | Génère automatiquement des expressions régulières candidates à partir d'exemples de valeurs corrigées |
| `RegexResult` | POJO résultat d'une génération de regex : expression générée, score de couverture, exemples couverts |

---

## Flux de traitement d'une facture d'achat

```
PDF / Image
      ↓
AchatInvoiceController.upload()
      ↓
AchatInvoiceProcessingService
      ├─ ocr/service/AdvancedOcrService        ← extraction texte
      ├─ ocr/service/TextCleaningService       ← nettoyage
      ├─ ocr/service/DocumentClassifierService ← type = INVOICE ?
      │
      ├─ AchatTemplateService.detect()         ← quel template ?
      │   ├─ by ICE → AchatTemplateDao
      │   ├─ by header keywords
      │   └─ by layout signature
      │
      ├─ ExtractionEngine.extract()            ← applique les patterns
      ├─ AchatFieldExtractorService            ← fallback + enrichissement
      ├─ TtcCalculationService                 ← cohérence montants
      ├─ ocr/service/BusinessValidationService ← ICE, dates, champs obligatoires
      ├─ ocr/service/DuplicateDetectionService ← doublon ?
      │
      └─ AchatInvoiceDao.save()                ← persistance
            ↓
      (optionnel) AlphaAgentExtractionService  ← si confiance < seuil
            ↓
      FieldLearningController.save()           ← corrections manuelles
            ↓
      PatternIntegrationService                ← enrichissement automatique
```

---

## Endpoints REST complets

| Méthode | Route | Contrôleur |
|---------|-------|------------|
| `POST` | `/api/dynamic/invoices/upload` | `AchatInvoiceController` |
| `POST` | `/api/dynamic/invoices/upload/batch` | `AchatInvoiceController` |
| `GET` | `/api/dynamic/invoices` | `AchatInvoiceController` |
| `GET` | `/api/dynamic/invoices/{id}` | `AchatInvoiceController` |
| `DELETE` | `/api/dynamic/invoices/{id}` | `AchatInvoiceController` |
| `GET` | `/api/dynamic/templates` | `AchatTemplateController` |
| `POST` | `/api/dynamic/templates` | `AchatTemplateController` |
| `PUT` | `/api/dynamic/templates/{id}` | `AchatTemplateController` |
| `DELETE` | `/api/dynamic/templates/{id}` | `AchatTemplateController` |
| `POST` | `/api/dynamic/learning/save` | `FieldLearningController` |
| `GET` | `/api/dynamic/learning/{dossierId}` | `FieldLearningController` |
| `GET` | `/api/patterns` | `FieldPatternController` |
| `POST` | `/api/patterns` | `FieldPatternController` |
| `PUT` | `/api/patterns/{id}` | `FieldPatternController` |
| `DELETE` | `/api/patterns/{id}` | `FieldPatternController` |
| `GET` | `/api/achat/invoices` | `LegacyAchatVenteCompatibilityController` |
| `POST` | `/api/achat/invoices/upload` | `LegacyAchatVenteCompatibilityController` |
| `POST` | `/api/achat/invoices/upload/batch` | `LegacyAchatVenteCompatibilityController` |
