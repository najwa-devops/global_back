# WORKFLOW — Extraction OCR : Factures Achat (Fournisseur) & Factures Vente

**Architecte Backend Senior — Version métier alignée dossier**
**Projet:** `invoice-reader` — Spring Boot + OCR
**Date:** 2026-02-27

---

## 1. Ce qu'on veut vraiment (règle métier)

Dans cette application comptable:
- **1 dossier = 1 entreprise** (notre entreprise)
- Toutes les factures uploadées dans ce dossier concernent cette même entreprise
- Il faut distinguer:
  - **Facture ACHAT (fournisseur)**: notre entreprise est **client/acheteur**
  - **Facture VENTE**: notre entreprise est **émetteur/vendeur**

Donc la distinction ne se fait pas seulement sur "footer/header", mais d'abord sur:
- l'identité de l'entreprise du dossier (ICE/IF/RC)
- la comparaison entre cette identité et les identifiants extraits OCR

---

## 2. Objectif des modifications

### Deux types de factures à distinguer

| Caractéristique | Facture VENTE | Facture ACHAT (FOURNISSEUR) |
|---|---|---|
| Qui émet ? | Notre entreprise (dossier) | Le fournisseur |
| Entreprise du dossier apparaît où ? | Footer (IF/ICE/RC émetteur) | Header/body (comme client/acheteur) |
| ICE dans footer | ICE de notre entreprise | ICE du fournisseur |
| 1er ICE header/body | ICE du client acheteur | ICE de notre entreprise |
| Nom près du 1er ICE header | Nom du client | Nom de notre entreprise |
| Signature template | Peu utile (document interne connu) | Oui: IF/ICE fournisseur (footer) |

### Règle métier clé

- **Le dossier représente l'entreprise de référence**
- Si l'ICE du dossier est détecté dans footer -> tendance **VENTE**
- Si l'ICE du dossier est détecté en header/body -> tendance **ACHAT**
- En cas d'ambiguïté: fallback sûr = **ACHAT/FOURNISSEUR** (compatibilité existant)

---

## 3. Modifications nécessaires (corrigées)

### Étape 1 — Ajouter l'identité entreprise au niveau dossier (indispensable)

> Sans cela, on ne peut pas classifier correctement vente vs achat.

**Fichiers impactés:**
- `entity/auth/Dossier.java`
- migration SQL

Ajouter:
- `companyName`
- `companyIce`
- `companyIf`
- `companyRc`

Exemple SQL:
```sql
ALTER TABLE dossiers
  ADD COLUMN company_name VARCHAR(255),
  ADD COLUMN company_ice VARCHAR(20),
  ADD COLUMN company_if VARCHAR(20),
  ADD COLUMN company_rc VARCHAR(30);
```

### Étape 2 — Introduire le type de facture

`invoiceType`:
- `VENTE`
- `ACHAT_FOURNISSEUR`

**Fichiers impactés:**
- `entity/dynamic/DynamicInvoice.java`
- nouveau enum `entity/invoice/InvoiceType.java`

Ajouter aussi:
- `clientName`
- `clientIce`

### Étape 3 — Pipeline OCR: extraction par zones (header/footer)

**Fichier:** `DynamicInvoiceProcessingService.java`

Conserver:
- extraction footer (IF/ICE/RC)

Ajouter:
- extraction header (1er ICE + nom voisin)
- enrichissement de `fieldsData`:
  - `clientIce`
  - `clientName`

### Étape 4 — Détection du type (logique robuste)

`detectInvoiceType(ocrText, dossierCompanyProfile)`:

1. Extraire:
- `headerFirstIce`
- `footerLastIce`

2. Comparer avec `dossier.companyIce`

3. Décision:
- si `footerLastIce == companyIce` -> `VENTE`
- sinon si `headerFirstIce == companyIce` -> `ACHAT_FOURNISSEUR`
- sinon fallback -> `ACHAT_FOURNISSEUR`

### Étape 5 — Liaison tier selon type

**Si `ACHAT_FOURNISSEUR`:**
- comportement actuel (tier fournisseur via IF/ICE footer)

**Si `VENTE`:**
- chercher tier client via `clientIce` (header)
- lier la facture au tier client

### Étape 6 — API upload

`POST /api/dynamic-invoices/upload`

Option proposée:
- `invoiceType` optionnel (forçage manuel)
- sinon auto-détection

Règle:
- si param fourni -> priorité au param
- sinon -> auto-détection

### Étape 7 — DTO réponse

Ajouter dans réponse:
- `invoiceType`
- `clientName`
- `clientIce`
- éventuellement `detectionReason` (debug)

---

## 4. Règles de lecture OCR (version métier finale)

### Cas A — Facture ACHAT (fournisseur)
- Header/body: notre entreprise (client)
- Footer: fournisseur (émetteur)
- Tier à lier: fournisseur

### Cas B — Facture VENTE
- Header/body: client acheteur
- Footer: notre entreprise (émetteur)
- Tier à lier: client

---

## 5. Compatibilité et sécurité de déploiement

- Ne pas casser le flux actuel fournisseur
- Fallback par défaut: `ACHAT_FOURNISSEUR`
- Si `companyIce` dossier absent: rester en mode actuel + log warning

Log recommandé:
```text
[InvoiceTypeDetection] dossierId=... companyIce=...
headerFirstIce=... footerLastIce=...
decision=VENTE|ACHAT_FOURNISSEUR reason=...
```

---

## 6. Résumé exécutable

Oui, **il y a modification**:
1. Ajouter identité entreprise sur `Dossier`
2. Ajouter `invoiceType/clientName/clientIce` sur `DynamicInvoice`
3. Détecter type en comparant ICE OCR avec ICE entreprise du dossier
4. Adapter liaison tier: fournisseur pour achat, client pour vente

C'est la version correcte pour ton contexte: **une entreprise par dossier**.



















Plan — Refonte module Sales : Duplication de DynamicInvoice
Contexte
Le module sales/ actuel (16 fichiers) a été construit avec une architecture propriétaire différente de celle de DynamicInvoice.
L'utilisateur veut repartir de zéro : supprimer tout le module sales actuel et le reconstruire comme une copie exacte de DynamicInvoice, avec une seule différence business :

Pour l'extraction de la signature : prendre le PREMIER ICE trouvé dans le HEADER (pas le dernier dans le footer)
Pas besoin de IF ni RC
Scope
Fichiers à SUPPRIMER (16 fichiers actuels dans sales/)

sales/controller/SalesInvoiceController.java
sales/dto/SalesExtractionContext.java
sales/dto/SalesInvoiceResponse.java
sales/dto/SalesOcrZones.java
sales/dto/SalesUploadRequest.java
sales/entity/SalesInvoice.java
sales/entity/SalesInvoiceStatus.java
sales/mapper/SalesInvoiceMapper.java
sales/repository/SalesInvoiceRepository.java
sales/service/SalesInvoiceOrchestrator.java
sales/service/extraction/SalesAmountExtractor.java
sales/service/extraction/SalesClientExtractor.java
sales/service/extraction/SalesMetaExtractor.java
sales/service/ocr/SalesOcrZoneParser.java
sales/service/processing/SalesInvoiceProcessor.java
sales/service/validation/SalesInvoiceValidator.java

Fichiers à CRÉER (4 fichiers, dans sales/)
sales/entity/SalesInvoice.java
sales/repository/SalesInvoiceRepository.java
sales/service/SalesInvoiceProcessingService.java
sales/controller/SalesInvoiceController.java


Services PARTAGÉS (non dupliqués — utilisés tels quels)
servises/dynamic/DynamicFieldExtractorService — extraction de champs
servises/dynamic/DynamicTemplateService — gestion des templates
servises/dynamic/FieldLearningService — apprentissage automatique
servises/dynamic/DynamicExtractionResult — résultat extraction
entity/dynamic/DynamicTemplate — template partagé
entity/invoice/InvoiceStatus — même enum de statut
servises/ocr/AdvancedOcrService — OCR partagé
servises/FileStorageService — stockage fichier partagé
servises/account_tier/TierService — tiers partagés
servises/patterns/FieldPatternService — patterns partagés
utils/ExtractionPatterns — patterns regex partagés
utils/InvoiceTypeDetector — détection avoir partagé


Détail des 4 fichiers à créer
1. sales/entity/SalesInvoice.java
Copie exacte de entity/dynamic/DynamicInvoice.java avec :

@Table(name = "sales_invoice")
Classe renommée SalesInvoice
Tous les champs identiques (extractedData, fieldsData, missingFields, lowConfidenceFields, autoFilledFields, detectedSignature, templateId, templateName, overallConfidence, tier, dossier, status, isAvoir, clientValidated, validatedAt, accountedAt, etc.)
Même InvoiceStatus enum (shared)
Toutes les méthodes helper identiques (getField, setField, getInvoiceNumber, getIce, canBeValidated, etc.)
2. sales/repository/SalesInvoiceRepository.java
Copie de repository/DynamicInvoiceDao.java avec :

Interface renommée SalesInvoiceRepository
Type générique SalesInvoice au lieu de DynamicInvoice
Toutes les mêmes query methods (findByDossierId, findByStatus, countByStatus, searchByInvoiceNumber, findByIce, etc.)
3. sales/service/SalesInvoiceProcessingService.java
Copie de servises/dynamic/DynamicInvoiceProcessingService.java avec 2 changements :

Changement 1 — Signature depuis HEADER (premier ICE uniquement)


AVANT (DynamicInvoice):
  detectSignatureFromFooter(text)
    → extractFooter(text) → derniers 25% OU marqueur [FOOTER]
    → extractIceFromFooter(footer) → prend le DERNIER ICE
    → extractIfFromFooter(footer) → prend le DERNIER IF
    → extractRcFromFooter(footer) → prend le DERNIER RC
    → Priorité: IF > ICE > RC

APRÈS (SalesInvoice):
  detectSignatureFromHeader(text)
    → extractHeader(text) → premiers 30% OU marqueur [HEADER]
    → extractFirstIceFromHeader(header) → prend le PREMIER ICE
    → Pas de IF, pas de RC
    → Retourne directement TemplateSignature(SignatureType.ICE, firstIce)
Changement 2 — Tier linking par ICE uniquement


AVANT:
  linkInvoiceToTier()
    → Priorité 1: cherche par IF
    → Priorité 2: cherche par ICE

APRÈS:
  linkInvoiceToTier()
    → Cherche uniquement par ICE (no IF lookup)
Changement 3 — Dossier chargé via DossierDao


AVANT:
  invoice.setDossierId(dossierId);  // bug potentiel, dossierId readonly

APRÈS:
  Dossier dossier = dossierDao.findById(dossierId).orElseThrow(...);
  invoice.setDossier(dossier);  // correctement via relation JPA
Tout le reste identique :

Stockage fichier
OCR (AdvancedOcrService)
Détection template (DynamicTemplateService)
Extraction champs (DynamicFieldExtractorService)
Auto-fill depuis fixedSupplierData du template
Fallback field_patterns
Calcul et validation montants (HT/TVA/TTC)
Détection avoir (InvoiceTypeDetector)
Decision Matrix : ERROR / READY_TO_VALIDATE / TREATED
Sauvegarde via SalesInvoiceRepository
reprocessExistingInvoice()
4. sales/controller/SalesInvoiceController.java
Copie de controller/dynamic/DynamicInvoiceController.java avec :

Base URL : /api/sales/invoices (au lieu de /api/dynamic-invoices)
Utilise SalesInvoiceProcessingService et SalesInvoiceRepository
Mêmes endpoints :
POST /upload
GET /{id}
PUT /{id}
GET (liste avec filtres dossier/status)
POST /{id}/validate
POST /{id}/reprocess
GET /{id}/download
Même sécurité session (@RequireRole, resolveDossierId, requireDossierForUser)