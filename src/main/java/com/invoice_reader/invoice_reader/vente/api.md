# API — Module `vente` (Dossier Vente)

Package racine : `com.invoice_reader.invoice_reader.vente`

Ce module gère le traitement des **factures de vente** (dossier vente). Il suit le même principe que le module `achat` : upload → OCR → détection template → extraction des champs → validation → comptabilisation, mais adapté aux factures émises par l'entreprise.

---

## Structure

```
vente/
├── controller/       ← REST endpoints
├── dto/              ← Objets de transfert
├── entity/           ← Entités JPA + enums
├── repository/       ← Accès données JPA
├── service/          ← Logique métier
│   ├── ocr/          ← OCR spécialisé vente
│   ├── patterns/     ← Gestion des patterns d'extraction vente
│   └── validation/   ← Validation des factures de vente
└── utils/            ← Utilitaires (patterns regex, détection type)
```

---

## vente/controller

| Classe | Routes | Rôle |
|--------|--------|------|
| `VenteInvoiceController` | `POST /api/sales/invoices/upload` · `POST /upload/batch` · `GET` · `GET /{id}` · `DELETE /{id}` · `PUT /{id}` · `POST /{id}/validate` · `POST /{id}/account` · `GET /stats` · `GET /{id}/download` · `POST /bulk/*` | Controller principal du module vente : upload (unitaire + batch), listage, consultation, suppression, mise à jour des champs, validation, comptabilisation, statistiques et téléchargement |
| `LegacyMiniCompatibilityController` | `POST /scan` · `POST /scan/extract-field` · `POST /alpha` · `POST /vente` | Endpoints de scan génériques (bridge achat + vente) maintenus pour rétrocompatibilité avec les anciens clients |

---

## vente/dto

| Classe | Rôle |
|--------|------|
| `VenteExtractionContext` | Contexte d'extraction pour une facture de vente : texte OCR segmenté, template détecté, dossier, mode OCR. Passé entre les services du pipeline |

---

## vente/entity

| Classe | Table | Rôle |
|--------|-------|------|
| `VenteInvoice` | `sales_invoice` | Facture de vente : tous les champs extraits (numéro, date, client, montants HT/TVA/TTC, ICE, IF, RC), statut, score de confiance, template et signature utilisés, dossier |
| `VenteInvoiceStatus` | — (enum) | Statut d'une facture de vente : `UPLOADED`, `PROCESSING`, `PROCESSED`, `VALIDATED`, `COMPTABILISE`, `ERROR`, `PENDING_CLIENT` |

> `VenteInvoice` référence aussi des types partagés du module `database/` : `InvoiceStatus`, `TemplateSignature`, `DocumentType`, `DuplicateLevel`.

---

## vente/repository

| Classe | Rôle |
|--------|------|
| `VenteInvoiceRepository` | JPA Repository pour `VenteInvoice` : requêtes par dossier, statut, période, numéro de facture, détection de doublons |

---

## vente/service

### Service principal

| Classe | Rôle |
|--------|------|
| `VenteInvoiceProcessingService` | Orchestre le pipeline complet d'une facture de vente : OCR → nettoyage → détection template → extraction → validation → persistance. Coordonne tous les autres services du module |
| `VenteExtractionResult` | POJO résultat d'extraction : champs extraits avec leurs scores de confiance, template utilisé, texte brut, statut de validation |

### Extraction et templates

| Classe | Rôle |
|--------|------|
| `VenteFieldExtractorService` | Extrait les champs d'une facture de vente depuis le texte OCR en utilisant les patterns du template détecté + fallback sur les patterns regex génériques vente |
| `VenteTemplateService` | Gestion des templates vente : détection par ICE/en-tête/signature, création automatique, mise à jour des statistiques d'utilisation |
| `VenteAlphaAgentExtractionService` | Extraction de secours via AlphaAgent (LLM) : utilisé quand les patterns classiques échouent sur une facture de vente inconnue |

### Apprentissage

| Classe | Rôle |
|--------|------|
| `VenteFieldLearningService` | Enregistre les corrections manuelles des champs d'une facture de vente ; met à jour les patterns et recalcule les scores de confiance du template |

### Stockage

| Classe | Rôle |
|--------|------|
| `VenteFileStorageService` | Gère le stockage physique des fichiers PDF/image des factures de vente : upload, lecture, suppression, génération de noms uniques |

---

## vente/service/ocr

| Classe | Rôle |
|--------|------|
| `VenteAdvancedOcrService` | OCR spécialisé vente : variante de `AdvancedOcrService` avec des paramètres Tesseract et un prétraitement adaptés à la mise en page des factures de vente (souvent en colonnes, avec tableaux) |

---

## vente/service/patterns

| Classe | Rôle |
|--------|------|
| `VenteFieldPatternService` | CRUD et logique des patterns d'extraction spécifiques aux factures de vente : validation des regex, test, calcul de confiance |

---

## vente/service/validation

| Classe | Rôle |
|--------|------|
| `VenteInvoiceValidator` | Valide une facture de vente extraite : cohérence des montants (TTC = HT + TVA), ICE valide, numéro non-vide, date dans une plage raisonnable, détection de doublons |

---

## vente/utils

| Classe | Rôle |
|--------|------|
| `VenteExtractionPatterns` | Constantes de patterns regex pour les identifiants fiscaux marocains dans les factures de vente : IF, ICE, RC, numéro TVA, RIB. Variantes orthographiques et de mise en forme incluses |
| `VenteInvoiceTypeDetector` | Utilitaire de détection du type de document vente : analyse les mots-clés du texte OCR pour distinguer `FACTURE`, `AVOIR`, `PROFORMA`, `BON_LIVRAISON` |

---

## Flux de traitement d'une facture de vente

```
PDF / Image
      ↓
VenteInvoiceController.upload()
      ↓
VenteInvoiceProcessingService
      ├─ VenteFileStorageService           ← stockage fichier
      ├─ VenteAdvancedOcrService           ← extraction texte
      ├─ ocr/service/TextCleaningService   ← nettoyage (partagé)
      ├─ VenteInvoiceTypeDetector          ← FACTURE / AVOIR / etc.
      │
      ├─ VenteTemplateService.detect()     ← quel template ?
      │   ├─ by ICE → VenteInvoiceRepository
      │   ├─ by header keywords
      │   └─ by TemplateSignature
      │
      ├─ VenteFieldExtractorService        ← applique les patterns
      │   └─ VenteExtractionPatterns       ← regex fiscaux marocains
      ├─ VenteAlphaAgentExtractionService  ← fallback LLM si confiance < seuil
      ├─ VenteInvoiceValidator             ← montants, ICE, doublons
      │
      └─ VenteInvoiceRepository.save()     ← persistance
            ↓
      (optionnel) VenteFieldLearningService ← corrections manuelles
            ↓
      VenteFieldPatternService              ← enrichissement patterns
```

---

## Endpoints REST complets

| Méthode | Route | Rôle |
|---------|-------|------|
| `POST` | `/api/sales/invoices/upload` | Upload d'une facture unitaire |
| `POST` | `/api/sales/invoices/upload/batch` | Upload batch de plusieurs factures |
| `POST` | `/api/sales/invoices/{id}/process` | (Re)traitement d'une facture |
| `GET` | `/api/sales/invoices` | Liste des factures du dossier |
| `GET` | `/api/sales/invoices/pending` | Factures en attente de validation |
| `GET` | `/api/sales/invoices/scanned` | Factures déjà scannées |
| `GET` | `/api/sales/invoices/{id}` | Détail d'une facture |
| `PUT` | `/api/sales/invoices/{id}` | Mise à jour des champs d'une facture |
| `DELETE` | `/api/sales/invoices/{id}` | Suppression d'une facture |
| `POST` | `/api/sales/invoices/{id}/validate` | Validation d'une facture |
| `POST` | `/api/sales/invoices/{id}/client-validate` | Validation côté client |
| `POST` | `/api/sales/invoices/{id}/account` | Comptabilisation d'une facture |
| `GET` | `/api/sales/invoices/{id}/available-signatures` | Signatures templates disponibles |
| `GET` | `/api/sales/invoices/{id}/download` | Téléchargement du fichier original |
| `GET` | `/api/sales/invoices/journal` | Journal comptable des factures vente |
| `GET` | `/api/sales/invoices/stats` | Statistiques (totaux, répartition par statut) |
| `POST` | `/api/sales/invoices/bulk/process` | Traitement en masse |
| `POST` | `/api/sales/invoices/bulk/validate` | Validation en masse |
| `POST` | `/api/sales/invoices/bulk/delete` | Suppression en masse |
| `POST` | `/scan` | `LegacyMiniCompatibilityController` — scan générique (bridge achat+vente) |
| `POST` | `/scan/extract-field` | `LegacyMiniCompatibilityController` — extraction d'un champ spécifique |
| `POST` | `/alpha` | `LegacyMiniCompatibilityController` — extraction via AlphaAgent |
| `POST` | `/vente` | `LegacyMiniCompatibilityController` — endpoint legacy vente |

---

## Différences clés avec le module `achat`

| Aspect | `achat` | `vente` |
|--------|---------|---------|
| Direction | Factures reçues (fournisseurs) | Factures émises (clients) |
| Template matching | Par ICE fournisseur + en-tête | Par ICE client + signature layout |
| Validation | Vérification TVA/HT/TTC | Idem + validation client externe |
| OCR | `AdvancedOcrService` partagé | `VenteAdvancedOcrService` spécialisé |
| Patterns | `ExtractionPatterns` (utils/) | `VenteExtractionPatterns` (vente/utils/) |
| Statuts supplémentaires | — | `PENDING_CLIENT` (attente validation client) |
