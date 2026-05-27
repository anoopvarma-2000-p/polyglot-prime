# hub-prime

## Overview

**hub-prime** is the primary Spring Boot application in the Polyglot Prime
monorepo.  It serves as:

- The main **FHIR R4 ingestion hub**, accepting FHIR bundles from partner
  organisations (tenants), validating them against the SHIN-NY Implementation
  Guide, and forwarding valid bundles to the SHIN-NY Data Lake.
- The **operations dashboard**, a Thymeleaf / HTMX browser application for
  monitoring interactions and managing configuration.
- A **CSV flat-file ingestion endpoint** (delegating to `hub-core-lib`'s
  `CsvService`).

---

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Runtime | Java 21, Spring Boot 3.3 |
| Web framework | Spring MVC, Thymeleaf, HTMX 2.0 |
| FHIR validation | HAPI FHIR R4 |
| Database access | jOOQ (type-safe SQL against PostgreSQL 16) |
| Database | PostgreSQL 16 via `udi-prime` schema |
| Build | Maven |
| Testing | JUnit 5, AssertJ, Playwright |
| Observability | Spring Boot Actuator, OpenTelemetry |
| Feature flags | OpenFeature |

---

## Module Location

```
hub-prime/
├── src/main/java/org/techbd/
│   ├── conf/                    # Global constants and configuration
│   ├── controller/
│   │   └── http/hub/prime/api/  # REST controllers (CsvController, etc.)
│   ├── orchestrate/
│   │   ├── fhir/                # FHIR orchestration engine, IG loading
│   │   └── sftp/                # SFTP file exchange orchestration
│   ├── service/
│   │   └── http/
│   │       ├── filter/          # Servlet filters (security, logging)
│   │       ├── hub/prime/
│   │       │   ├── api/         # FhirController, ExpectController
│   │       │   └── ...          # AppConfig, Application entry point
│   │       └── ...
│   └── util/                    # Utility helpers
├── src/main/resources/
│   ├── ig-packages/             # SHIN-NY IG packages (NPM-format)
│   ├── public/                  # Static web assets (CSS, JS, images)
│   ├── templates/               # Thymeleaf HTML templates
│   │   ├── fragments/           # Reusable template fragments
│   │   ├── layout/              # Page layout templates
│   │   ├── login/               # Login pages
│   │   ├── mock/                # Mock data endpoints
│   │   └── page/                # Full-page templates
│   └── application*.yml         # Spring Boot configuration files
└── src/test/java/org/techbd/
    ├── orchestrate/fhir/        # IG conformance tests
    ├── service/                  # Service-level tests
    └── util/                    # Utility tests
```

---

## Key Classes

### Application Entry Point

**`org.techbd.service.http.hub.prime.Application`**

Standard `@SpringBootApplication` with:
- `@EnableJpaRepositories(basePackages = "org.techbd.udi")`
- `@EnableCaching`
- `@EnableScheduling`
- Component scan: `org.techbd`

### Configuration

**`org.techbd.service.http.hub.prime.AppConfig`**

Bound to the `org.techbd.service.http.hub.prime` properties prefix.  Holds:
- `defaultDatalakeApiUrl` — URL of the SHIN-NY scoring engine
- `defaultDataLakeApiAuthn` — Authentication strategy (mTLS or API key)
- `igPackages` — Map of IG version → package paths
- `structureDefinitionsUrls` — Resource type → StructureDefinition URL mapping
- `validationSeverityLevel` — Minimum severity to report as a failure

### FHIR Controller

**`org.techbd.service.http.hub.prime.api.FhirController`**

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/metadata` | FHIR conformance statement |
| `POST` | `/Bundle` | Validate + persist + forward |
| `POST` | `/Bundle/$validate` | Validate only |
| `GET` | `/Bundle/$status/{id}` | Poll async status |
| `GET` | `/mock/shinny-data-lake/...` | Mock scoring responses |

### CSV Controller

**`org.techbd.controller.http.hub.prime.api.CsvController`**

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/flatfile/csv/Bundle/$validate` | Upload ZIP of CSV files |

Delegates to `hub-core-lib`'s `CsvService`.

### FHIR Orchestration Engine

**`org.techbd.orchestrate.fhir.OrchestrationEngine`** (in `hub-core-lib`)

Manages HAPI FHIR validation sessions:
1. Loads IG packages from classpath or URL
2. Creates a `FhirValidator` instance per IG version
3. Runs the validator against the submitted bundle
4. Produces a structured `OrchestrationSession` with validation results

---

## FHIR Implementation Guide Support

The platform validates against the **SHIN-NY FHIR R4 Implementation Guide**.

IG packages are stored in `src/main/resources/ig-packages/` and configured in
`application.yml`:

```yaml
org:
  techbd:
    ig-packages:
      fhir-v4:
        shinny-packages:
          shinny-v1-8-1:
            profile-base-url: http://shinny.org/us/ny/hrsn
            package-path: ig-packages/shin-ny-ig/shinny/v1.8.1
            ig-version: 1.8.1
          test-shinny-v1-9-1:
            profile-base-url: http://test.shinny.org/us/ny/hrsn
            package-path: ig-packages/shin-ny-ig/test-shinny/v1.9.1
            ig-version: 1.9.1
        base-packages:
          us-core: ig-packages/fhir-v4/us-core/stu-7.0.0
          sdoh:    ig-packages/fhir-v4/sdoh-clinicalcare/stu-2.2.0
          uv-sdc:  ig-packages/fhir-v4/uv-sdc/stu-3.0.0
```

Tenants can request a specific IG version via the `X-SHIN-NY-IG-Version`
request header.

---

## Data Flow

```
POST /Bundle
    │
    ▼
InteractionsFilter (servlet filter)
    │  - Extract tenant ID, correlation ID
    │  - Assign interaction ID (UUID)
    │  - Log request metadata
    ▼
FhirController.validateBundleAndForward()
    │
    ▼
FHIRService.processBundle()
    │
    ├── 1. OrchestrationEngine.validate(payload, igVersion)
    │         └── HAPI FHIR validator + SHIN-NY IG packages
    │
    ├── 2. Persist to PostgreSQL
    │         ├── hub_interaction (interaction ID)
    │         ├── sat_interaction_http_request
    │         ├── sat_interaction_fhir_request
    │         └── sat_interaction_fhir_validation_issue (per issue)
    │
    └── 3. Forward to SHIN-NY Data Lake
              └── mTLS or API key authentication
```

---

## Security

- **Servlet Filters**: `InteractionsFilter` handles tenant ID extraction,
  request wrapping, and interaction ID assignment.
- **mTLS Support**: When forwarding to the SHIN-NY Data Lake, hub-prime
  supports multiple mTLS strategies configured via `defaultDataLakeApiAuthn`:
  - `NO_MTLS` — plain HTTPS
  - `AWS_SECRETS` — certificate loaded from AWS Secrets Manager
  - `RESOURCE` — certificate loaded from classpath resources
  - `POST_STDIN_PAYLOAD` — delegates to an external command
  - `API_KEY` — API key authentication

---

## Running hub-prime

```bash
cd hub-prime
export SPRING_PROFILES_ACTIVE=devl
mvn spring-boot:run
```

Default port: **8080**

```
http://localhost:8080             → dashboard
http://localhost:8080/swagger-ui  → API docs
http://localhost:8080/actuator/health
```

---

## Running Tests

```bash
cd hub-prime
mvn test

# Run IG conformance tests only
mvn -Dtest=org.techbd.orchestrate.fhir.IgPublicationIssuesTest test
```

See [Testing Guide](../testing.md) for full details.
