# API Endpoints - Client Session

## Récapitulatif des Fonctionnalités Client

Le client peut désormais accéder à une interface complète de gestion des factures et relevés bancaires.

---

## 1. DASHBOARD CLIENT

### Endpoint: GET `/api/client/dashboard`

Récupère un aperçu complet du dossier actif avec statistiques et documents récents.

**Réponse:**
- Client ID et nom
- Dossier actif (ID, nom, dates d'exercice, comptable assigné)
- Statistiques (totaux et en attente pour chaque type de document)
- 10 derniers documents de chaque type

**Utilisations principales :**
- Page d'accueil / Dashboard client
- Vue d'ensemble rapide des tâches en cours

---

## 2. FACTURES D'ACHAT (Fournisseur)

### 2.1 Upload d'une facture
**Endpoint:** `POST /api/dynamic-invoices/upload`
- File: rapport PDF/JPG/PNG
- Retourne: ID facture, statut, métadonnées

### 2.2 Upload batch
**Endpoint:** `POST /api/dynamic-invoices/upload/batch`
- Files: plusieurs fichiers
- Retourne: liste avec succès/erreurs pour chaque fichier

### 2.3 Lister les factures
**Endpoint:** `GET /api/client/dashboard/buying-invoices`
- Query params: `page`, `limit`, `status`
- Retourne: liste paginée des factures d'achat

### 2.4 Obtenir détails
**Endpoint:** `GET /api/dynamic-invoices/{id}`
- Retourne: détails complets de la facture

### 2.5 Valider (client)
**Endpoint:** `POST /api/dynamic-invoices/{id}/client-validate`
- Marque la facture comme validée par le client

### 2.6 Supprimer
**Endpoint:** `DELETE /api/dynamic-invoices/{id}`
- Supprime la facture (si non validée par comptable)

---

## 3. FACTURES DE VENTE

### 3.1 Upload d'une facture
**Endpoint:** `POST /api/sales/invoices/upload`
- File: rapport PDF/JPG/PNG
- Retourne: ID facture, statut, métadonnées

### 3.2 Upload batch
**Endpoint:** `POST /api/sales/invoices/upload/batch`
- Files: plusieurs fichiers
- Retourne: liste avec succès/erreurs pour chaque fichier

### 3.3 Lister les factures
**Endpoint:** `GET /api/client/dashboard/sales-invoices`
- Query params: `page`, `limit`, `status`
- Retourne: liste paginée des factures de vente

### 3.4 Obtenir détails
**Endpoint:** `GET /api/sales/invoices/{id}`
- Retourne: détails complets de la facture

### 3.5 Valider (client)
**Endpoint:** `POST /api/sales/invoices/{id}/client-validate`
- Marque la facture comme validée par le client

### 3.6 Supprimer
**Endpoint:** `DELETE /api/sales/invoices/{id}`
- Supprime la facture (si non validée par comptable)

---

## 4. RELEVÉS BANCAIRES

### 4.1 Upload d'un relevé
**Endpoint:** `POST /api/bank-statements/upload`
- File: rapport PDF/XLS/XLSX/CSV
- Params: `bankName` (optionnel)
- Retourne: ID relevé, statut

### 4.2 Upload batch
**Endpoint:** `POST /api/bank-statements/upload/batch`
- Files: plusieurs fichiers
- Retourne: liste avec succès/erreurs

### 4.3 Lister les relevés
**Endpoint:** `GET /api/client/dashboard/bank-statements`
- Query params: `page`, `limit`
- Retourne: liste paginée des relevés

### 4.4 Obtenir détails
**Endpoint:** `GET /api/bank-statements/{id}`
- Retourne: détails du relevé avec transactions

### 4.5 Supprimer
**Endpoint:** `DELETE /api/bank-statements/{id}`
- Supprime le relevé (si non traité)

---

## 5. RELEVÉS CENTRE MONÉTIQUE

### 5.1 Upload d'un relevé
**Endpoint:** `POST /api/centre-monetique/upload`
- File: rapport PDF/XLS/XLSX/CSV
- Retourne: ID batch, statut

### 5.2 Upload batch
**Endpoint:** `POST /api/centre-monetique/upload/batch`
- Files: plusieurs fichiers
- Retourne: liste avec succès/erreurs

### 5.3 Lister les relevés
**Endpoint:** `GET /api/client/dashboard/centre-monetique`
- Query params: `page`, `limit`
- Retourne: liste paginée des relevés CB

### 5.4 Obtenir détails
**Endpoint:** `GET /api/centre-monetique/{id}`
- Retourne: détails du relevé avec transactions

### 5.5 Supprimer
**Endpoint:** `DELETE /api/centre-monetique/{id}`
- Supprime le relevé (si non traité)

---

## 6. DOSSIERS

### 6.1 Lister mes dossiers
**Endpoint:** `GET /api/dossiers`
- Retourne: liste de mes dossiers avec statistiques

### 6.2 Obtenir dossier actif
**Endpoint:** `GET /api/dossiers/active`
- Retourne: dossier actuellement sélectionné

### 6.3 Changer de dossier
**Endpoint:** `POST /api/dossiers/active`
- Body: `{ "dossierId": 123 }`
- Définit le dossier actif pour les uploads suivants

---

## Flux Recommandé d'Utilisation - Client

### Première Visite

1. **GET** `/api/dossiers` → voir mes dossiers
2. **POST** `/api/dossiers/active` → sélectionner le dossier
3. **GET** `/api/client/dashboard` → voir vue d'ensemble

### Upload de Factures Régulier

**Factures d'achat (batch):**
```
POST /api/dynamic-invoices/upload/batch
  files: [file1.pdf, file2.pdf, ...]
  dossierId: 123
```

**Factures de vente (batch):**
```
POST /api/sales/invoices/upload/batch
  files: [file1.pdf, file2.pdf, ...]
  dossierId: 123
```

**Relevés bancaires (batch):**
```
POST /api/bank-statements/upload/batch
  files: [releve.pdf, ...]
  dossierId: 123
```

### Suivi et Validation

1. **GET** `/api/client/dashboard` → voir nouvelles factures
2. **GET** `/api/client/dashboard/buying-invoices?status=READY_TO_VALIDATE` → factures à valider
3. **POST** `/api/dynamic-invoices/{id}/client-validate` → valider une facture
4. **GET** `/api/client/dashboard/sales-invoices?status=READY_TO_VALIDATE` → factures à valider

### Nettoyage (si erreur)

- **DELETE** `/api/dynamic-invoices/{id}` → supprimer facture achat
- **DELETE** `/api/sales/invoices/{id}` → supprimer facture vente
- **DELETE** `/api/bank-statements/{id}` → supprimer relevé bancaire
- **DELETE** `/api/centre-monetique/{id}` → supprimer relevé CB

---

## Statuts et Filtres Disponibles

### Statuts de Facture

| Statut | Description |
|--------|-------------|
| UPLOADED | Uploadée, pas encore traitée |
| READY_TO_VALIDATE | OCR complétée, prête à validation |
| TREATED | Traitée par OCR |
| RECALCULATED | Recalculée |
| VALIDATED | Validée par comptable |
| ACCOUNTED | Comptabilisée |
| ERROR | Erreur durant le traitement |
| DUPLICATE | Doublon détecté |
| OUT_OF_PERIOD | Hors période d'exercice |

### Statuts de Relevé Bancaire

| Statut | Description |
|--------|-------------|
| UPLOADED | Uploadé |
| PROCESSED | Traité |
| COMPTABILISE | Comptabilisé |
| ERROR | Erreur |
| DUPLIQUE | Doublon |

### Statuts Centre Monétique

| Statut | Description |
|--------|-------------|
| UPLOADEDED | Uploadé |
| PROCESSED | Traité |
| COMPTABILISE | Comptabilisé |
| ERROR | Erreur |

---

## Paramètres Courants

### Pagination

Disponible sur tous les endpoints de liste:
- `page`: Numéro de page (défaut: 0)
- `limit`: Éléments par page (défaut: 50)

### Filtrage

- `status`: Filtrer par statut
- `dossierId`: Limiter à un dossier spécifique

---

## Erreurs Courantes et Solutions

| Erreur | Cause | Solution |
|--------|-------|----------|
| `dossier_required` | Aucun dossier spécifié | Appeler POST `/api/dossiers/active` d'abord |
| `dossier_forbidden` | Pas accès au dossier | Vérifier l'ID du dossier |
| `Fichier vide` | File size = 0 | Envoyer un fichier valide |
| `Type de fichier non supporté` | Extension non reconnue | Utiliser PDF/JPG/PNG pour factures |
| `Cette facture est doublon par nom` | Nom fichier déjà uploadé | Renommer le fichier et réessayer |
| `Facture hors période d'exercice` | Date en dehors exercise dates | Vérifier les dates de la facture |
| `client_validated` | Facture déjà validée par comptable | Impossible de supprimer |
| 401 Unauthorized | Pas connecté en tant que client | Se reconnecter |

---

## Exemple Complet - Workflow Client

### Jour 1: Upload Documents

```bash
# 1. Récupérer les dossiers
curl "http://localhost:8089/api/dossiers" \
  -H "Cookie: JSESSIONID=..."

# 2. Sélectionner dossier 456
curl -X POST "http://localhost:8089/api/dossiers/active" \
  -H "Cookie: JSESSIONID=..." \
  -H "Content-Type: application/json" \
  -d '{"dossierId": 456}'

# 3. Upload 3 factures d'achat
curl -X POST "http://localhost:8089/api/dynamic-invoices/upload/batch" \
  -H "Cookie: JSESSIONID=..." \
  -F "files=@facture1.pdf" \
  -F "files=@facture2.pdf" \
  -F "files=@facture3.pdf" \
  -F "dossierId=456"

# 4. Upload relevé bancaire
curl -X POST "http://localhost:8089/api/bank-statements/upload" \
  -H "Cookie: JSESSIONID=..." \
  -F "file=@releve_bancaire.pdf" \
  -F "dossierId=456" \
  -F "bankName=Crédit Agricole"
```

### Jour 2: Voir et Valider

```bash
# 1. Voir dashboard
curl "http://localhost:8089/api/client/dashboard" \
  -H "Cookie: JSESSIONID=..."

# 2. Voir factures à valider
curl "http://localhost:8089/api/client/dashboard/buying-invoices?status=READY_TO_VALIDATE" \
  -H "Cookie: JSESSIONID=..."

# 3. Valider facture 1001
curl -X POST "http://localhost:8089/api/dynamic-invoices/1001/client-validate?dossierId=456" \
  -H "Cookie: JSESSIONID=..."

# 4. Voir factures validation réussie
curl "http://localhost:8089/api/client/dashboard/buying-invoices?status=VALIDATED" \
  -H "Cookie: JSESSIONID=..."
```

---

## Note Importante

**Sécurité:**
- Seul le client propriétaire du dossier peut voir/modifier ses documents
- Chaque requête vérifie les droits d'accès
- Les factures validées par le comptable ne peuvent être supprimées par le client

**Performance:**
- Utilisez `limit` pour limiter les requêtes longues
- Uploadez en batch pour plusieurs fichiers
- Mettez en cache le dashboard pour éviter rafraîchissements constants

