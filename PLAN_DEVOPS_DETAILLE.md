# Plan DevOps détaillé pour le projet `invoice-reader`

## 1. Objectif de la partie DevOps

L’objectif n’est pas seulement de “mettre Docker” autour du projet.  
Le vrai but est de transformer l’application en une solution :
- **reproductible** ;
- **déployable automatiquement** ;
- **scalable** ;
- **observable** ;
- **maintenable** ;
- **plus sûre à exploiter**.

Dans ce projet, la valeur DevOps est forte parce que l’application contient :
- un backend Spring Boot ;
- une base MariaDB ;
- un stockage de fichiers ;
- des dépendances OCR natives ;
- des migrations SQL ;
- plusieurs modules métiers ;
- des tests déjà existants.

Autrement dit, le projet est déjà assez riche pour justifier une vraie chaîne DevOps.

---

## 2. Pourquoi ajouter DevOps à ce projet

### 2.1 Problèmes qu’on veut résoudre
- Déploiement manuel fragile.
- Différences entre machine de développement et machine de production.
- Démarrage incomplet de certains services.
- Configuration éparpillée dans des fichiers et variables locales.
- Risque d’oubli lors des versions.
- Difficulté à refaire un environnement propre.
- Maintenance plus difficile quand plusieurs services interagissent.

### 2.2 Ce que DevOps apporte
- Un pipeline qui vérifie le code avant déploiement.
- Une image Docker stable et réutilisable.
- Une orchestration qui redémarre les services automatiquement.
- Une séparation claire des environnements.
- Une meilleure exploitation en production.
- Une traçabilité des versions.

---

## 3. Analyse du contexte actuel du projet

### 3.1 Ce qui existe déjà
- Application Spring Boot.
- `application.properties`.
- Profil Docker.
- Dockerfile.
- Migrations SQL.
- Tests unitaires et d’intégration.
- Santé applicative via Actuator.

### 3.2 Ce qui manque pour une vraie industrialisation
- Un pipeline CI/CD complet.
- Une stratégie de build et de tag des images.
- Un déploiement automatisé vers un orchestrateur.
- Un vrai système de gestion des secrets.
- Une séparation claire des environnements.
- Une supervision standardisée.

---

## 4. Choix des outils: pourquoi ceux-là

## 4.1 Docker
### Pourquoi Docker
- Standard de conteneurisation.
- Très répandu.
- Facile à intégrer avec Spring Boot.
- Permet d’emballer le backend avec ses dépendances.
- Compatible avec Jenkins et Kubernetes.

### Pourquoi Docker et pas une installation “bare metal”
- Plus reproductible.
- Moins d’erreurs de configuration.
- Plus simple à versionner.
- Plus simple à déployer ailleurs.

### Valeur ajoutée
- Environnement stable.
- Lancement rapide.
- Isolation du service.

---

## 4.2 Jenkins
### Pourquoi Jenkins
- Très bon pour un pipeline CI/CD académique ou professionnel.
- Très flexible.
- Supporte bien les étapes de build Maven, tests, packaging, push d’images et déploiement.
- Facilite la démonstration d’une chaîne DevOps complète.

### Pourquoi Jenkins plutôt que seulement GitHub Actions
- Jenkins est excellent pour montrer une logique d’entreprise plus classique.
- Il permet de mieux illustrer les étapes internes du pipeline.
- Il est souvent préféré dans des environnements où l’on veut un serveur CI dédié.

### Quand GitHub Actions peut être meilleur
- Si le code est déjà totalement centralisé sur GitHub.
- Si l’on veut une configuration plus légère.
- Si l’on cherche moins d’infrastructure à maintenir.

### Recommandation pour ce projet
- **Jenkins** comme choix principal de rapport DevOps, car il met mieux en valeur l’industrialisation complète.
- **GitHub Actions** peut être cité comme alternative moderne.

### Valeur ajoutée de Jenkins
- Pipeline très lisible.
- Contrôle fin des étapes.
- Facile à présenter dans un rapport de stage/PFE.

---

## 4.3 Kubernetes
### Pourquoi Kubernetes
- Orchestration standard du marché.
- Gestion des redémarrages automatiques.
- Scalabilité.
- Déploiement déclaré dans des fichiers YAML.
- Meilleure base qu’un simple `docker compose` pour une architecture cible sérieuse.

### Pourquoi Kubernetes plutôt que Docker Compose
- Docker Compose est très bon pour le local et les petits environnements.
- Kubernetes est meilleur pour une architecture qui doit survivre à des arrêts, des mises à jour et des variations de charge.

### Choix recommandé
- **Docker Compose** pour le développement local uniquement.
- **Kubernetes** pour le déploiement cible DevOps.

### Valeur ajoutée de Kubernetes
- Déploiement plus robuste.
- Rolling update.
- Self-healing.
- Scalabilité horizontale.
- Gestion plus propre des configs et secrets.

---

## 4.4 Registry d’images
### Choix possible
- Docker Hub.
- GitHub Container Registry.
- Registry privée d’entreprise.

### Recommandation
- **GitHub Container Registry** si le projet est hébergé sur GitHub.

### Pourquoi
- Intégration naturelle avec le code.
- Gestion simple des tags.
- Bon pour un projet de PFE.

### Valeur ajoutée
- Images versionnées.
- Déploiement plus fiable.
- Traçabilité de chaque build.

---

## 4.5 Observabilité
### Outils recommandés
- **Prometheus** pour la collecte des métriques.
- **Grafana** pour les tableaux de bord.
- Logs applicatifs structurés.
- Spring Actuator pour la santé.

### Pourquoi ces outils
- Prometheus est la référence pour la supervision orientée métriques.
- Grafana transforme les métriques en visualisation claire.
- Actuator expose des endpoints utiles pour health checks et monitoring.

### Valeur ajoutée
- Savoir si le service répond.
- Savoir s’il consomme trop de mémoire.
- Savoir si le pipeline OCR ralentit.
- Suivre la stabilité en production.

---

## 4.6 Sécurité
### Outils et pratiques
- Variables d’environnement.
- Secrets Kubernetes.
- Réseau isolé.
- Validation des images.
- Contrôle d’accès aux registres.

### Pourquoi
- L’application manipule des documents métier sensibles.
- Il faut éviter de stocker les mots de passe ou clés en clair.

### Valeur ajoutée
- Moins de fuite accidentelle.
- Meilleure discipline de production.

---

## 5. Le meilleur choix entre plusieurs options

## 5.1 Pour la CI/CD
### Option A : Jenkins
- Très complet.
- Très pédagogique.
- Très bon pour montrer un pipeline complet.

### Option B : GitHub Actions
- Plus simple à mettre en place.
- Très pratique pour projets hébergés sur GitHub.
- Moins d’infrastructure à maintenir.

### Choix conseillé
- **Jenkins** si tu veux un chapitre DevOps solide et démonstratif.
- **GitHub Actions** si ton encadrant préfère un système plus léger.

### Mon avis pour ton cas
- Le meilleur choix pour un rapport PFE orienté DevOps est **Jenkins + Kubernetes + Docker**.
- GitHub Actions peut rester en comparaison dans le texte, mais pas comme outil principal si tu veux un récit industriel fort.

---

## 5.2 Pour l’orchestration
### Option A : Docker Compose
- Simple.
- Rapide.
- Idéal local.

### Option B : Kubernetes
- Plus professionnel.
- Plus robuste.
- Plus évolutif.

### Choix conseillé
- **Kubernetes** pour la cible.
- **Docker Compose** seulement comme environnement de développement ou de secours.

---

## 5.3 Pour le monitoring
### Option A : uniquement Actuator
- Suffisant pour de petits tests.
- Limité pour la supervision réelle.

### Option B : Prometheus + Grafana + Actuator
- Vision complète.
- Très claire pour un rapport PFE.

### Choix conseillé
- **Prometheus + Grafana + Actuator**.

---

## 6. Ordre logique de réalisation

### Étape 1: stabiliser l’application
Avant DevOps, il faut s’assurer que :
- les tests passent ;
- les modules principaux fonctionnent ;
- les configurations sont propres ;
- les profils local/docker sont cohérents.

### Étape 2: nettoyer la configuration
- Séparer les paramètres sensibles.
- Vérifier que les chemins locaux ne sont pas codés en dur.
- Préparer les variables d’environnement.

### Étape 3: améliorer le Dockerfile
- Construire une image légère.
- Exposer le bon port.
- Préparer l’upload directory.
- Ajouter une commande de démarrage claire.

### Étape 4: ajouter la stratégie CI
- Compilation Maven.
- Exécution des tests.
- Rapport de couverture.
- Construction de l’image.
- Publication dans un registry.

### Étape 5: passer au déploiement automatisé
- Déployer l’image sur un environnement cible.
- Vérifier l’état des pods ou services.
- Contrôler les logs.

### Étape 6: mettre Kubernetes
- Deployment.
- Service.
- Ingress.
- ConfigMap.
- Secret.
- PVC.
- Probes.

### Étape 7: ajouter la supervision
- Actuator.
- Prometheus.
- Grafana.
- Alertes de base.

### Étape 8: documenter l’exploitation
- Procédure de déploiement.
- Procédure de rollback.
- Procédure de sauvegarde.
- Procédure de mise à jour.

---

## 7. Plan détaillé DevOps étape par étape

## 7.1 Préparation du projet
- Vérifier la stabilité du code.
- Nettoyer les fichiers de configuration.
- S’assurer que Maven, les tests et le build sont reproductibles.

## 7.2 Conteneurisation
- Construire une image backend.
- Définir les variables de configuration.
- Monter les volumes nécessaires.
- Valider le démarrage en local.

## 7.3 Pipeline Jenkins
- Récupérer le code.
- Lancer `mvn test`.
- Générer la couverture.
- Construire l’image Docker.
- Tagger l’image avec la version du build.
- Pousser l’image dans le registry.
- Déclencher le déploiement.

## 7.4 Déploiement Kubernetes
- Créer le namespace.
- Déployer la base de données si elle est externalisée ou dédiée.
- Déployer le backend.
- Définir les services.
- Ajouter les probes.
- Ajouter les secrets.
- Ajouter les volumes persistants.

## 7.5 Supervision
- Vérifier `/actuator/health`.
- Exposer les métriques utiles.
- Créer un dashboard Grafana.

## 7.6 Sécurisation
- Utiliser des secrets Kubernetes.
- Éviter les mots de passe dans le code.
- Limiter l’accès au registry et au cluster.

## 7.7 Exploitation
- Mettre en place une stratégie de mise à jour.
- Prévoir le rollback.
- Prévoir la sauvegarde des données.

---

## 8. Proposition de pipeline cible

1. Push du code source.
2. Déclenchement du pipeline Jenkins.
3. Compilation du projet.
4. Lancement des tests.
5. Analyse de la couverture.
6. Construction de l’image Docker.
7. Push de l’image dans le registry.
8. Déploiement sur Kubernetes.
9. Vérification santé.
10. Supervision continue.

---

## 9. Pourquoi ce plan est le bon

Ce plan est cohérent parce qu’il suit l’ordre réel des dépendances :
- on stabilise d’abord le code ;
- ensuite on le conteneurise ;
- ensuite on automatise ;
- ensuite on orchestre ;
- enfin on supervise.

On évite ainsi un piège fréquent : vouloir faire du Kubernetes avant d’avoir une application stable et correctement paramétrée.

---

## 10. Ce que tu peux écrire dans le rapport

### Message principal
- Le projet a d’abord résolu un besoin métier.
- Puis il a été préparé pour une industrialisation DevOps.
- Cette industrialisation a pour but de garantir la qualité, la reproductibilité et la maintenabilité.

### Idée à défendre
- Docker assure la portabilité.
- Jenkins automatise la chaîne.
- Kubernetes orchestre et rend le service robuste.
- Prometheus et Grafana rendent la solution observable.

---

## 11. Recommandation finale

Si tu veux une version forte, crédible et très bien défendable devant un encadrant, je te recommande :

- **Docker** pour la conteneurisation.
- **Jenkins** pour la CI/CD.
- **Kubernetes** pour l’orchestration.
- **GitHub Container Registry** pour stocker les images.
- **Prometheus + Grafana** pour la supervision.
- **Spring Actuator** pour les checks applicatifs.

Et pour le discours académique :
- montrer les alternatives ;
- expliquer pourquoi ce choix est le meilleur compromis ;
- insister sur la valeur ajoutée concrète ;
- présenter l’ordre logique de mise en oeuvre.

