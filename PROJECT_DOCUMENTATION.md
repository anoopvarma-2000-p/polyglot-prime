# Polyglot Prime — Comprehensive Project Documentation

## 1) Repository Overview

**polyglot-prime** is a multi-module monorepo centered on healthcare data ingestion, validation, orchestration, and supporting automation.

Primary technology stack observed in the repository:
- Java 21 + Spring Boot 3.3.x (core services)
- Maven multi-module build
- PostgreSQL + jOOQ (database-first workflows, especially in `udi-prime`)
- TypeScript + Playwright (API automation)
- Integration artifacts for FHIR, HL7v2, CCDA, flatfile, and AWS queue ingestion

---

## 2) Top-Level Structure

Key top-level directories:

- `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/hub-prime` — primary hub app (API + UX)
- `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/nexus-ingestion-api` — ingestion API (HTTP/SOAP/XDS/TCP pathways)
- `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/fhir-validation-service` — standalone FHIR validation service
- `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/csv-service` — standalone CSV ingestion/validation service
- `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/hub-core-lib` — shared Java core library
- `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/nexus-core-lib` — shared Nexus Java core library
- `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/api-automation` — Playwright automation project
- `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/integration-artifacts` — channel/schema/config artifacts
- `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/udi-prime` — SQL generation/migration and schema tooling
- `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/support` — supporting docs/specs/release materials
- `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/test-automation` — environment smoke-test bundles

---

## 3) Maven Build Topology

Root build file: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/pom.xml`

Configured Maven modules:
1. `hub-prime`
2. `nexus-core-lib`
3. `nexus-ingestion-api`
4. `hub-core-lib`
5. `fhir-validation-service`
6. `csv-service`

Global properties:
- Java target/source: `21`
- Spring Boot BOM version: `3.3.3`
- Packaging at root: `pom`

---

## 4) Module Catalog

### 4.1 hub-prime
Path: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/hub-prime`

- Maven coordinates: `artifactId=hub-prime`
- Name/Description: *Tech by Design Hub (Prime)*
- Packaging: `jar`
- Approximate source shape:
  - Main Java files: 103
  - Test Java files: 18
  - Main resource files: 161

Key responsibility areas from package layout and controllers:
- FHIR and CSV ingestion endpoints
- UX/dashboard/console routes
- data-quality and interaction views
- documentation and shell navigation routes

Representative endpoint groups:
- FHIR: `/metadata`, `/Bundle`, `/Bundle/$validate`, `/Bundle/$status/{bundleSessionId}`
- CSV: `/flatfile/csv/Bundle`, `/flatfile/csv/Bundle/$validate`
- UX: `/home`, `/console`, `/docs`, `/interactions`, `/needs-attention`, `/data-quality`

### 4.2 nexus-ingestion-api
Path: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/nexus-ingestion-api`

- Maven coordinates: `artifactId=nexus-ingestion-api`
- Packaging: `jar`
- Approximate source shape:
  - Main Java files: 71
  - Test Java files: 48
  - Main resource files: 87

Primary API/controller areas:
- Ingestion entrypoint(s): `/ingest`
- Feature toggles: `/api/features/*`
- XDS endpoint: `/xds/XDSbRepositoryWS`
- Data hold endpoint: `/hold`

The module README (`nexus-ingestion-api/README.md`) documents:
- high-level ingest flow
- protocol handling (HTTP/SOAP/TCP/MLLP)
- port resolution and destination behavior
- feature-flag controls and operations

### 4.3 fhir-validation-service
Path: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/fhir-validation-service`

- Maven coordinates: `artifactId=fhir-validation-service`
- Packaging: `jar`
- Approximate source shape:
  - Main Java files: 22
  - Test Java files: 3
  - Main resource files: 55

Representative routes:
- `/metadata`
- `/Bundle/$validate`
- `/Bundle/$status/{bundleSessionId}`
- `/api/features/*`

### 4.4 csv-service
Path: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/csv-service`

- Maven coordinates: `artifactId=csv-service`
- Packaging: `jar`
- Approximate source shape:
  - Main Java files: 45
  - Test Java files: 12
  - Main resource files: 1

Representative routes:
- `/flatfile/csv/Bundle`
- `/flatfile/csv/Bundle/$validate`
- `/api/features/*`

### 4.5 hub-core-lib
Path: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/hub-core-lib`

- Maven coordinates: `artifactId=hub-core-lib`
- Packaging: `jar`
- Approximate source shape:
  - Main Java files: 66
  - Test Java files: 18
  - Main resource files: 59

### 4.6 nexus-core-lib
Path: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/nexus-core-lib`

- Maven coordinates: `artifactId=nexus-core-lib`
- Packaging: `jar`
- Approximate source shape:
  - Main Java files: 21
  - Test Java files: 2
  - Main resource files: 41

---

## 5) Non-Maven Components

### 5.1 api-automation
Path: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/api-automation`

- Node package with Playwright dependencies
- Includes tests and fixtures for bundle validation workflows
- README-documented run patterns:
  - `npm install`
  - `npx playwright test`
  - `npx playwright show-report`

### 5.2 integration-artifacts
Path: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/integration-artifacts`

Contains documented channel/schema assets for:
- AWS queue listener
- CCDA
- FHIR
- Flatfile
- HL7v2
- global scripts

Index README links into subfolder-specific documentation.

### 5.3 udi-prime
Path: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/udi-prime`

Database/tooling-focused component that uses Deno + SQL Aide workflows to:
- generate SQL artifacts
- generate docs/ERD artifacts
- run migration/testing flows (`udictl.ts` workflows)

### 5.4 support and test-automation
- `support/` contains specs, qualityfolio, release notes, utilities, and archived materials.
- `test-automation/` contains smoke suites split by protocol/environment combinations.

---

## 6) Build, Test, and Run Commands

### 6.1 Root build/test
From `/tmp/workspace/anoopvarma-2000-p/polyglot-prime`:

```bash
mvn clean install
mvn test
```

### 6.2 Service-level development
Common pattern per Java module:

```bash
cd <module>
mvn test
mvn spring-boot:run
```

### 6.3 API automation
From `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/api-automation`:

```bash
npm install
npx playwright test
```

---

## 7) Configuration Notes

Observed default/service config examples:
- `nexus-ingestion-api/src/main/resources/application.yml` defines `server.port: ${SERVER_PORT:8080}`
- `hub-prime/src/main/resources/application.yml` includes SMTP `mail.port: 2525`

Environment-specific profiles exist in `hub-prime` and `fhir-validation-service` via `application-*.yml` files.

---

## 8) Existing Documentation Index

Core starting points:
- Root: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/README.md`
- Ingestion API deep-dive: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/nexus-ingestion-api/README.md`
- UDI workflows: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/udi-prime/README.md`
- Integration assets index: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/integration-artifacts/README.md`
- Playwright automation: `/tmp/workspace/anoopvarma-2000-p/polyglot-prime/api-automation/README.md`

---

## 9) Validation Snapshot (Current Environment)

Baseline command executed:

```bash
mvn test
```

Result in this environment: **build failed before test execution due to Java toolchain mismatch**:
- `invalid target release: 21`

Interpretation:
- The repository expects JDK 21-compatible build tooling.
- To run the full test suite successfully, use a Java 21 runtime/compiler.

---

## 10) Suggested Onboarding Sequence

1. Read root README and this document.
2. Install Java 21 and Maven.
3. Run root `mvn test`.
4. If working on ingestion flows, read `nexus-ingestion-api/README.md` in full.
5. If working on schema/tooling, read `udi-prime/README.md` and execute `udictl.ts` commands.
6. If working on API validations, set up `api-automation` and run Playwright tests.

