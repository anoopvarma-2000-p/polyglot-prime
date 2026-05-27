# Tech by Design Polyglot Prime — Comprehensive Project Documentation

> **Organization**: Technology By Design (Tech by Design)  
> **Repository**: `polyglot-prime`  
> **Current Version**: `0.1160.0`  
> **License**: See [LICENSE](./LICENSE)

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Monorepo Strategy](#2-monorepo-strategy)
3. [Repository Structure](#3-repository-structure)
4. [Tech Stack](#4-tech-stack)
5. [Module Descriptions](#5-module-descriptions)
   - 5.1 [hub-prime](#51-hub-prime)
   - 5.2 [udi-prime](#52-udi-prime)
   - 5.3 [nexus-ingestion-api](#53-nexus-ingestion-api)
   - 5.4 [hub-core-lib](#54-hub-core-lib)
   - 5.5 [nexus-core-lib](#55-nexus-core-lib)
   - 5.6 [fhir-validation-service](#56-fhir-validation-service)
   - 5.7 [csv-service](#57-csv-service)
6. [API Reference](#6-api-reference)
7. [Database Architecture](#7-database-architecture)
8. [Security & Authentication](#8-security--authentication)
9. [Data Ingestion Pipeline](#9-data-ingestion-pipeline)
10. [FHIR Validation Engine](#10-fhir-validation-engine)
11. [Integration Artifacts](#11-integration-artifacts)
12. [Testing Strategy](#12-testing-strategy)
13. [CI/CD Pipelines](#13-cicd-pipelines)
14. [Configuration & Environment Variables](#14-configuration--environment-variables)
15. [Local Development Setup](#15-local-development-setup)
16. [Deployment](#16-deployment)

---

## 1. Project Overview

**Tech by Design Polyglot Prime** is a production-grade, enterprise healthcare data integration platform. It serves as the central monorepo for all bespoke code managed by Technology By Design. The platform's primary mission is to ingest, validate, transform, and route healthcare data in multiple formats — **FHIR R4**, **HL7v2**, **CCDA**, and **CSV flat files** — from diverse data sources to the SHIN-NY (Statewide Health Information Network of New York) Data Lake.

### Core Capabilities

| Capability | Description |
|---|---|
| **Multi-format Ingestion** | Accepts FHIR R4, HL7v2, CCDA, and CSV flat file payloads |
| **Multi-protocol Support** | HTTP/HTTPS, MLLP (TCP), SOAP/XDS, SFTP |
| **FHIR Validation** | HAPI FHIR R4 validation against SHIN-NY Implementation Guide (IG) |
| **Data Forwarding** | Routes validated payloads to SHIN-NY Data Lake via API |
| **Interaction Tracking** | End-to-end request/response logging in PostgreSQL |
| **Web Dashboard** | Real-time data quality and interaction monitoring UI |
| **Observability** | OpenTelemetry distributed tracing and metrics |
| **Multi-tenant** | Tenant-aware data routing and access control |

---

## 2. Monorepo Strategy

Inspired by practices at Microsoft, Google, and other large software companies, the monorepo is organized around these principles:

1. **Modular Structure** — Each top-level directory represents a distinct project or service with clear separation of concerns.
2. **Consistent Naming** — Predictable naming conventions across all modules.
3. **Shared Libraries** — `hub-core-lib` and `nexus-core-lib` promote code reuse.
4. **Version Control** — Single source of truth for all services via Git.
5. **CI/CD Integration** — Per-module and cross-module automated pipelines.
6. **Documentation** — Each project contains its own documentation.

All Java modules are governed by a single Maven parent POM (`/pom.xml`) that coordinates versioning via the `revision` property (`${revision}`), resolved at build time using the Flatten Maven Plugin.

---

## 3. Repository Structure

```
polyglot-prime/
├── api-automation/              # TypeScript/Playwright API test automation
├── core-lib/                    # Generic core library
├── csv-service/                 # CSV file processing and transformation service
├── fhir-validation-service/     # Standalone FHIR compliance validation service
├── hub-core-lib/                # Shared Java library (FHIR, CSV, AWS utilities)
├── hub-prime/                   # Primary Spring Boot FHIR API hub and UI application
├── integration-artifacts/       # Integration configs, schemas, and templates
├── nexus-core-lib/              # Nexus-specific shared Java library
├── nexus-ingestion-api/         # Multi-protocol data ingestion service
├── support/                     # Non-production tools, docs, and specifications
├── test-automation/             # Smoke test suites for all environments
├── udi-prime/                   # PostgreSQL database schema + jOOQ code generation
├── pom.xml                      # Maven parent POM
├── .envrc.example               # Environment variable template
└── README.md                    # Top-level readme
```

---

## 4. Tech Stack

### Languages

| Language | Usage |
|---|---|
| Java 21 LTS | All backend services and libraries |
| TypeScript / Deno | Database schema generation (`udi-prime`), utility scripting |
| TypeScript / Node.js | API test automation (`api-automation`) |
| SQL / PL/pgSQL | PostgreSQL DDL, stored procedures, views |
| Thymeleaf | Server-side HTML templating |
| XSLT | HL7v2 → FHIR and CCDA → FHIR transformations |

### Frameworks & Core Libraries

| Library | Version | Purpose |
|---|---|---|
| Spring Boot | 3.3.3 | Web application framework |
| Spring Security | 6.x | Authentication and authorization |
| Spring Data JPA | 3.x | Database access |
| Spring WebFlux | 3.x | Reactive HTTP client (data lake forwarding) |
| Spring Actuator | 3.x | Health checks, metrics, management |
| Thymeleaf | 3.x | HTML templates |
| Thymeleaf Layout Dialect | — | Template composition |
| HTMX | 2.0 | Frontend HATEOAS interactions |
| HAPI FHIR | 8.2.2 | FHIR R4 parsing and validation |
| jOOQ | — | Type-safe SQL DSL and code generation |
| Hibernate ORM | — | JPA implementation |
| Jackson | 2.17.1 | JSON serialization/deserialization |
| Lombok | — | Boilerplate reduction |
| BouncyCastle | 1.84 | Cryptography, SSL/mTLS |
| OkHttp3 | 5.0.0-alpha.3 | HTTP client |
| OpenCSV | — | CSV parsing |
| Flexmark | — | Markdown → HTML rendering |
| Commons VFS2 | — | Virtual file system (SFTP) |
| Reactor Core | — | Reactive programming |
| SpringDoc OpenAPI | — | Swagger/OpenAPI UI generation |
| Playwright | 1.56.1 | Browser and API test automation |
| JUnit 5 | — | Unit testing |
| AssertJ | — | Fluent test assertions |

### Infrastructure

| Component | Technology |
|---|---|
| Primary Database | PostgreSQL 16 (Amazon Aurora) |
| Object Storage | AWS S3 |
| Message Queue | AWS SQS (FIFO) |
| Observability | OpenTelemetry + OpenObserve |
| Feature Flags | OpenFeature / Togglz |
| Identity Provider | FusionAuth (OAuth2 / OIDC) |
| Container Runtime | Docker |
| Build Tool | Apache Maven 3.x |

---

## 5. Module Descriptions

### 5.1 hub-prime

**Type**: Spring Boot 3.3+ web application (JAR packaging)  
**Artifact ID**: `hub-prime`  
**Entry Point**: `org.techbd.service.http.hub.prime.Application`

The primary hub application combining a FHIR REST API with a Thymeleaf-based monitoring UI. It validates and forwards FHIR bundles to the SHIN-NY Data Lake, persists all interaction metadata in PostgreSQL, and exposes diagnostic dashboards.

#### Package Structure

```
org.techbd/
├── component/
│   ├── SessionCleanupListener.java     # Session lifecycle management
│   └── SessionRegistry.java            # Active session tracking
├── conf/
│   ├── Configuration.java              # Core configuration constants
│   └── CoreLibYamlLoader.java          # Loads shared YAML configs
├── controller/
│   ├── FusionAuthWebhookController.java  # FusionAuth webhook handler
│   └── http/hub/prime/api/
│       └── CsvController.java          # CSV submission endpoint
├── orchestrate/
│   ├── fhir/util/
│   │   ├── ConceptReaderUtils.java     # FHIR concept reading
│   │   └── FileUtils.java             # File utility helpers
│   └── sftp/
│       ├── SftpAccountsOrchctlConfig.java  # SFTP account configuration
│       └── SftpManager.java            # SFTP session management with caching
├── service/
│   ├── DocResourcesService.java        # Documentation resource serving
│   ├── InteractionService.java         # Interaction persistence service
│   ├── constants/                      # ErrorCode, Origin, SourceType enums
│   ├── exception/                      # JsonValidationException
│   └── http/
│       ├── Constant.java               # URL constants (stateless/public URLs)
│       ├── FusionAuthUserAuthorizationFilter.java
│       ├── FusionAuthUsersService.java
│       ├── GitHubUserAuthorizationFilter.java
│       ├── GitHubUsersService.java
│       ├── Helpers.java                # HTTP utility helpers
│       ├── Interactions.java           # Interaction model
│       ├── InteractionsFilter.java     # Servlet filter for interaction logging
│       ├── NoAuthSecurityConfig.java   # Open/no-auth security profile
│       ├── OLTPConfiguration.java      # Database connection pool config
│       ├── SandboxHelpers.java         # Sandbox environment utilities
│       ├── SecurityConfig.java         # Spring Security chain configuration
│       ├── SwaggerConfig.java          # SpringDoc/Swagger configuration
│       ├── UxReportableObservability.java  # UX observability hooks
│       └── hub/prime/
│           ├── AppConfig.java          # Application configuration properties
│           ├── Application.java        # Spring Boot entry point
│           ├── ServletInitializer.java # War deployment initializer
│           ├── api/
│           │   ├── FhirController.java      # FHIR REST API endpoints
│           │   ├── ExpectController.java    # Test expectation endpoints
│           │   └── GlobalExceptionHandler.java
│           ├── health/
│           │   ├── BundleHealthIndicator.java
│           │   └── BundleValidateHealthIndicator.java
│           ├── route/
│           │   ├── RouteMapping.java
│           │   ├── RoutesTree.java
│           │   └── RoutesTrees.java
│           └── ux/
│               ├── ConsoleController.java
│               ├── ContentController.java
│               ├── DataQualityController.java
│               ├── DocsController.java
│               ├── ExperimentsController.java
│               ├── InteractionsController.java
│               ├── MavenController.java
│               ├── NeedAttentionController.java
│               ├── Presentation.java
│               ├── PrimeController.java
│               ├── ShellController.java
│               ├── TabularRowsController.java
│               ├── config/FileDownloadProperties.java
│               └── validator/
│                   ├── JsonPathValidator.java
│                   └── Validator.java
└── util/
    ├── AWSUtil.java
    ├── ArtifactStore.java
    ├── CsvConstants.java
    └── ...
```

#### Key Configuration Properties

```yaml
org.techbd.service.http.hub.prime:
  version: @project.version@
  fhirVersion: r4
  maxDownloadJsonPrettyPrintSizeMB: 1
```

---

### 5.2 udi-prime

**Type**: Database schema management and code generation project  
**Technology**: Deno (TypeScript) + SQL Aide + jOOQ  
**Directory**: `udi-prime/`

Manages all PostgreSQL schema DDL, migrations, and type-safe Java code generation for the ingestion database.

#### Directory Layout

```
udi-prime/
├── src/
│   ├── main/postgres/ingestion-center/
│   │   ├── 000_idempotent_universal.psql      # Universal utilities (register_issue, etc.)
│   │   ├── 001_idempotent_interaction.psql    # Interaction views
│   │   ├── 003_idempotent_migration.psql
│   │   ├── 004_idempotent_content_fhir.psql
│   │   ├── 005_idempotent_stored_routines.psql
│   │   ├── 006_idempotent_cron.psql
│   │   ├── 007_idempotent_interaction.psql
│   │   └── migrations/
│   │       ├── migrate-basic-infrastructure.ts
│   │       ├── migrate-cron.ts
│   │       ├── migrate-content-fhir-view.ts
│   │       ├── migrate-diagnostics-fhir-view.ts
│   │       ├── migrate-interaction-fhir-view.ts
│   │       ├── migrate-ddl-stored-routine-interaction.ts
│   │       ├── migrate-models-dv.ts
│   │       └── migrations.ts          # Migration orchestrator
│   └── test/postgres/ingestion-center/
│       └── fixtures.sql               # pgTAP test fixtures
└── support/jooq/                     # jOOQ code generation configs
```

#### CLI Tool (`udictl.ts`)

The primary tool for database operations:

```bash
./udictl.ts generate sql               # Generate SQL DDL from TypeScript models
./udictl.ts generate java jooq         # Generate type-safe Java jOOQ classes
./udictl.ts generate docs              # Generate database documentation
./udictl.ts load-sql                   # Load SQL into database
./udictl.ts migrate                    # Run database migrations
./udictl.ts test                       # Run pgTAP database tests
./udictl.ts ic omnibus-fresh --deploy-jar  # Full rebuild + deploy JAR
```

#### Generated Artifact

The jOOQ code generation produces `lib/techbd-udi-jooq-ingress.auto.jar`, consumed as a system-scope dependency in `hub-prime`.

---

### 5.3 nexus-ingestion-api

**Type**: Spring Boot multi-protocol ingestion service  
**Artifact ID**: `nexus-ingestion-api`  
**Default Port**: `8080` (HTTP), `7980` (MLLP/TCP)

A source-agnostic ingestion gateway that accepts data from healthcare partners over HTTP/HTTPS, MLLP (TCP), or SOAP/XDS, uploads payloads to AWS S3 with structured date partitioning, and enqueues message metadata to AWS SQS FIFO for ordered downstream processing.

#### Supported Protocols

| Protocol | Transport | Port |
|---|---|---|
| REST/HTTP | HTTP/HTTPS | 8080 |
| MLLP | Raw TCP | 7980 |
| SOAP / XDS | HTTP/HTTPS | 8080 |

#### Message Flow

```
Client Request
    │
    ▼
Port/Protocol Resolution (PortResolverService)
    │
    ▼
Generate Unique interactionId (UUID)
    │
    ▼
Upload Payload → S3
  s3://<bucket>/<datadir>/data/<YYYY>/<MM>/<DD>/<timestamp>-<interactionId>
    │
    ▼
Upload Metadata JSON → S3 (alongside payload)
    │
    ▼
Enqueue to SQS FIFO (deterministic messageGroupId)
    │
    ▼
Return Protocol-Specific ACK/NACK
  • HTTP → JSON response
  • MLLP → HL7 ACK
  • SOAP → MTOM response
```

#### Port Configuration Resolution (Priority Order)

1. Source type + message type match (highest priority)
2. Port + protocol match
3. Fallback error response

#### Controller Endpoints

| Controller | Base Path | Description |
|---|---|---|
| `DataIngestionController` | `POST /ingest` | HTTP data submission |
| `XdsRepositoryController` | `POST /xds/XDSbRepositoryWS` | SOAP/XDS document submission |
| `DataHoldController` | — | Data hold management |
| `FeatureToggleController` | `/api/features` | Feature flag management |
| `NettyTcpServer` | `TCP:7980` | MLLP raw TCP listener |

#### Package Structure

```
org.techbd.ingest/
├── controller/
│   ├── DataIngestionController.java    # Primary HTTP ingestion endpoint
│   ├── XdsRepositoryController.java    # SOAP/XDS endpoint
│   ├── DataHoldController.java         # Data hold management
│   ├── FeatureToggleController.java    # Feature flag API
│   ├── InteractionsFilter.java         # Request/response logging filter
│   ├── SoapFaultEnhancementFilter.java # SOAP fault enrichment
│   └── CachedBodyHttpServletRequest.java
├── service/
│   ├── MessageProcessorService.java    # Core message processing
│   ├── SoapForwarderService.java       # SOAP message forwarding
│   ├── MetadataBuilderService.java     # S3 metadata construction
│   └── messagegroup/
│       ├── MessageGroupService.java
│       ├── MessageGroupStrategy.java   # Strategy interface
│       ├── IpPortMessageGroupStrategy.java
│       ├── MllpMessageGroupStrategy.java
│       ├── TenantMessageGroupStrategy.java
│       ├── TcpMessageGroupStrategy.java
│       └── SourceMsgTypeGroupStrategy.java
├── listener/
│   └── NettyTcpServer.java             # MLLP/TCP server (Netty)
├── interceptors/
│   └── WsaHeaderInterceptor.java       # WS-Addressing header handling
├── model/
│   └── RequestContext.java             # Request metadata model
└── util/
    ├── AppLogger.java
    ├── TemplateLogger.java
    ├── HttpUtil.java
    ├── Hl7Util.java
    ├── SoapFaultUtil.java
    ├── SoapResponseUtil.java
    ├── UuidUtil.java
    ├── LogUtil.java
    └── ProxyProtocolParserUtil.java
```

---

### 5.4 hub-core-lib

**Type**: Shared Java library (JAR)  
**Artifact ID**: `hub-core-lib`

Contains all common utilities, FHIR service logic, CSV processing, data ledger integration, and shared configuration used across `hub-prime` and `fhir-validation-service`.

#### Key Components

| Package/Class | Purpose |
|---|---|
| `service.fhir.FHIRService` | Core FHIR bundle processing — validation, storage, forwarding |
| `service.fhir.FhirReplayService` | Replays previously ingested FHIR bundles |
| `service.fhir.engine.OrchestrationEngine` | HAPI FHIR validation engine orchestration |
| `service.fhir.validation.FhirBundleValidator` | Bundle-level validation logic |
| `service.fhir.validation.PrePopulateSupport` | Pre-validation data enrichment |
| `service.fhir.validation.PostPopulateSupport` | Post-validation result enrichment |
| `service.dataledger.CoreDataLedgerApiClient` | External Data Ledger API integration |
| `util.fhir.CoreFHIRUtil` | FHIR resource utilities |
| `util.AWSUtil` | AWS SDK wrapper (S3, Secrets Manager) |
| `util.AppLogger` / `TemplateLogger` | Structured logging |
| `util.DateUtil` | Date/time parsing and formatting |
| `config.CoreAppConfig` | Shared configuration properties |

#### Environment Profiles Supported

`devl`, `phiqa`, `sandbox`, `stage`, `phiprod`

---

### 5.5 nexus-core-lib

**Type**: Shared Java library (JAR)  
**Artifact ID**: `nexus-core-lib`

Nexus-specific shared library providing utilities and configuration common to `nexus-ingestion-api` and related Nexus services.

#### Provided Utilities

| Class | Purpose |
|---|---|
| `AppLogger` | Structured logging |
| `TemplateLogger` | Template-based logging |
| `DataLedgerApiClient` | Data Ledger API integration |
| `UuidUtil` | UUID generation utilities |

#### Environment Profiles Supported

`devl`, `phiqa`, `sandbox`, `stage`, `phiprod`

---

### 5.6 fhir-validation-service

**Type**: Standalone Spring Boot application (WAR/JAR)  
**Artifact ID**: `fhir-validation-service`

A dedicated, independently deployable FHIR compliance validation service. Shares the HAPI FHIR validation engine from `hub-core-lib` and exposes its own REST API.

#### Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/metadata` | FHIR server conformance statement (XML) |
| `POST` | `/Bundle/$validate` | Validate a FHIR bundle (no storage) |
| `GET` | `/Bundle/$status/{bundleSessionId}` | Check async validation status |
| `GET` | `/mock/shinny-data-lake/1115-validate/{resourcePath}.json` | Mock validation response |
| `GET` | `/Bundles/status/nyec-submission-failed` | Failed NYEC submissions |
| `GET` | `/Bundles/status/operation-outcome` | OperationOutcome results |
| `GET/HEAD` | `/` | Health check |
| `GET` | `/api/features/status` | Feature flag status |
| `POST` | `/api/features/enable` | Enable a feature flag |
| `POST` | `/api/features/disable` | Disable a feature flag |
| `GET` | `/api/features/all/feature/status` | All feature flags |

#### Supported SHIN-NY IG Versions

| Key | IG Version | Profile Base URL |
|---|---|---|
| `test-shinny-v1-9-1` | 1.9.1 | `http://test.shinny.org/us/ny/hrsn` |
| `shinny-v1-8-1` | 1.8.1 | `http://shinny.org/us/ny/hrsn` |

**Base FHIR packages included**:
- `us-core` (STU 7.0.0)
- `sdoh-clinicalcare` (STU 2.2.0)
- `uv-sdc` (STU 3.0.0)

---

### 5.7 csv-service

**Type**: Spring Boot service (JAR)  
**Artifact ID**: `csv-service`

Processes CSV flat files, validates them against NYHER FHIR IG-equivalent rules using a Python validation script, and converts valid data to FHIR R4 bundles.

#### Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/flatfile/csv/Bundle/$validate` | Validate CSV without storing |
| `POST` | `/flatfile/csv/Bundle` | Validate, convert, and store CSV as FHIR |
| `GET/HEAD` | `/` | Health check |
| `GET` | `/api/features/status` | Feature flag status |

#### CSV Processing Pipeline

```
CSV Upload (multipart/form-data)
    │
    ▼
Python Validation Script
  (validate-nyher-fhir-ig-equivalent.py)
    │
    ▼
CsvBundleProcessorService
    │
    ▼
FHIR Resource Converters (per entity type)
  ├── PatientConverter
  ├── OrganizationConverter
  ├── EncounterConverter
  ├── ConsentConverter
  ├── ProcedureConverter
  ├── ScreeningResponseObservationConverter
  └── SexualOrientationObservationConverter
    │
    ▼
FHIR Bundle Assembly
    │
    ▼
Persist to PostgreSQL via jOOQ
```

#### Configuration

```yaml
org.techbd.csv.validation:
  pythonScriptPath: ${TECHBD_PYTHON_SCRIPT_PATH}support/specifications/flat-file/validate-nyher-fhir-ig-equivalent.py
  pythonExecutable: python3
  packagePath: ${TECHBD_PYTHON_SCRIPT_PATH}support/specifications/flat-file/datapackage-nyher-fhir-ig-equivalent.json
  inboundPath: /app/techbyDesign/flatFile/inbound
  outputPath: /app/techbyDesign/flatFile/outbound
  ingressHomePath: /app/techbyDesign/flatFile/ingress
```

---

## 6. API Reference

### hub-prime FHIR API Endpoints

| Method | Path | Auth Required | Description |
|---|---|---|---|
| `GET` | `/metadata` | No | FHIR conformance statement (CapabilityStatement) |
| `POST` | `/Bundle` | No (stateless) | Validate, store, and forward FHIR bundle to SHIN-NY |
| `POST` | `/Bundle/` | No (stateless) | Alias for `/Bundle` |
| `POST` | `/Bundle/$validate` | No (stateless) | Validate only — no storage or forwarding |
| `GET` | `/Bundle/$status/{bundleSessionId}` | No | Check async bundle processing status |
| `GET` | `/mock/shinny-data-lake/1115-validate/{resourcePath}.json` | No | Mock validation responses |
| `POST` | `/Bundle/replay` | No (stateless) | Replay FHIR bundles between date/time range |
| `GET` | `/Bundles/status/nyec-submission-failed` | No | FHIR bundles that failed NYEC submission |
| `GET` | `/Bundles/status/operation-outcome` | No | OperationOutcome results by Bundle/Interaction ID |
| `POST` | `/flatfile/csv/Bundle/$validate` | No (stateless) | Validate CSV payload |
| `POST` | `/flatfile/csv/Bundle` | No (stateless) | Submit CSV for validation and ingestion |
| `POST` | `/api/expect/fhir/bundle` | No (stateless) | Submit expected FHIR bundle for testing |
| `POST` | `/fusionauth/webhook` | No | FusionAuth webhook receiver |

### hub-prime UI Routes

| Path | Description |
|---|---|
| `/` | Home / Dashboard |
| `/home` | Main dashboard |
| `/login` | Login page |
| `/console` | Admin console |
| `/console/health-info` | Application health details |
| `/console/project` | Maven project info |
| `/console/schema` | Database schema viewer |
| `/console/islm` | ISLM migration status |
| `/console/pgtap-test-results` | pgTAP database test results |
| `/console/diagnostics` | System diagnostics |
| `/console/diagnostics/database-exceptions` | Database exception log |
| `/console/diagnostics/database-diagnostics-logs` | Diagnostic logs |
| `/interactions` | All interactions overview |
| `/interactions/httpsfhir` | FHIR HTTP interactions |
| `/interactions/httpsfailed` | Failed HTTP interactions |
| `/interactions/httpscsv` | CSV HTTP interactions |
| `/interactions/httpsccda` | CCDA HTTP interactions |
| `/interactions/httpshl7v2` | HL7v2 HTTP interactions |
| `/interactions/https` | All HTTPS interactions |
| `/interactions/observe` | Observability dashboard |
| `/interactions/observe/api-performance` | API performance metrics |
| `/data-quality` | Data quality overview |
| `/data-quality/needs-attention` | Items needing attention |
| `/data-quality/fhir-validation-issues` | FHIR validation issue details |
| `/data-quality/csv-validations` | CSV validation overview |
| `/data-quality/csv-validations/csv-issues-summary` | CSV issue summary |
| `/data-quality/csv-validations/csv-issue-details` | CSV issue details |
| `/needs-attention` | Attention-required items overview |
| `/needs-attention/techbd-processing-failures` | TechBD processing failures |
| `/needs-attention/techbd-validation-failures` | TechBD validation failures |
| `/needs-attention/shinny-datalake-failed-submissions` | SHIN-NY failed submissions |
| `/docs` | Documentation home |
| `/docs/techbd-hub` | TechBD Hub documentation |
| `/dashboard/stat/fhir/most-recent/{tenantId}.{extension}` | Recent FHIR stats per tenant |
| `/dashboard/stat/fhir/fhir-submission-summary` | FHIR submission summary |
| `/dashboard/stat/csv/most-recent/{tenantId}.{extension}` | Recent CSV stats per tenant |
| `/dashboard/stat/ccda/most-recent/{tenantId}.{extension}` | Recent CCDA stats per tenant |
| `/dashboard/stat/hl7v2/most-recent/{tenantId}.{extension}` | Recent HL7v2 stats per tenant |
| `/admin/cache/tenant-sftp-egress-content/clear` | Clear SFTP egress cache |

### OpenAPI / Swagger

| Path | Description |
|---|---|
| `/docs/api/interactive/index.html` | Swagger UI |
| `/docs/api/openapi` | OpenAPI JSON spec |

### Actuator Endpoints

| Path | Description |
|---|---|
| `/actuator/health` | Application health status |

---

## 7. Database Architecture

### Schema Overview

All database objects live in the `techbd_udi_ingress` schema within PostgreSQL.

#### Core Tables

| Table | Description |
|---|---|
| `hub_interaction` | Master table of all inbound interactions (one row per request) |
| `sat_interaction_fhir_request` | FHIR-specific HTTP request satellites (denormalized for performance) |
| `sat_interaction_user` | User session interaction tracking |
| `hub_diagnostic` | Diagnostic event registry |
| `sat_diagnostic_exception` | Exception details (SQL state, context, hints) |

#### Key Views

| View | Description |
|---|---|
| `interaction_http_request` | Unified view of all HTTP interactions (FHIR + user sessions) |
| `interaction_http_fhir_request` | Filtered view of FHIR-specific HTTP requests |

#### Key Stored Functions

| Function | Purpose |
|---|---|
| `register_issue(...)` | Registers an exception with diagnostics |
| `convert_csv_to_json(p_csv_data, p_delimiter)` | Converts a CSV string to JSONB |

#### Connection Pool Configuration

```yaml
# HikariCP settings (per service)
maximumPoolSize: 10       # vCPU × 2 + overhead (Aurora 2vCPU)
minimumIdle: 5
idleTimeout: 60000        # 1 minute
connectionTimeout: 20000  # 20 seconds
maxLifetime: 1800000      # 30 minutes
```

#### Read/Write Split

The system supports optional read/write split via a secondary datasource:

```yaml
org.techbd.udi.uiReadsFromReaderEnabled: ${ORG_TECHBD_DB_READ_WRITE_SPLIT_ENABLED:false}
```

When enabled, UI read queries use the `secondary` JDBC datasource; writes and API operations always use the `primary` datasource.

### Database Migrations

Migrations are written in TypeScript (Deno + SQL Aide) and produce idempotent PostgreSQL DDL:

| File | Description |
|---|---|
| `migrate-basic-infrastructure.ts` | Core schema tables, indexes, constraints |
| `migrate-interaction-fhir-view.ts` | FHIR interaction tracking views |
| `migrate-diagnostics-fhir-view.ts` | Diagnostic views |
| `migrate-content-fhir-view.ts` | Content-specific FHIR views |
| `migrate-cron.ts` | `pg_cron` job definitions |
| `migrate-ddl-stored-routine-interaction.ts` | Stored procedures and routines |
| `migrate-models-dv.ts` | Data Vault model tables |

Migrations are tracked via **ISLM (Information Schema Lifecycle Management)** and are idempotent — safe to re-run.

---

## 8. Security & Authentication

### Authentication Providers

The system supports two OAuth2 identity providers, configured via `AUTH_PROVIDER` environment variable:

| Provider | Value | Description |
|---|---|---|
| FusionAuth | `fusionauth` | Primary SSO via FusionAuth OIDC |
| GitHub OAuth | `github` | GitHub App OAuth2 for developer access |

### Spring Security Configuration

```
SecurityFilterChain (stateless) → /Bundle/**, /flatfile/csv/Bundle/**, /Hl7/v2/**, /api/expect/**, /Bundles/**
    → All requests: permitAll (stateless API, no session)

SecurityFilterChain (stateful) → All other routes
    → Public: /login/**, /oauth2/**, /, /metadata, /docs/api/**, /error/**
    → Authenticated: everything else

Filters (in order):
1. InteractionsFilter         → Logs all requests/responses
2. FusionAuthUserAuthorizationFilter (or GitHubUserAuthorizationFilter)
3. UsernamePasswordAuthenticationFilter
```

### Security Features

| Feature | Implementation |
|---|---|
| Session management | 60-minute timeout, secure HttpOnly cookies, SameSite=LAX |
| CSRF | Disabled for stateless API endpoints |
| HSTS | Max-age 31,536,000 seconds (1 year) |
| mTLS | BouncyCastle + client certificate validation (configurable) |
| IP Whitelisting | CIDR block validation in nexus-ingestion-api |
| Secrets Management | AWS Secrets Manager for API keys |

### Security Profiles

- **`localopen`**: No authentication (local development only)
- **`sandbox`**: OAuth2 + dummy user support (`SANDBOX_USE_DUMMY_USER=true`)
- **`devl` / `phiqa` / `stage` / `phiprod`**: Full OAuth2 with FusionAuth or GitHub

---

## 9. Data Ingestion Pipeline

### FHIR Bundle Ingestion Flow

```
HTTP POST /Bundle (JSON payload)
    │
    ▼
InteractionsFilter
  → Capture request metadata
  → Assign interaction ID
    │
    ▼
FhirController.handleBundle()
    │
    ▼
FHIRService.processBundle()
    │
    ├─► OrchestrationEngine.validate()
    │      │
    │      ├─► PrePopulateSupport (enrich context)
    │      ├─► FhirBundleValidator (HAPI FHIR validation chain)
    │      │      ├─ DefaultProfileValidationSupport
    │      │      ├─ NpmPackageValidationSupport (SHIN-NY IG)
    │      │      ├─ CommonCodeSystemsTerminologyService
    │      │      ├─ InMemoryTerminologyServerValidationSupport
    │      │      ├─ SnapshotGeneratingValidationSupport
    │      │      └─ CachingValidationSupport (Caffeine)
    │      └─► PostPopulateSupport (enrich result)
    │
    ├─► Persist to PostgreSQL (jOOQ)
    │      → hub_interaction
    │      → sat_interaction_fhir_request
    │
    ├─► CoreDataLedgerApiClient (if tracking enabled)
    │      → POST to Data Ledger API
    │
    └─► Forward to SHIN-NY Data Lake
           → Spring WebFlux reactive HTTP client
           → mTLS or API key authentication
    │
    ▼
Return OperationOutcome JSON
```

### CSV Ingestion Flow

```
HTTP POST /flatfile/csv/Bundle (multipart/form-data)
    │
    ▼
CsvController (hub-prime) or CsvController (csv-service)
    │
    ▼
Python Validation Script
  (validate-nyher-fhir-ig-equivalent.py)
    │
    ▼
CsvBundleProcessorService
    │
    ▼
Per-Resource FHIR Converters (Patient, Org, Encounter, etc.)
    │
    ▼
FHIR Bundle Assembly → FHIRService.processBundle()
    │
    ▼
Return OperationOutcome JSON
```

### HL7v2 / CCDA Ingestion (via Nexus)

```
Mirth Connect / Nexus Integration Layer
    │
    ▼ XSLT Transformation
    │  (hl7v2-fhir-bundle.xslt / cda-fhir-bundle.xslt)
    │
    ▼
nexus-ingestion-api: POST /ingest
    │
    ▼
S3 Upload (date-partitioned) + SQS FIFO enqueue
    │
    ▼
Downstream consumer processes from SQS
```

---

## 10. FHIR Validation Engine

### Architecture

The `OrchestrationEngine` (in `hub-core-lib`) manages a cache of `ValidationEngine` instances keyed by FHIR profile URL. Each engine wraps the full HAPI FHIR validation chain.

### Validation Support Chain

```
ValidationSupportChain
 ├── DefaultProfileValidationSupport        # Built-in FHIR R4 profiles
 ├── NpmPackageValidationSupport            # SHIN-NY IG package
 │     ├── shinny/v1.8.1 (production IG)
 │     └── test-shinny/v1.9.1 (test IG)
 ├── CommonCodeSystemsTerminologyService    # Standard code systems
 ├── InMemoryTerminologyServerValidationSupport
 ├── SnapshotGeneratingValidationSupport    # Profile snapshot generation
 └── CachingValidationSupport (Caffeine)   # Result caching
```

### Validation Severity Levels

Configurable via `validation-severity-level` property:

| Level | Description |
|---|---|
| `fatal` | Only fatal errors fail validation |
| `error` | Fatal + errors fail validation (default) |
| `warning` | Fatal + errors + warnings fail |
| `information` | All issues fail validation |

### SHIN-NY Profile URLs

| Resource | Profile URL Path |
|---|---|
| Bundle | `/StructureDefinition/SHINNYBundleProfile` |
| Patient | `/StructureDefinition/shinny-patient` |
| Consent | `/StructureDefinition/shinny-Consent` |
| Encounter | `/StructureDefinition/shinny-encounter` |
| Organization | `/StructureDefinition/shin-ny-organization` |
| Observation | `/StructureDefinition/shinny-observation-screening-response` |
| Questionnaire | `/StructureDefinition/shinny-questionnaire` |
| Practitioner | `/StructureDefinition/shin-ny-practitioner` |
| Sexual Orientation Obs. | `/StructureDefinition/shinny-observation-sexual-orientation` |
| Procedure | `/StructureDefinition/shinny-sdoh-procedure` |

---

## 11. Integration Artifacts

The `integration-artifacts/` directory contains all configurations, schemas, and templates for integrating external systems via Mirth Connect or Nexus.

### Directory Contents

| Directory | Description |
|---|---|
| `hl7v2/` | HL7v2 channel files (Mirth Connect and Nexus), XSLT transforms, validation schemas |
| `ccda/` | CCDA → FHIR XSLT transforms, XSD schemas, PHI filter templates |
| `fhir/` | FHIR-specific integration artifacts |
| `flatfile/` | Flat file processing templates |
| `aws-queue-listener/` | AWS SQS listener configurations |
| `lookup-manager/` | Lookup table exports (Nexus) |
| `global-scripts/` | Shared JavaScript functions for Mirth Connect |
| `custom-lib/` | Custom integration libraries |
| `code-templates/` | Reusable Mirth Connect code templates |

### HL7v2 Transformation

XSLT-based pipeline:

```
HL7v2 Message → hl7v2-fhir-bundle.xslt → FHIR R4 Bundle JSON
```

Validation schema: `hl7v2-validation-schema.xml`

### CCDA Transformation

Multiple XSLT stylesheets per EHR vendor:

```
CCDA Document
  ├── cda-phi-filter.xslt           → PHI filtering (generic)
  ├── cda-phi-filter-athenahealth.xslt
  ├── cda-phi-filter-medent.xslt
  └── cda-fhir-bundle.xslt          → CCDA to FHIR conversion
      ├── cda-fhir-bundle-athenahealth.xslt
      ├── cda-fhir-bundle-medent.xslt
      └── cda-fhir-bundle-common-utils.xslt
```

---

## 12. Testing Strategy

### Unit Tests (JUnit 5 + AssertJ)

Each Maven module contains unit tests under `src/test/java/`:

| Module | Key Test Classes |
|---|---|
| `hub-core-lib` | `OrchestrationEngineTest`, `IgPublicationIssuesTest`, `BaseIgValidationTest`, `CsvServiceTest`, `CsvBundleProcessorServiceTest` |
| `fhir-validation-service` | `OrchestrationEngineTest`, `IgPublicationIssuesTest`, `BaseIgValidationTest` |
| `csv-service` | `CsvServiceTest`, `CsvBundleProcessorServiceTest`, `FileProcessorTest`, `PatientConverterTest`, `OrganizationConverterTest`, `EncounterConverterTest`, `ConsentConverterTest`, `ProcedureConverterTest`, `ScreeningResponseObservationConverterTest`, `SexualOrientationObservationConverterTest` |
| `nexus-core-lib` | `UuidUtilTest`, `DataLedgerApiClientTest` |
| `nexus-ingestion-api` | `InteractionsFilterTest` |

Run all unit tests:

```bash
mvn test
```

### Integration Tests (Maven Failsafe)

```bash
mvn verify -Pintegration-tests
```

### Database Tests (pgTAP)

Located in `udi-prime/src/test/postgres/ingestion-center/fixtures.sql`.

Run via `udictl.ts test`.

### API Automation Tests (Playwright/TypeScript)

Located in `api-automation/`:

| Test File | Description |
|---|---|
| `tests/FHIR-BundlePositive.test.ts` | Valid FHIR bundle scenarios |
| `tests/FHIR-BundleNegative.test.ts` | Invalid/malformed bundle scenarios |

Run API tests:

```bash
cd api-automation
npm install
npx playwright test
```

### Smoke Tests

Environment-specific smoke test suites in `test-automation/`:

| Suite | Environments |
|---|---|
| `FHIR-Bundle-SmokeTest-*` | Devl, PHI-QA, Stage |
| `CSV-Bundle-SmokeTest-*` | PHI-QA, Stage |
| `CCDA-Bundle-SmokeTest-*` | PHI-QA, Stage |
| `HL7-Bundle-SmokeTest-*` | PHI-QA, Stage |

---

## 13. CI/CD Pipelines

All pipelines are GitHub Actions workflows located in `.github/workflows/`.

### Build Workflows

| Workflow | Trigger | Description |
|---|---|---|
| `maven-fhir-compile.yml` | Push/PR to `main` | Compile all Maven modules with Java 21 (Corretto) |
| `maven-fhir-site.yml` | Manual / release | Generate Maven site documentation |

### Test Workflows

| Workflow | Description |
|---|---|
| `Integration-Tests.yml` | Nexus API integration tests |
| `fhir-bundle-smoketest.yml` | FHIR bundle smoke tests |
| `csv-bundle-smoketest.yml` | CSV bundle smoke tests |
| `hl7-bundle-smoketest.yml` | HL7v2 bundle smoke tests |
| `ccda-bundle-smoketest.yml` | CCDA bundle smoke tests |
| `devl-api-automation.yml` | Development environment API tests |
| `stage-api-automation.yml` | Staging environment API tests |

### Deployment Workflows

| Workflow | Trigger | Description |
|---|---|---|
| `deploy-techbd-org.yml` | Release published | Multi-environment deployment via infrastructure-prime PRs |
| `nexus-deploy-techbd.yml` | Release `*-nexus` tag | Nexus production deployment |
| `nexus-hub-deploy-techbd.yml` | — | Nexus hub deployment |
| `nexus-sandbox-tag-update.yml` | — | Sandbox tag update |
| `Nexus-tag-pr.yml` | — | Nexus tag PR creation |

### Database Workflows

| Workflow | Description |
|---|---|
| `udi-devl-rds.yml` | Development RDS migrations |
| `udi-phi-qa-rds.yml` | PHI QA RDS migrations |
| `udi-phi-use1-prd-rds.yml` | Production RDS migrations |
| `udi-phi-use1-sandbox-rds.yml` | Sandbox RDS migrations |

### Other Workflows

| Workflow | Description |
|---|---|
| `check-ses.yml` | SES (email) health check |
| `notify-qe-stat.yaml` | QE status notifications |
| `nyec-ig-version-check.yml` | NYEC IG version monitoring |
| `techbd-qualityfolio.yml` | Quality metrics dashboard |

### Deployment Pattern

Deployments to infrastructure use a PR-based GitOps approach:

1. A release is published with a semver tag (e.g., `0.1160.0`)
2. CI clones the `infrastructure-prime` repository
3. CI updates the environment `.env` file (`TAG=<version>`)
4. CI creates a branch and PR against `infrastructure-prime`'s `main`
5. PR is merged to trigger the actual infrastructure deployment

---

## 14. Configuration & Environment Variables

### Spring Profiles

| Profile | Usage |
|---|---|
| `localopen` | Local dev — no authentication |
| `sandbox` | Local dev with OAuth2 authentication |
| `devl` | Development environment |
| `phiqa` | PHI QA environment |
| `stage` | Staging environment |
| `phiprod` | PHI production environment |

Set the active profile via:

```bash
export SPRING_PROFILES_ACTIVE=sandbox
```

### Required Environment Variables

#### Database

| Variable | Description |
|---|---|
| `{PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_URL` | Primary DB JDBC URL |
| `{PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_USERNAME` | Primary DB username |
| `{PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_PASSWORD` | Primary DB password |
| `{PROFILE}_TECHBD_UDI_DS_READER_JDBC_URL` | Read replica JDBC URL |
| `{PROFILE}_TECHBD_UDI_DS_READER_JDBC_USERNAME` | Read replica username |
| `{PROFILE}_TECHBD_UDI_DS_READER_JDBC_PASSWORD` | Read replica password |
| `ORG_TECHBD_DB_READ_WRITE_SPLIT_ENABLED` | Enable read/write split (`false`) |

#### OAuth2 — FusionAuth

| Variable | Description |
|---|---|
| `SPRING_SECURITY_OAUTH2_FUSIONAUTH_CLIENT_ID` | FusionAuth client ID |
| `SPRING_SECURITY_OAUTH2_FUSIONAUTH_CLIENT_SECRET` | FusionAuth client secret |
| `SPRING_SECURITY_OAUTH2_FUSIONAUTH_REDIRECT_URI` | OAuth2 redirect URI |
| `SPRING_SECURITY_OAUTH2_LOGOUT_REDIRECT_URI` | Post-logout redirect URI |
| `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_FUSIONAUTH_AUTHORIZATION_URI` | FusionAuth authorization endpoint |
| `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_FUSIONAUTH_TOKEN_URI` | FusionAuth token endpoint |
| `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_FUSIONAUTH_USER_INFO_URI` | FusionAuth user info endpoint |
| `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_FUSIONAUTH_JWK_SET_URI` | FusionAuth JWK set URI |
| `ORG_TECHBD_SERVICE_HTTP_FUSIONAUTH_BASE_URL` | FusionAuth base URL |
| `AUTH_PROVIDER` | `github` or `fusionauth` |

#### OAuth2 — GitHub

| Variable | Description |
|---|---|
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID` | GitHub App client ID |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET` | GitHub App client secret |
| `ORG_TECHBD_SERVICE_HTTP_GITHUB_AUTHZ_USERS_YAML_URL` | Authorized users YAML URL |
| `ORG_TECHBD_SERVICE_HTTP_GITHUB_API_AUTHN_TOKEN` | GitHub API auth token |

#### AWS (nexus-ingestion-api)

| Variable | Description |
|---|---|
| `ORG_TECHBD_SERVICE_AWS_REGION` | AWS region (`us-east-1`) |
| `ORG_TECHBD_SERVICE_SECRET_NAME` | AWS Secrets Manager secret name |
| `AWS_SQS_QUEUE_NAME` | SQS FIFO queue URL |
| `AWS_S3_BUCKET_NAME` | Primary S3 bucket |
| `AWS_S3_METADATA_BUCKET_NAME` | Metadata S3 bucket |
| `HOLD_S3_BUCKET_NAME` | Data hold S3 bucket |

#### Application

| Variable | Description |
|---|---|
| `TECHBD_HUB_PRIME_FHIR_API_BASE_URL` | FHIR API base URL |
| `TECHBD_HUB_PRIME_FHIR_UI_BASE_URL` | UI base URL |
| `TECHBD_BASE_FHIR_URL` | Default FHIR base URL for CSV-to-FHIR generation |
| `TECHBD_DATA_LEDGER_API_URL` | Data Ledger API URL |
| `TECHBD_DATA_LEDGER_TRACKING_ENABLED` | Enable Data Ledger tracking (`false`) |
| `TECHBD_DATA_LEDGER_DIAGNOSTICS_ENABLED` | Enable Data Ledger diagnostics (`true`) |
| `TECHBD_PYTHON_SCRIPT_PATH` | Path to Python validation scripts |
| `TECHBD_OPEN_OBSERVE_URL` | OpenObserve/OpenTelemetry collector URL |
| `TECHBD_OPEN_OBSERVE_PASSWORD` | OpenObserve Basic auth password (Base64) |
| `TECHBD_OPEN_OBSERVE_STREAM_NAME` | OpenObserve stream name |
| `TECHBD_ALLOWED_HOSTS` | Allowed hostnames for HSTS |

#### Sandbox / Dev Helpers

| Variable | Description |
|---|---|
| `SANDBOX_USE_DUMMY_USER` | Use dummy user for sandbox profile (`true`) |
| `SANDBOX_GITHUB_ID` | Dummy GitHub user ID |
| `SANDBOX_USER_NAME` | Dummy user display name |
| `SANDBOX_TENANT_ID` | Dummy tenant ID |

#### CSV Async Processing

| Variable | Description |
|---|---|
| `ORG_TECHBD_CSV_ASYNC_EXECUTOR_CORE_POOL_SIZE` | Core thread pool size (`20`) |
| `ORG_TECHBD_CSV_ASYNC_EXECUTOR_MAX_POOL_SIZE` | Max thread pool size (`50`) |
| `ORG_TECHBD_CSV_ASYNC_EXECUTOR_QUEUE_CAPACITY` | Task queue capacity (`200`) |
| `ORG_TECHBD_CSV_ASYNC_EXECUTOR_AWAIT_TERMINATION_SECONDS` | Shutdown timeout (`30`) |

---

## 15. Local Development Setup

### Prerequisites

- Java 21 LTS (Amazon Corretto recommended)
- Apache Maven 3.8+
- PostgreSQL 16 (or access to Aurora)
- Deno (for `udi-prime` schema generation)
- Node.js 20+ + npm (for `api-automation`)
- Python 3 (for CSV validation scripts)
- `direnv` (recommended for environment variable management)
- Docker (optional, for containerized services)

### Step-by-Step Setup

```bash
# 1. Clone the repository
git clone https://github.com/tech-by-design/polyglot-prime.git
cd polyglot-prime

# 2. Configure environment variables
cp .envrc.example .envrc
# Edit .envrc with your credentials and settings
direnv allow   # or: source .envrc

# 3. Build all Maven modules
mvn clean install

# 4. (Optional) Generate UDI database schema and jOOQ classes
cd udi-prime
./udictl.ts ic omnibus-fresh --deploy-jar
cd ..

# 5. Run hub-prime locally
cd hub-prime
mvn spring-boot:run
# Visit: http://localhost:8080

# 6. (Optional) Run with no authentication for development
export SPRING_PROFILES_ACTIVE=localopen
mvn spring-boot:run
```

### Running Individual Services

```bash
# fhir-validation-service
cd fhir-validation-service
mvn spring-boot:run

# csv-service
cd csv-service
mvn spring-boot:run

# nexus-ingestion-api
cd nexus-ingestion-api
mvn spring-boot:run
```

### Running Tests

```bash
# Unit tests for all modules
mvn test

# Unit tests for a specific module
cd hub-prime
mvn test

# API automation tests
cd api-automation
npm install
npx playwright test

# Database tests
cd udi-prime
./udictl.ts test
```

### IDE Setup

The project is optimized for IntelliJ IDEA with the standard Maven project layout. After importing, ensure:

1. Set Project SDK to Java 21
2. Enable annotation processing (for Lombok)
3. Configure `SPRING_PROFILES_ACTIVE=sandbox` in run configurations
4. Set all required environment variables in run configurations or source `.envrc` before launching IDE

---

## 16. Deployment

### Environment Profiles

| Profile | Environment | Notes |
|---|---|---|
| `sandbox` | Local / Sandbox | FusionAuth OAuth2, dummy user support |
| `devl` | Development | AWS Dev environment |
| `phiqa` | PHI QA | AWS PHI QA environment |
| `stage` | Staging | AWS Staging environment |
| `phiprod` | Production | AWS PHI Production environment |

### Maven Build for Deployment

```bash
# Production build (skip tests for speed in CI)
mvn clean package -DskipTests -DskipUTs -DskipITs

# Build with all checks
mvn clean verify site
```

### Docker

Archive Dockerfile and docker-compose available in:

```
support/archive/containers/sandbox/hub-prime/
├── Dockerfile
└── docker-compose.yml
```

### GitOps Deployment

Production deployments follow a GitOps pattern:
1. Tag a release in this repository (e.g., `0.1160.0`)
2. GitHub Actions workflow creates a PR in `infrastructure-prime`
3. The PR updates the environment `.env` file (`TAG=0.1160.0`)
4. Merging the PR triggers the infrastructure deployment

### Observability in Production

The application publishes metrics and traces to OpenObserve (OpenTelemetry-compatible):

```yaml
management:
  tracing:
    sampling:
      probability: 1.0    # 100% trace sampling
  otlp:
    metrics:
      export:
        url: ${TECHBD_OPEN_OBSERVE_URL}/api/${TECHBD_OPEN_OBSERVE_STREAM_NAME}/v1/metrics
        step: 1m
    tracing:
      export:
        endpoint: ${TECHBD_OPEN_OBSERVE_URL}/api/${TECHBD_OPEN_OBSERVE_STREAM_NAME}/v1/traces
```

---

*This documentation was generated by analyzing the polyglot-prime codebase. For the most up-to-date API documentation, refer to the Swagger UI at `/docs/api/interactive/index.html` when the application is running.*
