# Plan Détaillé du Rapport PFE — Plateforme Comptable Intelligente
**Étudiant :** Najwa Ourbat | **École :** ENSA Marrakech | **Année :** 2024-2025
**Entreprise d'accueil :** [Nom de l'entreprise]
**Encadrant académique :** [Nom] | **Encadrant industriel :** [Nom]

---

> **Note sur ce document :**
> Ce plan est construit en **5 chapitres** (standard ENSA/PFE Maroc).
> Chaque section indique : **ce qu'on écrit + pourquoi c'est là + ce qu'on inclut**.
> Pages estimées : **65 à 80 pages** hors annexes.

---

## PAGES PRÉLIMINAIRES (avant page 1)

| Page | Contenu | Pourquoi |
|---|---|---|
| Page de garde | Titre, étudiant, école, encadrants, logo | Obligatoire — première impression |
| Dédicaces | Personnel | Tradition académique ENSA |
| Remerciements | Encadrants, équipe, école | Courtoisie professionnelle |
| Résumé (FR) | 200 mots : contexte, solution, résultat | Résumé pour le jury avant lecture |
| Abstract (EN) | Même contenu en anglais | Valeur internationale du rapport |
| الملخص | Même contenu en arabe | Exigence académique nationale |
| Liste des abréviations | OCR, API, REST, RBAC, CI/CD, JWT... | Évite les répétitions |
| Table des figures | Numéros + légendes | Navigation dans le rapport |
| Liste des tableaux | Numéros + titres | Navigation dans le rapport |
| Table des matières | Sommaire complet | Lecture ciblée |

---

## INTRODUCTION GÉNÉRALE — pages 1 à 3

**Ce qu'on écrit :**
Poser le décor : la saisie manuelle des documents comptables au Maroc (factures, relevés) coûte du temps et génère des erreurs. La plateforme qu'on développe automatise ce traitement grâce à l'OCR et un workflow digital complet.

**Pourquoi c'est là :**
L'introduction donne envie de lire le rapport. Elle annonce le problème, la solution et la structure. Elle doit être lisible par quelqu'un qui ne connaît pas le projet.

**Ce qu'on inclut :**
- Paragraphe 1 : Contexte général (digitalisation comptable au Maroc)
- Paragraphe 2 : Problématique (saisie manuelle = coûts + erreurs)
- Paragraphe 3 : Objectifs du projet (automatisation OCR, workflow, validation)
- Paragraphe 4 : Structure du rapport (un paragraphe par chapitre)

**Astuce rédaction :** Écrire l'introduction EN DERNIER — après avoir tout rédigé, le texte sera plus précis et cohérent.

---

## CHAPITRE 1 : Contexte et Cadre Général du Projet — pages 4 à 20

**Objectif du chapitre :** Présenter le cadre dans lequel s'inscrit le projet : l'organisme d'accueil, le projet lui-même, la méthodologie de travail et la planification.

---

### 1.1 Présentation de l'Organisme d'Accueil — pages 4 à 8

**Ce qu'on écrit :**
Présenter l'entreprise qui a commandé ou accueilli le projet.

**Pourquoi c'est là :**
Le jury doit comprendre le contexte réel du projet, pas juste la technique. Cela montre que la solution répond à un besoin professionnel concret.

**Ce qu'on inclut :**
- Logo de l'entreprise
- Fiche signalétique (tableau : Nom, Secteur, Effectif, Adresse, Site web)
- Activité principale (cabinet comptable / éditeur logiciel ?)
- Organisation interne (organigramme simplifié)
- Positionnement du projet dans l'activité de l'entreprise
- Problème concret vécu par l'entreprise avant le projet

---

### 1.2 Présentation du Projet — pages 8 à 12

**Ce qu'on écrit :**
Décrire la plateforme : ce qu'elle fait, pour qui, et comment elle s'intègre dans le travail du cabinet.

**Pourquoi c'est là :**
Avant d'entrer dans la technique, le jury doit avoir une vision claire de ce qu'est le produit final.

**Ce qu'on inclut :**

**1.2.1 Contexte métier**
> Dans un cabinet comptable, le comptable reçoit des centaines de factures et relevés bancaires chaque mois. Aujourd'hui, il les saisit manuellement dans le logiciel de comptabilité — opération longue, répétitive et source d'erreurs. Notre plateforme automatise cette chaîne.

**1.2.2 Workflow global** (inclure un schéma)
```
Client              Comptable              Admin
  |                     |                    |
[Upload document]       |                    |
  |──────────────────>  |                    |
  |              [OCR automatique]           |
  |              [Extraction des champs]     |
  |              [Validation métier]         |
  |              [Correction si besoin]      |
  |              [Comptabilisation]──────────>|
```

**1.2.3 Acteurs du système**

| Acteur | Rôle |
|---|---|
| ADMIN | Gère les utilisateurs, dossiers, paramètres. Peut traiter directement ses uploads. |
| COMPTABLE | Traite les documents validés par le client, les comptabilise. |
| CLIENT | Uploade ses documents, les valide avant traitement comptable. |

**1.2.4 Les 4 types de documents traités**

| Document | Module | Description |
|---|---|---|
| Facture d'achat | DynamicInvoice | Factures fournisseurs (charges) |
| Facture de vente | SalesInvoice | Factures émises par le client |
| Relevé bancaire | BankStatement | Mouvements bancaires mensuels |
| Centre monétique | CentreMonetique | Transactions carte bancaire/TPE |

---

### 1.3 Problématique et Objectifs — pages 12 à 15

**Ce qu'on écrit :**
La problématique précise + les objectifs fonctionnels et techniques.

**Pourquoi c'est là :**
C'est le "pourquoi du projet". Le jury doit comprendre le problème avant d'évaluer la solution.

**Problématique :**
> Comment automatiser l'extraction et la structuration des données comptables à partir de documents hétérogènes (PDF, images scannées) tout en garantissant la fiabilité des données et en s'adaptant à la diversité des formats de documents marocains ?

**Objectifs fonctionnels :**
- Upload et stockage des documents (PDF, JPG, PNG)
- Extraction automatique via OCR : ICE, IF, RC, montants HT/TVA/TTC, date, numéro facture
- Workflow de statuts : PENDING → PROCESSING → TREATED → READY_TO_VALIDATE → VALIDATED → ACCOUNTED
- Validation par le client avant traitement comptable
- Comptabilisation automatique des documents validés
- Gestion des tiers (fournisseurs, clients) et des comptes comptables
- Rapprochement entre relevés bancaires et centre monétique

**Objectifs techniques :**
- Architecture multi-tenant (1 dossier = 1 entreprise, isolation des données)
- API REST sécurisée avec contrôle des rôles (RBAC)
- Pipeline OCR multi-moteurs (PaddleOCR, Tesseract, AlphaAgent)
- Déploiement conteneurisé (Docker)
- Pipeline CI/CD automatisé (GitHub Actions)

---

### 1.4 Méthodologie et Planification — pages 15 à 20

**Ce qu'on écrit :**
Comment le projet a été organisé et planifié dans le temps.

**Pourquoi c'est là :**
Montre que le développement était structuré, pas improvisé. Prouve la maturité dans la gestion de projet.

**Ce qu'on inclut :**

**1.4.1 Méthodologie Agile (SCRUM)**
> Le projet a suivi une approche Agile avec des sprints de 2 semaines. Chaque sprint cible un module ou une fonctionnalité. Les commits Git marquent les jalons.

**1.4.2 Outils de gestion**

| Outil | Usage |
|---|---|
| Git + GitHub | Versionnement du code |
| GitHub Actions | Pipeline CI/CD |
| VS Code + IntelliJ | IDE de développement |
| MariaDB Workbench | Gestion base de données |
| Postman | Tests des API REST |

**1.4.3 Diagramme de Gantt**
(Tableau ou image montrant les phases : Analyse → Conception → Module Achat → Module Vente → Module Bancaire → Centre Monétique → DevOps → Tests → Rapport)

**Conclusion du chapitre 1 :**
(2-3 phrases résumant ce chapitre et annonçant le suivant)

---

## CHAPITRE 2 : Étude Fonctionnelle et Conception — pages 21 à 38

**Objectif du chapitre :** Analyser les besoins en détail et les modéliser avec des diagrammes UML. C'est le chapitre "papier" avant le code.

---

### 2.1 Capture des Besoins — pages 21 à 25

**Ce qu'on écrit :**
Lister et décrire les besoins fonctionnels et non-fonctionnels.

**Pourquoi c'est là :**
Avant de coder, on doit savoir exactement quoi construire. Ce chapitre montre qu'on a analysé le besoin avant d'implémenter.

**2.1.1 Besoins fonctionnels**

| BF | Description |
|---|---|
| BF-01 | L'utilisateur peut uploader un document (PDF, JPG, PNG) |
| BF-02 | Le système extrait automatiquement les champs comptables via OCR |
| BF-03 | L'utilisateur peut corriger manuellement les champs mal extraits |
| BF-04 | Le CLIENT valide le document avant traitement comptable |
| BF-05 | L'ADMIN peut traiter ses propres uploads sans attendre le client |
| BF-06 | Le COMPTABLE comptabilise les documents validés |
| BF-07 | Le système détecte les doublons et les documents hors période |
| BF-08 | Le système gère les tiers et les comptes comptables |
| BF-09 | Le système traite les relevés bancaires et les transactions monétiques |
| BF-10 | Le système génère les journaux comptables |

**2.1.2 Besoins non-fonctionnels**

| BNF | Description |
|---|---|
| BNF-01 | **Sécurité** : Authentification par session, RBAC sur tous les endpoints |
| BNF-02 | **Isolation** : Un utilisateur ne peut pas accéder aux données d'un autre dossier |
| BNF-03 | **Performance** : Traitement OCR asynchrone pour ne pas bloquer l'interface |
| BNF-04 | **Disponibilité** : Déploiement Docker + CI/CD pour déploiements fiables |
| BNF-05 | **Maintenabilité** : Architecture en couches (Controller/Service/Repository) |
| BNF-06 | **Traçabilité** : Chaque action est horodatée et associée à un utilisateur |

---

### 2.2 Identification des Acteurs et Cas d'Utilisation — pages 25 à 30

**Ce qu'on écrit :**
Les acteurs du système et leurs interactions (diagramme Use Case UML).

**Pourquoi c'est là :**
Le diagramme de cas d'utilisation est une exigence académique. Il montre visuellement qui fait quoi dans le système.

**Acteurs :**
- **ADMIN** : acteur principal avec accès total
- **COMPTABLE** : acteur principal pour le traitement comptable
- **CLIENT** : acteur secondaire qui uploade et valide ses documents
- **Système OCR** : acteur externe (PaddleOCR, Tesseract)

**Principaux cas d'utilisation à représenter :**

*Pour ADMIN :*
- Gérer les utilisateurs (créer, activer, désactiver)
- Gérer les dossiers (créer, configurer la période d'exercice)
- Uploader et traiter directement un document
- Comptabiliser les documents
- Configurer les paramètres du dossier

*Pour COMPTABLE :*
- Voir les documents validés par le client
- Corriger et valider les champs extraits
- Comptabiliser un document
- Générer les journaux comptables

*Pour CLIENT :*
- Uploader un document
- Consulter ses documents et leur statut
- Valider un document pour traitement

**Description détaillée d'un cas d'utilisation (exemple) :**

> **UC-03 : Traitement d'une facture d'achat**
> - **Acteur :** COMPTABLE ou ADMIN
> - **Précondition :** Le document est uploadé et clientValidated = true
> - **Déclencheur :** Le comptable clique sur "Traiter"
> - **Scénario nominal :**
>   1. Le système lance l'OCR (PaddleOCR)
>   2. Les champs sont extraits (ICE, montants, date...)
>   3. La validation métier vérifie la cohérence (HT + TVA ≈ TTC)
>   4. Le statut passe à READY_TO_VALIDATE
>   5. L'utilisateur valide → statut VALIDATED
>   6. La facture est comptabilisée → statut ACCOUNTED
> - **Exception :** OCR faible confiance → fallback Tesseract → intervention manuelle

---

### 2.3 Diagramme de Classes — pages 30 à 34

**Ce qu'on écrit :**
Le modèle de données principal avec les relations entre entités.

**Pourquoi c'est là :**
Montre la structure de la base de données et les relations entre objets. Preuve que la conception est solide avant l'implémentation.

**Entités principales à représenter :**

```
UserAccount ─── (1,*) ─── Dossier
     |                        |
  UserRole               DossierGeneralParams
(ADMIN/COMPTABLE/CLIENT)       |
                         ┌─────┴──────────────────────┐
                         |            |                |
                    DynamicInvoice  SalesInvoice    BankStatement
                     (Facture achat) (Facture vente) (Relevé bancaire)
                         |                               |
                    InvoiceStatus                    BankStatus
                    - PENDING                        - PENDING
                    - PROCESSING                     - PROCESSING
                    - TREATED                        - TREATED
                    - READY_TO_VALIDATE              - READY_TO_VALIDATE
                    - VALIDATED                      - VALIDATED
                    - ACCOUNTED                      - COMPTABILISE
                    - ERROR                          - ERROR

DynamicInvoice ─── DynamicTemplate (pattern d'extraction)
DynamicInvoice ─── AccountingEntry (écriture comptable)
DynamicInvoice ─── Tier (fournisseur)
BankStatement ─── BankTransaction (lignes du relevé)
CentreMonetiqueBatch ─── CentreMonetiqueTransaction
```

**Attributs clés à montrer pour DynamicInvoice :**
- id, filename, originalName, filePath
- status (InvoiceStatus)
- clientValidated (Boolean), clientValidatedBy, clientValidatedAt
- extractedData (JSON), fieldsData (JSON)
- missingFields, lowConfidenceFields
- overallConfidence (Double)
- duplicateLevel (NONE/LOW/HIGH)
- dossierId, dossier

---

### 2.4 Diagrammes de Séquence — pages 34 à 38

**Ce qu'on écrit :**
Les échanges entre composants pour les scénarios critiques.

**Pourquoi c'est là :**
Montre dynamiquement comment le système traite un document. C'est le diagramme le plus informatif pour un jury technique.

**Séquence 1 : Upload et traitement d'une facture (ADMIN)**
```
[Frontend] → POST /upload → [DynamicInvoiceController]
   → processingService.processInvoice(file, dossierId, engine)
   → [PaddleOcrService] → texte brut
   → [TextCleaningService] → texte nettoyé
   → [DynamicFieldExtractorService] → champs extraits
   → [BusinessValidationService] → validation montants
   → [DuplicateDetectionService] → vérification doublon
   → DynamicInvoice (status=READY_TO_VALIDATE, clientValidated=true si ADMIN)
   → [DynamicInvoiceDao].save(invoice)
   → 200 OK + JSON
```

**Séquence 2 : Workflow de validation CLIENT → COMPTABLE**
```
[CLIENT] → POST /{id}/client-validate → clientValidated=true
[COMPTABLE] → GET /list → filtre: clientValidated=true
[COMPTABLE] → POST /{id}/validate → status=VALIDATED
[COMPTABLE] → POST /comptabilise → AccountingEntry créée → status=ACCOUNTED
```

**Conclusion du chapitre 2 :**
(2-3 phrases résumant les besoins identifiés et annonçant l'architecture technique)

---

## CHAPITRE 3 : Architecture Technique et Choix Technologiques — pages 39 à 52

**Objectif du chapitre :** Justifier chaque choix technologique et présenter l'architecture globale du système.

---

### 3.1 Architecture Globale — pages 39 à 43

**Ce qu'on écrit :**
La vue d'ensemble de l'architecture (frontend, backend, base de données, OCR).

**Pourquoi c'est là :**
Le jury doit comprendre comment les composants s'articulent avant d'entrer dans les détails de chaque module.

**Ce qu'on inclut :**

**Schéma d'architecture globale :**
```
┌─────────────────────────────────────────────────────────┐
│                    CLIENT (Navigateur)                   │
│              Next.js 15 + React + TypeScript            │
│              shadcn/ui + Tailwind CSS                   │
└──────────────────────────┬──────────────────────────────┘
                           │ HTTP/REST (JSON)
┌──────────────────────────▼──────────────────────────────┐
│                  BACKEND (Spring Boot 3.2)               │
│                      Java 17                            │
│  ┌─────────────┐ ┌──────────────┐ ┌──────────────────┐  │
│  │ Controllers │ │   Services   │ │   Repositories   │  │
│  │  (25 REST)  │ │  (52 métier) │ │  (Spring Data)   │  │
│  └─────────────┘ └──────┬───────┘ └────────┬─────────┘  │
│                         │                  │            │
│  ┌──────────────────────▼──┐   ┌───────────▼─────────┐  │
│  │    Pipeline OCR          │   │    MariaDB           │  │
│  │  PaddleOCR (principal)   │   │  (persistance)       │  │
│  │  Tesseract (fallback)    │   │                     │  │
│  │  AlphaAgent (IA)         │   └─────────────────────┘  │
│  │  OpenCV (prétraitement)  │                            │
│  └──────────────────────────┘                            │
└─────────────────────────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                  INFRASTRUCTURE                          │
│         Docker + Docker Compose                         │
│         GitHub Actions (CI/CD)                         │
│         Prometheus + Spring Actuator (monitoring)       │
└─────────────────────────────────────────────────────────┘
```

**Architecture en couches du backend :**

| Couche | Rôle | Technologie |
|---|---|---|
| Controller | Expose les endpoints REST, valide les inputs | Spring MVC |
| Service | Logique métier, orchestration OCR | Java pur |
| Repository | Accès base de données | Spring Data JPA |
| Entity | Modèle de données persisté | Hibernate/JPA |
| DTO | Transfert de données entre couches | Java POJO |
| Security | Contrôle des rôles et sessions | `@RequireRole` custom |

---

### 3.2 Choix Technologiques Justifiés — pages 43 à 49

**Ce qu'on écrit :**
Pour chaque technologie : présentation + pourquoi ce choix + alternatives rejetées.

**Pourquoi c'est là :**
Le jury évalue la maturité technique par la capacité à justifier les choix, pas juste à les lister.

---

#### 3.2.1 Backend : Java 17 + Spring Boot 3.2

**Présentation :** Framework Java mature pour le développement d'applications web d'entreprise.

**Pourquoi Spring Boot :**
- Auto-configuration : réduction du code boilerplate
- Spring Data JPA : abstraction ORM complète
- Spring Security (session) : gestion native des sessions
- Spring Actuator : métriques de monitoring intégrées
- Écosystème mature : vaste documentation et communauté

**Alternatives rejetées :**
- Node.js/Express → moins adapté aux traitements lourds OCR en mémoire
- Python/Django → bonne pour l'IA, mais moins performant pour une API d'entreprise volumineuse

---

#### 3.2.2 Base de données : MariaDB

**Présentation :** SGBD relationnel open-source, fork MySQL.

**Pourquoi MariaDB :**
- Compatible MySQL (portabilité)
- Performant pour les données structurées comptables (montants, dates, identifiants fiscaux)
- Support natif des colonnes JSON (extractedData, fieldsData)
- Licence libre, pas de coûts de licence

**Alternatives rejetées :**
- PostgreSQL → très bon, mais MariaDB suffisant pour ce besoin + compatibilité Docker simple
- MongoDB → NoSQL inadapté aux données comptables qui nécessitent des jointures et des contraintes ACID

---

#### 3.2.3 OCR Principal : PaddleOCR

**Présentation :** Moteur OCR développé par Baidu, accessible via RapidOCR4j (wrapper Java).

**Pourquoi PaddleOCR :**
- Meilleure reconnaissance des documents arabes et latins mélangés (contexte Maroc)
- Plus précis sur les documents de mauvaise qualité que Tesseract seul
- Fonctionne en local (pas de dépendance cloud)

**Architecture OCR à 3 niveaux :**
```
Document soumis
      │
      ▼
[PaddleOCR] ─── confiance > 65% ──→ Résultat OK
      │
      └── confiance < 65% ──→ [Tesseract (fallback)]
                                      │
                                      └── confiance < 65% ──→ [AlphaAgent IA]
```

---

#### 3.2.4 Traitement d'images : OpenCV + Apache PDFBox

**OpenCV :** Prétraitement des images avant OCR (débruitage, redressement, contraste).
**PDFBox :** Extraction du texte natif des PDF textuels (sans passer par OCR).

**Pourquoi :**
> Un PDF textuel contient déjà le texte encodé. Le passer par OCR serait une perte de précision. PDFBox extrait directement le texte avec 100% de fidélité. OpenCV est utilisé uniquement pour les images scannées.

---

#### 3.2.5 Frontend : Next.js 15 + TypeScript + shadcn/ui

**Pourquoi Next.js :**
- Server-side rendering pour les pages protégées
- Routes API pour les appels backend sécurisés
- TypeScript : typage fort, moins d'erreurs runtime

**Pourquoi shadcn/ui :**
- Composants React prêts à l'emploi, accessibles, personnalisables
- Tailwind CSS pour un rendu propre sans effort

---

#### 3.2.6 Infrastructure : Docker + GitHub Actions

**Pourquoi Docker :**
- Environnement reproductible : "ça marche en local = ça marche en prod"
- Isolation des services (backend, MariaDB)
- Déploiement simplifié

**Pourquoi GitHub Actions :**
- Intégré au dépôt Git (pas de serveur CI séparé)
- Déclenchement automatique sur push
- Gratuit pour les dépôts privés (dans les limites)

---

### 3.3 Architecture Multi-Tenant — pages 49 à 52

**Ce qu'on écrit :**
Comment la plateforme gère plusieurs entreprises clientes simultanément.

**Pourquoi c'est là :**
C'est une contrainte architecturale majeure. Un cabinet comptable gère des dizaines d'entreprises, leurs données ne doivent jamais se mélanger.

**Ce qu'on inclut :**

**Concept Dossier :**
> Un `Dossier` représente une entreprise cliente. Chaque document appartient à un dossier. Un utilisateur CLIENT n'a accès qu'à son propre dossier. Un COMPTABLE a accès aux dossiers qui lui sont assignés. L'ADMIN a accès à tous les dossiers.

**Champs du Dossier :**

| Champ | Type | Description |
|---|---|---|
| id | Long | Identifiant unique |
| name | String | Nom de l'entreprise |
| client | UserAccount | L'entreprise propriétaire |
| comptable | UserAccount | Le comptable assigné |
| exerciseStartDate | LocalDate | Début de la période comptable |
| exerciseEndDate | LocalDate | Fin de la période comptable |
| defaultPurchaseJournal | String | Journal achat par défaut |
| defaultSalesJournal | String | Journal vente par défaut |

**Règle de sécurité appliquée :**
```java
// Chaque endpoint vérifie l'appartenance au dossier
if (!canAccessInvoiceInDossier(sessionUser, invoice, dossierId)) {
    return ResponseEntity.status(403).body("forbidden");
}
```

**Conclusion du chapitre 3 :**
(2-3 phrases résumant les choix techniques et annonçant la réalisation)

---

## CHAPITRE 4 : Réalisation — pages 53 à 70

**Objectif du chapitre :** Montrer concrètement ce qui a été implémenté, module par module, avec des captures d'écran et des extraits de code significatifs.

> **Règle rédactionnelle :** Ne pas mettre tout le code. Mettre uniquement les extraits qui illustrent un concept important. Préférer les screenshots et les explications.

---

### 4.1 Module Authentification et Gestion des Utilisateurs — pages 53 à 56

**Ce qu'on écrit :**
Comment les utilisateurs se connectent et comment leurs accès sont contrôlés.

**Ce qu'on inclut :**
- Screenshot : page de login
- Screenshot : liste des utilisateurs (vue ADMIN)
- Explication du mécanisme de session (Cookie HTTP + HttpSession Spring)
- Tableau des rôles et permissions
- Extrait de code : `@RequireRole` custom annotation

**Point technique à souligner :**
> L'authentification utilise les sessions HTTP plutôt que JWT. Ce choix est adapté à une application interne d'entreprise où la persistance de session côté serveur est préférable à un token stateless.

---

### 4.2 Module Factures d'Achat — pages 56 à 60

**Ce qu'on écrit :**
Le flux complet d'une facture d'achat : upload → OCR → extraction → correction → validation → comptabilisation.

**Ce qu'on inclut :**
- Screenshot : page d'upload
- Screenshot : résultat OCR avec champs extraits
- Screenshot : formulaire de correction des champs
- Screenshot : liste des factures avec statuts colorés
- Diagramme du workflow des statuts :

```
PENDING → PROCESSING → TREATED → READY_TO_VALIDATE → VALIDATED → ACCOUNTED
   ↓           ↓                        ↑
 ERROR     DUPLICATE              OUT_OF_PERIOD
```

- Explication : détection des doublons (comparaison ICE + numéro facture)
- Explication : validation des montants (HT + TVA ≈ TTC, tolérance 0,05 MAD)
- Feature admin : traitement direct sans validation client

**Extrait de code illustratif :**
```java
// Quand ADMIN uploade → clientValidated auto
if (sessionUser.isAdmin()) {
    processed.setClientValidated(true);
    processed.setClientValidatedAt(LocalDateTime.now());
    processed.setClientValidatedBy(sessionUser.username());
}
```

---

### 4.3 Module Factures de Vente — pages 60 à 62

**Ce qu'on écrit :**
Comment les factures émises par l'entreprise sont traitées différemment des factures d'achat.

**Ce qu'on inclut :**
- Screenshot : page factures de vente
- Explication : distinction achat vs vente (position de l'ICE de l'entreprise dans le document)
- Même workflow de statuts que les achats

**Point technique :**
> La distinction achat/vente repose sur la position de l'ICE de l'entreprise dans le document : s'il apparaît en footer → facture de vente (notre entreprise émet). S'il apparaît en header/body → facture d'achat (nous sommes acheteurs).

---

### 4.4 Module Relevés Bancaires — pages 62 à 65

**Ce qu'on écrit :**
Traitement des relevés bancaires : extraction des transactions et comptabilisation.

**Ce qu'on inclut :**
- Screenshot : upload relevé + résultat extraction
- Screenshot : liste des transactions extraites
- Explication : traitement asynchrone (le relevé peut être un gros PDF)
- Explication : validation de continuité des soldes
- Statuts BankStatement : PENDING → PROCESSING → TREATED → VALIDATED → COMPTABILISE

**Chiffres à mentionner :**
- Banques supportées : CMI, Barid Bank, AMEX, format générique
- Formats : PDF texte, PDF scanné, Excel

---

### 4.5 Module Centre Monétique — pages 65 à 67

**Ce qu'on écrit :**
Traitement des récapitulatifs de transactions par carte bancaire.

**Ce qu'on inclut :**
- Screenshot : interface centre monétique
- Explication : rapprochement avec les relevés bancaires (module liaison)
- Exemple d'une transaction extraite

**Valeur ajoutée :**
> Le rapprochement automatique entre le centre monétique et le relevé bancaire permet au comptable de détecter les écarts sans calcul manuel.

---

### 4.6 Module Administration — pages 67 à 70

**Ce qu'on écrit :**
Les fonctionnalités d'administration : gestion des dossiers, utilisateurs, paramètres.

**Ce qu'on inclut :**
- Screenshot : tableau de bord admin
- Screenshot : gestion des utilisateurs (créer, désactiver, changer rôle)
- Screenshot : paramètres du dossier (période d'exercice, journaux)
- Explication : paramètres de suppression de documents (allowValidatedDocumentDeletion, allowAccountedDocumentDeletion)

**Conclusion du chapitre 4 :**
(2-3 phrases résumant ce qui a été réalisé et renvoyant vers la validation/tests)

---

## CHAPITRE 5 : DevOps, Tests et Déploiement — pages 71 à 82

**Objectif du chapitre :** Montrer que le projet est industrialisable — tests, intégration continue, monitoring, conteneurisation.

---

### 5.1 Pipeline CI/CD — pages 71 à 75

**Ce qu'on écrit :**
La chaîne d'intégration et de déploiement continus mise en place avec GitHub Actions.

**Pourquoi c'est là :**
C'est une exigence moderne du développement logiciel. Ça montre que le projet n'est pas juste "ça marche sur mon PC" mais qu'il est déployable de façon fiable et répétable.

**Ce qu'on inclut :**
- Screenshot : GitHub Actions — liste des runs (pipeline #11 vert en 2m38s)
- Screenshot : détail d'un run (job Tests + job Build)
- Schéma du pipeline :

```
git push (branche najwa ou main)
          │
          ▼
┌─────────────────────┐
│  Job 1: Tests       │  53 secondes
│  ─────────────────  │
│  • Démarrage MariaDB│
│  • mvn test         │
│  • Rapport JaCoCo   │
└────────────┬────────┘
             │ (si succès)
             ▼
┌─────────────────────┐
│  Job 2: Build       │  1 minute 27 secondes
│  ─────────────────  │
│  • mvn package      │
│  • docker build     │
│  • docker images    │
└─────────────────────┘
```

- Extrait du fichier `ci-cd.yml` (les parties les plus importantes)
- Explication : déclenchement sur push `najwa` ET `main`, PR vers `main`

---

### 5.2 Conteneurisation Docker — pages 75 à 78

**Ce qu'on écrit :**
Comment l'application est conteneurisée pour un déploiement reproductible.

**Ce qu'on inclut :**
- Structure du Dockerfile (multi-stage build si applicable)
- Variables d'environnement configurables (DATABASE_URL, profiles Spring)
- Profils Spring : `local` vs `docker`
- Avantage : même image en dev et en prod

**Tableau des variables d'environnement :**

| Variable | Description | Valeur exemple |
|---|---|---|
| DATABASE_URL | URL de connexion MariaDB | jdbc:mariadb://localhost:3306/invoice_db |
| DATABASE_USERNAME | Utilisateur DB | root |
| DATABASE_PASSWORD | Mot de passe DB | ******* |
| SPRING_PROFILES_ACTIVE | Profil Spring | docker |

---

### 5.3 Monitoring et Observabilité — pages 78 à 80

**Ce qu'on écrit :**
Comment l'état de l'application est surveillé en production.

**Ce qu'on inclut :**
- Spring Boot Actuator : endpoints de santé (`/actuator/health`, `/actuator/metrics`)
- Prometheus : collecte de métriques JVM + HTTP
- Métriques exposées : temps de réponse, mémoire utilisée, nombre de requêtes

---

### 5.4 Tests — pages 80 à 82

**Ce qu'on écrit :**
La stratégie de test et les résultats obtenus.

**Ce qu'on inclut :**

**Statistiques :**

| Métrique | Valeur |
|---|---|
| Fichiers de tests | 19 |
| Tests unitaires | ~50+ |
| Rapport de couverture | JaCoCo (artefact GitHub Actions) |
| Base de test | MariaDB réelle (pas de mock) |

**Correction des tests :**
> Lors de la mise en place du CI/CD, 3 tests échouaient :
> 1. `TextCleaningService` non injecté dans les tests → résolu par ajout du mock
> 2. MariaDB non disponible en CI → résolu par service container dans le workflow
> 3. `maven.test.failure.ignore` supprimé → les tests sont maintenant obligatoirement verts

**Screenshot : rapport JaCoCo (couverture de code)**

**Conclusion du chapitre 5 :**
(2-3 phrases résumant la qualité du déploiement et ouvrant sur la conclusion générale)

---

## CONCLUSION GÉNÉRALE ET PERSPECTIVES — pages 83 à 85

**Ce qu'on écrit :**
Bilan du projet et ouverture vers les évolutions futures.

**Structure :**

**Bilan :**
> La plateforme comptable développée répond aux objectifs fixés : automatisation de l'extraction OCR, workflow complet PENDING → ACCOUNTED, gestion multi-tenant, sécurité RBAC et déploiement CI/CD opérationnel.

**Chiffres finaux :**

| Indicateur | Valeur réalisée |
|---|---|
| Contrôleurs REST | 25 |
| Services métier | 52 |
| Modules fonctionnels | 4 (Achat, Vente, Bancaire, Monétique) |
| Moteurs OCR | 3 (PaddleOCR, Tesseract, AlphaAgent) |
| Statuts workflow | 10 |
| Pipeline CI/CD | Opérationnel (2m38s) |

**Difficultés rencontrées :**
- Variabilité des formats de factures marocaines → résolu par système de templates adaptatifs
- Qualité variable des documents scannés → résolu par pipeline OCR multi-niveaux
- Tests unitaires cassés lors de la mise en place CI → résolu par injection correcte des mocks

**Perspectives d'évolution :**
1. Ajout des rôles SUPERVISEUR et LECTEUR
2. Interface de reporting avancé (tableaux de bord financiers)
3. Export vers logiciels comptables (SAGE, Ciel)
4. Déploiement Kubernetes pour la scalabilité
5. Amélioration du machine learning (apprentissage des corrections utilisateur)

---

## BIBLIOGRAPHIE / WEBOGRAPHIE — page 86

**Format recommandé :**
- Documentation Spring Boot officielle : https://docs.spring.io
- PaddleOCR : https://github.com/PaddlePaddle/PaddleOCR
- Next.js Documentation : https://nextjs.org/docs
- Docker Documentation : https://docs.docker.com
- GitHub Actions : https://docs.github.com/actions
- Plan comptable marocain (référence)
- Articles sur l'OCR et le traitement documentaire

---

## ANNEXES — pages 87+

**Annexe A : Principaux endpoints API REST**

| Méthode | URL | Description |
|---|---|---|
| POST | /api/auth/login | Authentification |
| POST | /api/dynamic-invoices/upload | Upload facture achat |
| POST | /api/sales/invoices/upload | Upload facture vente |
| POST | /api/v2/bank-statements/upload | Upload relevé bancaire |
| POST | /api/v2/centre-monetique/upload | Upload centre monétique |
| POST | /api/dynamic-invoices/{id}/validate | Valider une facture |
| GET | /api/dynamic-invoices/stats | Statistiques des factures |

**Annexe B : Fichier ci-cd.yml complet**

**Annexe C : Captures d'écran supplémentaires**

**Annexe D : Extrait du schéma de base de données**

---

## RÉCAPITULATIF DU PLAN

| # | Chapitre | Pages | Contenu principal |
|---|---|---|---|
| — | Pages préliminaires | i à xii | Résumé, abréviations, tables |
| — | Introduction générale | 1-3 | Contexte, problématique, objectifs |
| 1 | Contexte général | 4-20 | Organisme, projet, méthodologie, Gantt |
| 2 | Analyse et conception | 21-38 | Besoins, UML, use cases, séquences |
| 3 | Architecture technique | 39-52 | Technologies justifiées, architecture |
| 4 | Réalisation | 53-70 | Modules avec screenshots et code |
| 5 | DevOps, Tests, Déploiement | 71-82 | CI/CD, Docker, monitoring, tests |
| — | Conclusion | 83-85 | Bilan et perspectives |
| — | Bibliographie | 86 | Références |
| — | Annexes | 87+ | API, config, screenshots |
| **Total** | | **~90 pages** | |

---

*Document créé pour Najwa Ourbat — PFE ENSA Marrakech 2024-2025*
