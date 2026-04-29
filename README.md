# Invoice Reader OCR Backend

Spring Boot backend that uploads invoices, runs OCR, extracts fields with
dynamic templates, and manages accounting tiers/accounts plus pattern learning.

## Features
- Upload invoice files (PDF/JPG/PNG) and process OCR.
- Dynamic templates for field extraction and auto-detection.
- Field learning workflow to improve patterns over time.
- Accounting tiers and accounts management.
- File storage and download endpoint for original uploads.
- Actuator health endpoint for monitoring.

## Tech Stack
- Java 17, Spring Boot 3.2
- MariaDB
- Tesseract OCR via Tess4J
- PDFBox, OpenCV

## Project Layout
- `src/main/java/.../controller`: REST controllers.
- `src/main/java/.../servises`: business logic (OCR, templates, learning).
- `src/main/resources/application.properties`: config defaults.
- `src/main/resources/data.sql`: seeds field patterns on startup.
- `uploads/`: default upload folder (configurable).

## Requirements
- Java 17
- Maven (or use the Maven wrapper)
- MariaDB
- Tesseract OCR installed locally (Windows default is used in config)

## Configuration
Set env vars or edit `src/main/resources/application.properties`.

Profiles:
- `local` (default): uses MariaDB on `localhost:3306`.
- `docker`: uses MariaDB service `mariadb:3306` inside Docker network.

Database:
- `DATABASE_URL` (default `jdbc:mariadb://localhost:3306/invoice_db?createDatabaseIfNotExist=true`)
- `DATABASE_USERNAME` (default `root`)
- `DATABASE_PASSWORD` (default empty)

Server:
- `BACKEND_PORT` (default `8089`)
- `CORS_ORIGINS` (default `http://localhost:3000,http://localhost:3001`)

OCR and uploads:
- `TESSERACT_PATH` (default `C:\Program Files\Tesseract-OCR\tesseract.exe`)
- `TESSERACT_DATAPATH` (default `C:\Program Files\Tesseract-OCR\tessdata`)
- `TESSERACT_LANGUAGE` (default `eng+fra`)
- `UPLOAD_DIR` (default `C:\evoleos\uploads`)

Logging:
- `SHOW_SQL` (default `true`)

## Local Run
1. Start MariaDB (the app can auto-create `invoice_db` with default config).
2. Ensure `UPLOAD_DIR` exists or update it in `application.properties`.
3. Run the app:
   - Windows: `.\mvnw.cmd spring-boot:run`
   - Linux/macOS: `./mvnw spring-boot:run`
4. Health check: `http://localhost:8089/actuator/health`

By default, Spring uses profile `local`, so datasource points to `localhost:3306`.

## Docker (optional)
`docker-compose.yml` runs backend + MariaDB with Spring profile `docker`.

## API Overview
Base URLs:
- `GET /actuator/health` - health check.

Dynamic invoices:
- `POST /api/dynamic-invoices/upload` - upload and process file.
- `POST /api/dynamic-invoices/{id}/process` - reprocess.
- `GET /api/dynamic-invoices/{id}` - details.
- `GET /api/dynamic-invoices` - list (filters: `status`, `templateId`).
- `PUT /api/dynamic-invoices/{id}/fields` - update fields.
- `POST /api/dynamic-invoices/{id}/validate` - validate.
- `GET /api/dynamic-invoices/{id}/available-signatures` - signature hints.
- `GET /api/dynamic-invoices/stats` - stats.
- `GET /api/dynamic-invoices/files/{filename}` - download file.
- `POST /api/dynamic-invoices/{id}/link-tier` - link a tier.

Dynamic templates:
- `POST /api/dynamic-templates` - create.
- `PUT /api/dynamic-templates/{id}` - update (new version).
- `PATCH /api/dynamic-templates/{id}` - partial update.
- `GET /api/dynamic-templates/{id}` - get.
- `GET /api/dynamic-templates` - list active.
- `GET /api/dynamic-templates/search?name=` - search.
- `GET /api/dynamic-templates/by-supplier-type/{supplierType}` - filter.
- `GET /api/dynamic-templates/reliable` - reliable templates.
- `GET /api/dynamic-templates/by-supplier?supplier=` - supplier name search.
- `DELETE /api/dynamic-templates/{id}` - deactivate.
- `POST /api/dynamic-templates/extract/{invoiceId}` - extract by template.
- `POST /api/dynamic-templates/extract-file` - extract from upload.
- `POST /api/dynamic-templates/{templateId}/test` - test template.
- `POST /api/dynamic-templates/detect` - detect template from OCR text.

Field learning (v2):
- `PUT /api/v2/{invoiceId}/fields-with-patterns` - save and integrate.
- `POST /api/v2/{id}/learn-patterns` - save for learning only.
- `GET /api/v2/learning/pending` - pending patterns.
- `GET /api/v2/learning/invoice/{invoiceId}` - patterns by invoice.
- `GET /api/v2/learning/supplier/{ice}` - patterns by supplier.
- `PUT /api/v2/learning/{id}/approve` - approve.
- `PUT /api/v2/learning/{id}/reject` - reject.
- `POST /api/v2/learning/bulk-approve` - bulk approve.
- `POST /api/v2/learning/integrate-all` - integrate all ready patterns.
- `GET /api/v2/learning/stats` - stats.
- `GET /api/v2/learning/analyze/{fieldName}` - analysis.
- `GET /api/v2/learning/suggestions/{fieldName}` - suggestions.
- `POST /api/v2/learning/test-pattern` - test a pattern.
- `GET /api/v2/{invoiceId}/test` - simple test endpoint.

Accounting accounts:
- `POST /api/accounting/accounts` - create.
- `GET /api/accounting/accounts/{id}` - get.
- `GET /api/accounting/accounts/by-code/{code}` - get by code.
- `GET /api/accounting/accounts` - list (filter: `activeOnly`).
- `GET /api/accounting/accounts/search?query=` - search.
- `GET /api/accounting/accounts/by-classe/{classe}` - by classe.
- `GET /api/accounting/accounts/fournisseurs` - fournisseur accounts.
- `GET /api/accounting/accounts/charges` - charge accounts.
- `GET /api/accounting/accounts/tva` - TVA accounts.
- `PUT /api/accounting/accounts/{id}` - update.
- `DELETE /api/accounting/accounts/{id}` - deactivate.
- `PATCH /api/accounting/accounts/{id}/activate` - activate.
- `GET /api/accounting/accounts/stats` - stats.
- `POST /api/accounting/accounts/import` - bulk import.

Accounting tiers:
- `POST /api/accounting/tiers` - create.
- `GET /api/accounting/tiers/{id}` - get.
- `GET /api/accounting/tiers/by-tier-number/{tierNumber}` - get by tier number.
- `GET /api/accounting/tiers/by-ice/{ice}` - get by ICE.
- `GET /api/accounting/tiers/by-if/{ifNumber}` - get by IF.
- `GET /api/accounting/tiers` - list (filter: `activeOnly`).
- `GET /api/accounting/tiers/search?query=` - search.
- `GET /api/accounting/tiers/with-config` - with accounting config.
- `GET /api/accounting/tiers/without-config` - without config.
- `GET /api/accounting/tiers/with-ice` - with ICE.
- `GET /api/accounting/tiers/with-if` - with IF.
- `GET /api/accounting/tiers/without-identifier` - without fiscal id.
- `GET /api/accounting/tiers/auxiliaire` - auxiliaire mode.
- `GET /api/accounting/tiers/normal` - normal mode.
- `PUT /api/accounting/tiers/{id}` - update.
- `DELETE /api/accounting/tiers/{id}` - deactivate.
- `PATCH /api/accounting/tiers/{id}/activate` - activate.
- `GET /api/accounting/tiers/stats` - stats.

Field patterns:
- `GET /api/field-patterns`
- `POST /api/field-patterns`
- `PUT /api/field-patterns/{id}`
- `DELETE /api/field-patterns/{id}`

## Quick Test
Use the included Python script:
```bash
python test_upload.py
```
It uploads `src/archive/aaa.pdf` to `http://localhost:8089/api/dynamic-invoices/upload`.

## Regex Enrichment (New)
This project now supports optional regex learning from corrected invoice fields.

What changed:
- Added `RegexEnrichmentService` at `src/main/java/com/invoice_reader/invoice_reader/servises/patterns/RegexEnrichmentService.java`.
- Integrated into `PUT /api/v2/{invoiceId}/fields-with-patterns` through `PatternIntegrationService`.
- Improved `RegexGenerator` to build safer token-based regex with confidence scoring.
- Added repository methods to fetch learning history and upsert learned regex patterns.
- Added config flags in `src/main/resources/application.properties`.

How it works:
1. User corrects missing fields and submits `fieldsData` + `newPatterns`.
2. Existing flow still saves fields and learning entries (`FieldLearningData`).
3. If `regex.enrichment.enabled=true`, system gathers approved history samples for the same field/supplier.
4. `RegexGenerator` creates a regex and confidence score.
5. If confidence is high enough, regex is saved in `field_patterns` (scoped in `description`).
6. If invoice has a template, learned regex is applied only when the template field has no regex yet.

Safety behavior:
- Disabled by default (`regex.enrichment.enabled=false`).
- Does not replace existing extraction logic.
- Does not overwrite existing template regex.
- Skips weak or invalid regex.

Configuration:
- `regex.enrichment.enabled` (default `false`)
- `regex.enrichment.min-samples` (default `3`)
- `regex.enrichment.min-confidence` (default `0.75`)
- `regex.enrichment.max-history-samples` (default `20`)

Recommended rollout:
1. Enable in one environment: `REGEX_ENRICHMENT_ENABLED=true`.
2. Keep `min-samples=3` and `min-confidence=0.75` initially.
3. Monitor `field_patterns` and extraction quality for 1-2 supplier templates.
4. Increase confidence threshold if you observe false positives.
