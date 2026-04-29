# Table des matières détaillée du rapport PFE

## Objectif du document
Ce document sert de base de travail pour rédiger un rapport de stage/PFE clair, complet et professionnel sur le projet `invoice-reader`.
Il combine :
- une **structure académique classique** ;
- une **lecture fonctionnelle** du besoin métier ;
- une **explication théorique et pratique** des modules réellement présents dans le projet ;
- une **orientation future DevOps** à intégrer comme chapitre dédié.

L’objectif est que l’encadrant comprenne rapidement :
- le contexte du projet ;
- le problème métier ;
- les choix techniques ;
- l’architecture logicielle ;
- la valeur ajoutée apportée ;
- la future évolution vers une chaîne DevOps industrialisée.

---

## Pages préliminaires

### Page de garde
- Titre du rapport.
- Nom de l’étudiant.
- Encadrant académique.
- Organisme d’accueil.
- Année universitaire.

### Remerciements
- Remerciements à l’encadrant.
- Remerciements à l’entreprise d’accueil.
- Remerciements à l’équipe technique et administrative.

### Résumé en français
- Contexte général du projet.
- Problématique traitée.
- Approche adoptée.
- Résultats et apport principal.

### Abstract en anglais
- Version anglaise du résumé.
- Même logique de synthèse.

### الملخص بالعربية
- Résumé en arabe pour conformité académique si demandé.

### Liste des abréviations
- API.
- OCR.
- PFE.
- CI/CD.
- DevOps.
- JDBC.
- JPA.
- CRUD.
- JSON.
- JWT, si mentionné plus tard.
- UAT, si utilisé dans la validation.

### Liste des figures
- Diagrammes d’architecture.
- Schémas de flux OCR.
- Capture des écrans fonctionnels.
- Pipeline Jenkins.
- Schéma Kubernetes.

### Liste des tableaux
- Tableau des technologies.
- Tableau des rôles.
- Tableau des modules.
- Tableau des cas de test.

### Table des matières
- Sommaire automatique du rapport.

---

## Introduction générale

### 1. Présentation générale du sujet
- Expliquer le besoin de digitalisation du traitement documentaire.
- Présenter le contexte comptable et l’importance de l’automatisation.
- Montrer pourquoi la lecture manuelle des factures et relevés est coûteuse et source d’erreurs.

### 2. Problématique
- Comment extraire automatiquement les informations d’une facture ou d’un relevé avec un niveau de fiabilité suffisant ?
- Comment distinguer les documents d’achat, de vente, bancaires et centre monétique ?
- Comment rendre le système maintenable, industrialisable et déployable de façon reproductible ?

### 3. Objectifs du projet
- Automatiser l’extraction OCR.
- Réduire la saisie manuelle.
- Structurer les données comptables.
- Fiabiliser la validation.
- Préparer l’intégration DevOps.

### 4. Méthodologie générale
- Analyse du besoin.
- Étude de l’existant.
- Conception.
- Implémentation.
- Tests.
- Validation.
- Déploiement et évolution.

### 5. Structure du rapport
- Présentation courte des chapitres à venir.

---

## Chapitre 1 - Contexte général du projet

### 1.1 Présentation de l’organisme d’accueil
- Activité de l’entreprise.
- Organisation générale.
- Position du projet dans les besoins réels de l’organisme.

### 1.2 Contexte métier du projet
- Gestion des dossiers d’entreprises.
- Traitement comptable des factures.
- Extraction de données depuis des documents semi-structurés.
- Traitement des relevés bancaires.
- Exploitation du centre monétique.

### 1.3 Problématique métier
- Documents hétérogènes.
- Formats multiples.
- OCR bruité.
- Données manquantes.
- Besoin de validation humaine.
- Besoin de rapprochement comptable.

### 1.4 Objectifs fonctionnels
- Upload de fichiers.
- Lecture OCR.
- Extraction de champs.
- Détection de signature.
- Gestion des tiers.
- Gestion des comptes.
- Workflow de validation.
- Gestion des relevés bancaires.
- Gestion du centre monétique.

### 1.5 Objectifs techniques
- Architecture modulaire.
- API REST.
- Persistance MariaDB.
- OCR Tesseract/OpenCV.
- Traitement batch.
- Validation métier.
- Préparation DevOps.

### 1.6 Organisation du projet
- Répartition du temps.
- Séquencement des tâches.
- Gestion des versions.
- Gestion des tests.

---

## Chapitre 2 - État de l’art et cadre conceptuel

### 2.1 Traitement automatique des documents
- Définition de l’OCR.
- Principes de l’extraction d’informations.
- Différence entre document textuel et document scanné.
- Défis des documents administratifs marocains.

### 2.2 Lecture intelligente des factures
- Reconnaissance des zones documentaires.
- Détection des montants.
- Extraction des identifiants fiscaux.
- Gestion des erreurs OCR.
- Règles de validation comptable.

### 2.3 Gestion comptable de base
- Tiers.
- Comptes.
- Journaux.
- Périodes d’exercice.
- Validation des documents avant comptabilisation.

### 2.4 Approches d’apprentissage de patterns
- Règles regex.
- Apprentissage incrémental.
- Correction utilisateur.
- Capitalisation des corrections.

### 2.5 État de l’art DevOps
- Conteneurisation.
- Intégration continue.
- Déploiement automatisé.
- Orchestration.
- Observabilité.

---

## Chapitre 3 - Analyse de l’existant et architecture globale

### 3.1 Présentation de l’application
- Backend Spring Boot.
- Base MariaDB.
- Stockage des documents.
- Modules fonctionnels présents.

### 3.2 Architecture générale
- Couches contrôleur/service/repository/entity.
- Séparation par domaine fonctionnel.
- Réutilisation des composants OCR et métier.

### 3.3 Gestion des utilisateurs et des rôles
- ADMIN.
- COMPTABLE.
- CLIENT.
- Authentification par session.
- Sécurisation des routes API.

### 3.4 Gestion des dossiers
- Un dossier = une entreprise.
- Dossier actif.
- Restriction d’accès par rôle.
- Période d’exercice.

### 3.5 Flux général des documents
- Upload du document.
- Stockage fichier.
- OCR.
- Extraction.
- Validation.
- Affectation au tier.
- Sauvegarde en base.

### 3.6 Contraintes techniques
- OCR bruité.
- Fichiers multiples.
- Cas de factures achat/vente.
- Cas des relevés bancaires.
- Cas du centre monétique.

---

## Chapitre 4 - Conception fonctionnelle

### 4.1 Cas d’utilisation
- Authentification.
- Création d’un dossier.
- Upload facture achat.
- Upload facture vente.
- Correction et validation des champs.
- Gestion des comptes et tiers.
- Upload de relevés bancaires.
- Rapprochement centre monétique.

### 4.2 Règles métier principales
- Règle du dossier.
- Règle achat.
- Règle vente.
- Règle des montants.
- Règle d’exercice.
- Règle de doublon.

### 4.3 Modèle conceptuel du domaine
- Utilisateur.
- Dossier.
- Facture.
- Template.
- Tier.
- Account.
- BankStatement.
- CentreMonetiqueBatch.

### 4.4 Diagrammes recommandés
- Diagramme de cas d’utilisation.
- Diagramme de classes.
- Diagramme de séquence du traitement OCR.
- Diagramme de séquence du workflow bancaire.
- Diagramme de séquence du centre monétique.

---

## Chapitre 5 - Conception technique et architecture logicielle

### 5.1 Structure du projet
- `controller`
- `servises`
- `repository`
- `entity`
- `dto`
- `config`
- `security`

### 5.2 Modules principaux
- Authentification.
- Dossiers.
- Factures d’achat.
- Factures de vente.
- Tiers et comptes.
- Relevés bancaires.
- Centre monétique.
- Rapprochement.

### 5.3 Choix technologiques
- Spring Boot pour la rapidité de développement.
- JPA/Hibernate pour la persistance.
- MariaDB pour la compatibilité et la simplicité.
- Tesseract pour l’OCR local.
- OpenCV pour le prétraitement image.
- PDFBox pour les PDF.
- Apache POI pour les fichiers Excel.

### 5.4 Gestion des configurations
- `application.properties`
- `application-docker.properties`
- Variables d’environnement.
- Modes local et Docker.

### 5.5 Gestion des migrations et seeds
- Migrations SQL.
- Création d’un admin par défaut.
- Insertion des comptes initiaux.
- Ajout de colonnes OCR.

---

## Chapitre 6 - Implémentation du moteur OCR

### 6.1 Chaîne de traitement des documents
- Stockage du fichier.
- Détection du format.
- OCR ou extraction directe.
- Nettoyage du texte.
- Prétraitement image.

### 6.2 OCR facture achat
- Détection de la signature dans le footer.
- Extraction des champs par template.
- Fallback par regex.
- Validation de cohérence.

### 6.3 OCR facture vente
- Détection du premier ICE.
- Interprétation comme ICE client.
- Liaison du tier par ICE.
- Validation par dossier.

### 6.4 Validation et enrichissement
- Montant HT.
- TVA.
- TTC.
- Conversion en lettres.
- Statut documentaire.

### 6.5 Apprentissage automatique
- Sauvegarde des corrections.
- Enrichissement des patterns.
- Génération de regex apprises.
- Réutilisation sur de futurs documents.

---

## Chapitre 7 - Module bancaire et rapprochement

### 7.1 Objectif du module bancaire
- Lire les relevés bancaires.
- Identifier les mouvements.
- Préparer le rapprochement comptable.

### 7.2 Extraction des relevés
- PDF texte.
- PDF scanné.
- Excel.
- Image.

### 7.3 Détection des structures bancaires
- CMI.
- Barid Bank.
- AMEX.
- Cas générique.

### 7.4 Traitement métier
- Découpage des lignes.
- Calcul des totaux.
- Détection des doublons.
- Validation des périodes.

### 7.5 Workflow de comptabilisation
- Simulation.
- Confirmation.
- Génération des écritures.

---

## Chapitre 8 - Centre monétique et liaison avec les relevés

### 8.1 Pourquoi ce module existe
- Pour traiter les flux cartes/TPE.
- Pour relier les transactions du centre monétique aux relevés.

### 8.2 Extraction du centre monétique
- Détection de la structure.
- Lecture des lots.
- Extraction des lignes.

### 8.3 Rapprochement
- Mise en relation batch/relevé.
- Contrôle du dossier.
- Génération des écarts.

### 8.4 Apport métier
- Meilleure traçabilité.
- Réduction des contrôles manuels.
- Meilleure visibilité sur les paiements par carte.

---

## Chapitre 9 - Sécurité, validation et qualité

### 9.1 Sécurité applicative
- Session utilisateur.
- Contrôle de rôle.
- Restriction par dossier.

### 9.2 Validation métier
- Validation des montants.
- Validation de période d’exercice.
- Détection des doublons.
- Cohérence des identifiants fiscaux.

### 9.3 Qualité logicielle
- Tests unitaires.
- Tests d’intégration.
- Tests OCR.
- Tests du module bancaire.

### 9.4 Limites et risques
- OCR dépendant de la qualité des documents.
- Variabilité des mises en page.
- Nécessité de supervision humaine sur certains cas.

---

## Chapitre 10 - Industrialisation DevOps

### 10.1 Motivation
- Rendre le projet reproductible.
- Fiabiliser les déploiements.
- Standardiser les environnements.

### 10.2 Conteneurisation
- Image backend.
- Variables d’environnement.
- Séparation configuration/code.

### 10.3 Intégration continue
- Compilation.
- Tests.
- Qualité.
- Packaging.

### 10.4 Orchestration
- Déploiement Kubernetes.
- Gestion des services.
- Scalabilité.
- Résilience.

### 10.5 Observabilité
- Health checks.
- Logs.
- Métriques.
- Alertes.

### 10.6 Sécurité et exploitation
- Gestion des secrets.
- Mise à jour.
- Rollback.
- Sauvegarde.

---

## Chapitre 11 - Tests, résultats et perspectives

### 11.1 Scénarios de test
- Facture achat.
- Facture vente.
- Relevé bancaire.
- Centre monétique.
- Cas d’erreur.

### 11.2 Résultats obtenus
- Taux de fiabilité.
- Réduction de la saisie.
- Amélioration du traitement.

### 11.3 Limites observées
- Variété des modèles documentaires.
- Documents dégradés.
- Cas rares non couverts.

### 11.4 Perspectives d’évolution
- Amélioration OCR.
- Renforcement du machine learning.
- Industrialisation complète DevOps.
- Ajout de monitoring avancé.

---

## Conclusion générale
- Rappel du besoin.
- Rappel de la solution.
- Bilan technique.
- Bilan fonctionnel.
- Ouverture vers les évolutions futures.

---

## Annexes
- Extraits de code.
- Schémas d’architecture.
- Captures d’écran.
- Exemples d’API.
- Pipeline Jenkins.
- Manifestes Kubernetes.
- Tableaux comparatifs des outils.

