# Dossier `config/`

Package : `com.invoice_reader.invoice_reader.config`

Ce dossier contient les **configurations techniques transversales** de l'application Spring Boot : CORS, MVC et librairies natives. Il ne contient aucune logique métier.

> Les configurations spécifiques à un module (Tesseract, migrations BDD, async banking) se trouvent dans leurs modules respectifs : `ocr/config/`, `database/migration/`, `banque/config/`.

---

## Fichiers

| Fichier | Rôle |
|---------|------|
| `CorsConfig.java` | `@Configuration` Spring : configure la politique CORS globale de l'application. Autorise les origines, méthodes HTTP et en-têtes acceptés pour les appels cross-origin depuis le frontend Next.js |
| `OpenCvConfig.java` | `@Configuration` Spring : charge la librairie native OpenCV au démarrage (`@PostConstruct`) via `OpenCV.loadLocally()`. Nécessaire pour le prétraitement des images avant OCR (deskew, binarisation, débruitage) |
| `WebConfig.java` | `@Configuration` qui implémente `WebMvcConfigurer` : enregistre le `SessionAuthInterceptor` sur toutes les routes protégées. Point central de la configuration des intercepteurs HTTP |
