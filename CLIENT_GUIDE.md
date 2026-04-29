# Guide Client - Gestion des Factures et Relevés

## Vue d'ensemble

En tant que client, vous avez maintenant accès à une interface complète de gestion de vos documents comptables. Vous pouvez :

- **Uploader et voir les factures d'achat** (factures fournisseur)
- **Uploader et voir les factures de vente** (factures clients)
- **Uploader et voir les relevés bancaires**
- **Uploader et voir les relevés pour centre monétique**

---

## Dashboard Client

### 1. Accès au Dashboard

**Endpoint:** `GET /api/client/dashboard`

**Description:** Récupère le dashboard complet avec un aperçu de tous vos documents.

**Response:** 
```json
{
  "success": true,
  "clientId": 123,
  "clientName": "Client Name",
  "activeDossier": {
    "id": 456,
    "name": "Dossier 2026",
    "exerciseStartDate": "2026-01-01",
    "exerciseEndDate": "2026-12-31",
    "comptableId": 789,
    "comptableName": "Comptable Name"
  },
  "stats": {
    "totalBuyingInvoices": 45,
    "totalSalesInvoices": 32,
    "totalBankStatements": 12,
    "totalCentreMonetique": 8,
    "pendingBuyingInvoices": 5,
    "pendingSalesInvoices": 3,
    "pendingBankStatements": 2,
    "pendingTotal": 10
  },
  "recentBuyingInvoices": [...],
  "recentSalesInvoices": [...],
  "recentBankStatements": [...],
  "recentCentreMonetique": [...]
}
```

---

## Factures d'Achat (Fournisseur)

### 1. Uploader une Facture d'Achat

**Endpoint:** `POST /api/dynamic-invoices/upload`

**Method:** POST  
**Content-Type:** multipart/form-data

**Parameters:**
- `file` (required): Le fichier PDF, JPG, JPEG ou PNG
- `dossierId` (optional): ID du dossier (si non spécifié, le dossier par défaut est utilisé)

**Example:**
```bash
curl -X POST http://localhost:8089/api/dynamic-invoices/upload \
  -H "Content-Type: multipart/form-data" \
  -F "file=@facture_fournisseur.pdf" \
  -F "dossierId=456"
```

**Response:**
```json
{
  "id": 1001,
  "filename": "facture_fournisseur.pdf",
  "status": "UPLOADED",
  "supplierName": "ACME Inc",
  "invoiceNumber": "INV-2026-001",
  "invoiceDate": "2026-04-15",
  "totalAmount": 1500.50,
  "createdAt": "2026-04-29T12:00:00"
}
```

### 2. Voir la Liste des Factures d'Achat

**Endpoint:** `GET /api/client/dashboard/buying-invoices`

**Parameters:**
- `dossierId` (optional): ID du dossier
- `status` (optional): Filtre par statut (UPLOADED, VALIDATED, ACCOUNTED, ERROR, DUPLICATE, OUT_OF_PERIOD)
- `page` (default: 0): Numéro de page
- `limit` (default: 50): Nombre de résultats par page

**Example:**
```bash
curl "http://localhost:8089/api/client/dashboard/buying-invoices?status=UPLOADED&page=0&limit=20"
```

**Response:**
```json
{
  "success": true,
  "page": 0,
  "limit": 20,
  "total": 45,
  "invoices": [
    {
      "id": 1001,
      "filename": "facture_fournisseur.pdf",
      "status": "UPLOADED",
      "supplierName": "ACME Inc",
      "invoiceNumber": "INV-2026-001",
      "invoiceDate": "2026-04-15",
      "totalAmount": 1500.50,
      "createdAt": "2026-04-29T12:00:00",
      "clientValidated": false
    },
    ...
  ]
}
```

### 3. Valider une Facture d'Achat (Client)

**Endpoint:** `POST /api/dynamic-invoices/{id}/client-validate`

**Description:** Permet au client de confirmer qu'il approuve la facture d'achat.

**Parameters:**
- `id` (required): ID de la facture
- `dossierId` (optional): ID du dossier

**Example:**
```bash
curl -X POST http://localhost:8089/api/dynamic-invoices/1001/client-validate?dossierId=456
```

**Response:**
```json
{
  "message": "Facture validee par le client",
  "invoice": {...}
}
```

---

## Factures de Vente

### 1. Uploader une Facture de Vente

**Endpoint:** `POST /api/sales/invoices/upload`

**Method:** POST  
**Content-Type:** multipart/form-data

**Parameters:**
- `file` (required): Le fichier PDF, JPG, JPEG ou PNG
- `dossierId` (optional): ID du dossier

**Example:**
```bash
curl -X POST http://localhost:8089/api/sales/invoices/upload \
  -H "Content-Type: multipart/form-data" \
  -F "file=@facture_vente.pdf" \
  -F "dossierId=456"
```

### 2. Voir la Liste des Factures de Vente

**Endpoint:** `GET /api/client/dashboard/sales-invoices`

**Parameters:**
- `dossierId` (optional): ID du dossier
- `status` (optional): Filtre par statut
- `page` (default: 0): Numéro de page
- `limit` (default: 50): Nombre de résultats par page

**Example:**
```bash
curl "http://localhost:8089/api/client/dashboard/sales-invoices?page=0&limit=20"
```

**Response:**
```json
{
  "success": true,
  "page": 0,
  "limit": 20,
  "total": 32,
  "invoices": [
    {
      "id": 2001,
      "filename": "facture_vente.pdf",
      "status": "UPLOADED",
      "clientName": "Client XYZ",
      "invoiceNumber": "VENTE-2026-001",
      "invoiceDate": "2026-04-20",
      "totalAmount": 2500.00,
      "createdAt": "2026-04-29T12:00:00",
      "clientValidated": false
    },
    ...
  ]
}
```

### 3. Valider une Facture de Vente (Client)

**Endpoint:** `POST /api/sales/invoices/{id}/client-validate`

**Description:** Permet au client de confirmer qu'il approuve la facture de vente de sa part.

**Parameters:**
- `id` (required): ID de la facture
- `dossierId` (optional): ID du dossier

**Example:**
```bash
curl -X POST http://localhost:8089/api/sales/invoices/2001/client-validate?dossierId=456
```

---

## Relevés Bancaires

### 1. Uploader un Relevé Bancaire

**Endpoint:** `POST /api/bank-statements/upload`

**Method:** POST  
**Content-Type:** multipart/form-data

**Parameters:**
- `file` (required): Le fichier PDF, XLS, XLSX ou CSV
- `dossierId` (optional): ID du dossier
- `bankName` (optional): Nom de la banque

**Example:**
```bash
curl -X POST http://localhost:8089/api/bank-statements/upload \
  -H "Content-Type: multipart/form-data" \
  -F "file=@releve_bancaire.pdf" \
  -F "dossierId=456" \
  -F "bankName=BanqueXYZ"
```

### 2. Voir la Liste des Relevés Bancaires

**Endpoint:** `GET /api/client/dashboard/bank-statements`

**Parameters:**
- `dossierId` (optional): ID du dossier
- `page` (default: 0): Numéro de page
- `limit` (default: 50): Nombre de résultats par page

**Example:**
```bash
curl "http://localhost:8089/api/client/dashboard/bank-statements?page=0&limit=20"
```

**Response:**
```json
{
  "success": true,
  "page": 0,
  "limit": 20,
  "total": 12,
  "statements": [
    {
      "id": 3001,
      "filename": "releve_bancaire.pdf",
      "bankName": "BanqueXYZ",
      "status": "UPLOADED",
      "createdAt": "2026-04-29T12:00:00"
    },
    ...
  ]
}
```

---

## Relevés Centre Monétique

### 1. Uploader un Relevé Centre Monétique

**Endpoint:** `POST /api/centre-monetique/upload`

**Method:** POST  
**Content-Type:** multipart/form-data

**Parameters:**
- `file` (required): Le fichier PDF, XLS, XLSX ou CSV
- `dossierId` (optional): ID du dossier

**Example:**
```bash
curl -X POST http://localhost:8089/api/centre-monetique/upload \
  -H "Content-Type: multipart/form-data" \
  -F "file=@centre_monetique.pdf" \
  -F "dossierId=456"
```

### 2. Voir la Liste des Relevés Centre Monétique

**Endpoint:** `GET /api/client/dashboard/centre-monetique`

**Parameters:**
- `dossierId` (optional): ID du dossier
- `page` (default: 0): Numéro de page
- `limit` (default: 50): Nombre de résultats par page

**Example:**
```bash
curl "http://localhost:8089/api/client/dashboard/centre-monetique?page=0&limit=20"
```

**Response:**
```json
{
  "success": true,
  "page": 0,
  "limit": 20,
  "total": 8,
  "batches": [
    {
      "id": 4001,
      "filename": "centre_monetique.pdf",
      "status": "UPLOADED",
      "structure": "CB|VIREMENT",
      "createdAt": "2026-04-29T12:00:00"
    },
    ...
  ]
}
```

---

## Uploader Plusieurs Fichiers à la Fois

### 1. Uploader Plusieurs Factures d'Achat

**Endpoint:** `POST /api/dynamic-invoices/upload/batch`

**Method:** POST  
**Content-Type:** multipart/form-data

**Parameters:**
- `files` (required): Plusieurs fichiers
- `dossierId` (optional): ID du dossier

**Example:**
```bash
curl -X POST http://localhost:8089/api/dynamic-invoices/upload/batch \
  -H "Content-Type: multipart/form-data" \
  -F "files=@facture1.pdf" \
  -F "files=@facture2.pdf" \
  -F "files=@facture3.pdf" \
  -F "dossierId=456"
```

**Response:**
```json
{
  "count": 3,
  "successCount": 3,
  "errorCount": 0,
  "results": [
    {
      "filename": "facture1.pdf",
      "status": "success",
      "invoice": {...}
    },
    ...
  ]
}
```

### 2. Uploader Plusieurs Factures de Vente

**Endpoint:** `POST /api/sales/invoices/upload/batch`

**Method:** POST  
**Content-Type:** multipart/form-data

---

## Suppression de Fichiers

### Supprimer une Facture d'Achat

**Endpoint:** `DELETE /api/dynamic-invoices/{id}`

**Parameters:**
- `id` (required): ID de la facture
- `dossierId` (optional): ID du dossier

**Example:**
```bash
curl -X DELETE http://localhost:8089/api/dynamic-invoices/1001?dossierId=456
```

### Supprimer une Facture de Vente

**Endpoint:** `DELETE /api/sales/invoices/{id}`

**Parameters:**
- `id` (required): ID de la facture
- `dossierId` (optional): ID du dossier

**Example:**
```bash
curl -X DELETE http://localhost:8089/api/sales/invoices/2001?dossierId=456
```

---

## Statuts des Factures

Les statuts possibles pour les factures sont :

- **UPLOADED** : Facture uploadée mais non traitée
- **READY_TO_VALIDATE** : Facture extraite et prête à validation
- **TREATED** : Facture traitée par OCR
- **RECALCULATED** : Facture recalculée
- **VALIDATED** : Facture validée par le comptable
- **ACCOUNTED** : Facture comptabilisée
- **ERROR** : Erreur lors du traitement
- **DUPLICATE** : Duplicata détecté
- **OUT_OF_PERIOD** : Facture hors période d'exercice

---

## Gestion des Dossiers

Vous pouvez avoir plusieurs dossiers (ex: dossier 2025, dossier 2026). 

### Changer de Dossier Actif

**Endpoint:** `POST /api/dossiers/active`

**Parameters:**
```json
{
  "dossierId": 456
}
```

**Example:**
```bash
curl -X POST http://localhost:8089/api/dossiers/active \
  -H "Content-Type: application/json" \
  -d '{"dossierId": 456}'
```

### Voir le Dossier Actif

**Endpoint:** `GET /api/dossiers/active`

---

## Filtres Disponibles

### Filtrer par Statut

Tous les endpoints de listing acceptent un paramètre `status` :

```bash
# Voir uniquement les factures validées
curl "http://localhost:8089/api/client/dashboard/buying-invoices?status=VALIDATED"

# Voir les factures en attente
curl "http://localhost:8089/api/client/dashboard/buying-invoices?status=READY_TO_VALIDATE"
```

### Pagination

Tous les endpoints supportent la pagination avec `page` et `limit` :

```bash
# Page 0, 50 résultats par page
curl "http://localhost:8089/api/client/dashboard/buying-invoices?page=0&limit=50"

# Page 1 (2e page), 25 résultats par page
curl "http://localhost:8089/api/client/dashboard/buying-invoices?page=1&limit=25"
```

---

## Flux Standard de Traitement

### Pour une Facture d'Achat

1. **Upload** → `POST /api/dynamic-invoices/upload`
   - État: UPLOADED

2. **Traitement OCR** (automatique ou manuel)
   - État: TREATED

3. **Vérification** par le client
   - Voir: `GET /api/client/dashboard/buying-invoices`

4. **Validation Client** → `POST /api/dynamic-invoices/{id}/client-validate`
   - État: clientValidated = true

5. **Validation Comptable** (faite par le comptable)
   - État: VALIDATED

6. **Comptabilisation** (faite par le comptable)
   - État: ACCOUNTED

### Pour une Facture de Vente

Le processus est identique en utilisant :
- `/api/sales/invoices/upload`
- `/api/client/dashboard/sales-invoices`
- `/api/sales/invoices/{id}/client-validate`

---

## Codes d'Erreur Courants

| Code HTTP | Message | Cause |
|-----------|---------|-------|
| 401 | unauthorized | Pas d'authentification |
| 403 | dossier_forbidden | Accès non autorisé au dossier |
| 400 | dossier_required | Le dossier n'a pas été spécifié |
| 400 | Fichier vide | Le fichier uploadé est vide |
| 400 | Type de fichier non supporté | Format de fichier incompatible |
| 409 | Cette facture est doublon par nom | Nom de fichier déjà existant |
| 400 | Facture hors période d'exercice | Date de facture invalide |
| 404 | Not Found | Ressource non trouvée |
| 500 | Internal Server Error | Erreur serveur |

---

## Conseils d'Utilisation

1. **Organisations des factures** : Nommez vos fichiers clairement (ex: `FACTURE_FOURNISSEUR_2026_04_29.pdf`)

2. **Format des documents** : Utilisez de préférence des PDF pour les relevés bancaires et factures

3. **Fichiers en lot** : Utilisez les endpoints `/batch` pour upload plusieurs fichiers à la fois

4. **Suivi du statut** : Consultez régulièrement le dashboard pour voir l'état de traitement

5. **Validation rapide** : Validez vos factures dès que possible pour accélérer le processus de comptabilisation

---

## Support

Pour toute question ou problème, contactez votre comptable via le compteur dédié dans votre dossier.

