# Étapes Suivantes — Priorités Rapport + Projet
**Contexte :** Tu es en retard sur le rapport. Voici un plan clair par ordre de priorité.

---

## URGENCE 1 — Commencer le Rapport Maintenant

Le rapport se base sur ce qui existe déjà. Tu n'as **pas besoin que le projet soit 100% terminé** pour écrire.

### Structure recommandée du rapport

```
1. Introduction / Contexte
2. Cahier des charges & Problématique
3. Architecture technique
   3.1 Backend (Java Spring Boot)
   3.2 Frontend (Next.js)
   3.3 Infrastructure (Docker, CI/CD)
4. Réalisation — Module par module
   4.1 Factures Achat
   4.2 Factures Vente
   4.3 Relevés Bancaires
   4.4 Centre Monétique
   4.5 Administration & Authentification
5. Pipeline OCR & Intelligence Artificielle
6. Tests & Qualité
7. DevOps & CI/CD
8. Bilan & Perspectives
9. Conclusion
```

### Parties déjà rédigées ou faciles à rédiger

| Section | Source disponible | Temps estimé |
|---|---|---|
| Contexte & problématique | PDF fourni → copier-adapter | 30 min |
| Architecture technique | Code exploré → schémas UML/diagrammes | 2h |
| Modules fonctionnels | Endpoints REST documentés | 3h |
| CI/CD | Fichier `ci-cd.yml` existant | 1h |
| Tests | 19 fichiers tests + rapport JaCoCo | 1h |
| Bilan | Tableau avancement ici | 1h |

**Total estimé pour une première version :** 8-10 heures de rédaction.

---

## URGENCE 2 — Corrections Critiques Avant Soutenance

Ces points doivent être résolus car ils créent un écart entre le PDF et le code :

### 1. Rôles SUPERVISEUR et LECTEUR (1-2 jours)

```java
// UserRole.java — ajouter :
public enum UserRole {
    ADMIN,
    COMPTABLE,
    CLIENT,
    SUPERVISEUR,  // ← à ajouter
    LECTEUR       // ← à ajouter
}
```

Puis définir leurs permissions sur les endpoints existants.

### 2. Mettre à jour la présentation PDF (2h)

Corriger les écarts identifiés dans `AVANCEMENT_PROJET.md` :
- Controllers : 23 → 25
- Rôles : selon l'état final choisi
- Moteurs OCR : nommer correctement

---

## URGENCE 3 — Éléments Pour le Rapport Technique

### Captures d'écran à prendre (frontend)

- [ ] Page login
- [ ] Dashboard admin
- [ ] Upload d'une facture + résultat OCR
- [ ] Liste des factures avec statuts
- [ ] Formulaire de correction des champs
- [ ] Validation d'une facture
- [ ] Relevés bancaires
- [ ] Centre monétique
- [ ] Gestion des utilisateurs

### Diagrammes à produire

- [ ] Diagramme de cas d'utilisation (Use Case) — par rôle
- [ ] Diagramme de classes simplifié (5-6 entités principales)
- [ ] Diagramme de séquence — workflow upload→OCR→validation
- [ ] Architecture globale (Frontend ↔ Backend ↔ DB ↔ OCR)

### Chiffres clés à mentionner dans le rapport

| Métrique | Valeur |
|---|---|
| Contrôleurs REST | 25 |
| Services métier | 52 |
| Fichiers Java | 249 |
| Statuts workflow | 10 |
| Moteurs OCR | 3 (PaddleOCR, Tesseract, AlphaAgent) |
| Formats supportés | PDF, JPG, PNG |
| Rôles | 3 (objectif 5) |
| Pipeline CI/CD | GitHub Actions — 2m 38s |
| Tests | 19 fichiers |

---

## Planning Suggéré

| Jour | Tâche |
|---|---|
| **Aujourd'hui** | Créer le squelette du rapport (Word/LaTeX), copier le contexte du PDF |
| **J+1** | Rédiger architecture technique + capturer screenshots frontend |
| **J+2** | Rédiger modules fonctionnels (Factures + Relevés) |
| **J+3** | Rédiger OCR pipeline + CI/CD + Tests |
| **J+4** | Ajouter rôles SUPERVISEUR/LECTEUR dans le code |
| **J+5** | Bilan, relecture, mise en page |

---

## Ce Qui Est Déjà Solide Pour la Soutenance

✅ Pipeline CI/CD fonctionnel (GitHub Actions — vert)
✅ 4 modules complets (Achat, Vente, Bancaire, Monétique)
✅ 3 moteurs OCR intégrés
✅ Workflow complet PENDING → ACCOUNTED
✅ Architecture multi-tenant avec isolation des dossiers
✅ RBAC (Role-Based Access Control) sur tous les endpoints
✅ Feature admin : traitement direct sans validation client
✅ Docker + Actuator + Prometheus

---

*Document créé — Mai 2026*
