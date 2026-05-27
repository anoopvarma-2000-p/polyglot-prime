# Architecture Overview

## 1. System Purpose

Polyglot Prime is a **healthcare data-exchange hub** that acts as the integration
layer between community-based health-care organisations (partners/tenants) and
the **SHIN-NY Data Lake** operated by the New York State HRSN (Health-Related
Social Needs) programme.  The platform:

1. Accepts structured health data in multiple formats (FHIR R4 JSON, HL7v2,
   CCDA XML, CSV flat-files)
2. Validates payloads against the [SHIN-NY FHIR Implementation Guide](https://shinny.org/ImplementationGuide/HRSN)
3. Persists every interaction in a PostgreSQL data vault for auditing and replay
4. Forwards validated bundles to the downstream SHIN-NY scoring/datalake API
5. Exposes a browser-based operations dashboard for tenants and Tech by Design staff

---

## 2. High-Level Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       Partner / Tenant Systems                           │
│  FHIR Clients  │  HL7v2 / MLLP  │  SOAP/XDS  │  CSV flat-files          │
└───────┬────────┴────────┬────────┴─────┬──────┴──────┬───────────────────┘
        │                 │              │             │
        ▼                 ▼              │             ▼
 ┌─────────────┐  ┌──────────────────┐  │   ┌──────────────────┐
 │  hub-prime  │  │ nexus-ingestion  │  │   │   csv-service    │
 │  (FHIR hub) │  │      -api        │◄─┘   │  (flat-file hub) │
 └──────┬──────┘  └────────┬─────────┘      └────────┬─────────┘
        │                  │                         │
        │          ┌───────▼───────┐                 │
        │          │  AWS SQS FIFO │                 │
        │          │    Queue      │                 │
        │          └───────┬───────┘                 │
        │                  │                         │
        ▼                  ▼                         ▼
 ┌──────────────────────────────────────────────────────────────┐
 │                    hub-core-lib                               │
 │   FHIRService  │  CsvService  │  OrchestrationEngine         │
 │   ValidationEngine (HAPI)     │  jOOQ DAL                    │
 └──────────────────────┬───────────────────────────────────────┘
                        │
          ┌─────────────▼───────────────┐
          │  fhir-validation-service     │
          │  (optional standalone svc)   │
          └─────────────┬───────────────┘
                        │
          ┌─────────────▼───────────────┐
          │       udi-prime              │
          │  PostgreSQL data vault       │
          │  (techbd_udi_ingress schema) │
          └─────────────┬───────────────┘
                        │
          ┌─────────────▼───────────────┐
          │   SHIN-NY Data Lake / API    │
          │   (scoring engine)           │
          └─────────────────────────────┘
```

---

## 3. Modules and Their Roles

| Module | Technology | Role |
|--------|-----------|------|
| **hub-prime** | Spring Boot 3.3, Thymeleaf, HTMX | Primary FHIR ingestion hub and operations UI |
| **fhir-validation-service** | Spring Boot 3.3, HAPI FHIR | Standalone FHIR validation microservice |
| **csv-service** | Spring Boot 3.3 | CSV / ZIP flat-file ingestion and FHIR conversion |
| **nexus-ingestion-api** | Spring Boot 3.3 | Multi-protocol ingestion gateway (HTTP, MLLP, SOAP) |
| **hub-core-lib** | Java 21 library | Shared FHIR orchestration, CSV processing, jOOQ DAL |
| **nexus-core-lib** | Java 21 library | Nexus-specific shared utilities |
| **udi-prime** | Deno, TypeScript, PostgreSQL | Database schema management and jOOQ code generation |
| **api-automation** | Node.js, Playwright, TypeScript | API test automation framework |
| **test-automation** | HTTP-based smoke tests | Multi-environment smoke test suites |
| **integration-artifacts** | Mirth Connect / Nexus channels | HL7v2, CCDA, FHIR, flat-file channel configs |
| **support** | Various | Specs, release notes, quality dashboards, test cases |

---

## 4. Data Ingestion Flows

### 4.1 FHIR Bundle Ingestion (hub-prime)

```
HTTP POST /Bundle
    │
    ├─► Security filters (tenant-id validation, mTLS, API key)
    │
    ├─► FhirController.validateBundleAndForward()
    │       │
    │       ├─► FHIRService.processBundle()
    │       │       │
    │       │       ├─► OrchestrationEngine (HAPI FHIR validation)
    │       │       │       └─► Validates against SHIN-NY IG packages
    │       │       │
    │       │       ├─► Persist interaction → PostgreSQL (hub, satellite tables)
    │       │       │
    │       │       └─► Forward to SHIN-NY Data Lake (mTLS or API key)
    │       │
    │       └─► Return OperationOutcome JSON
    │
    └─► HTTP POST /Bundle/$validate  (validate-only, no forward)
```

### 4.2 CSV Flat-File Ingestion (csv-service / hub-prime)

```
HTTP POST /flatfile/csv/Bundle/$validate  (multipart ZIP)
    │
    ├─► CsvController → CsvService.validateCsvFile()
    │
    ├─► CsvOrchestrationEngine.OrchestrationSession.validate()
    │       │
    │       ├─► Python validation script (frictionless / custom IG checks)
    │       ├─► Convert CSV rows → FHIR Bundle JSON
    │       └─► HAPI FHIR validation of generated bundles
    │
    ├─► Persist ZIP interaction + per-file results → PostgreSQL
    │
    └─► Optional async forward to SHIN-NY Data Lake
```

### 4.3 Nexus Multi-Protocol Ingestion (nexus-ingestion-api)

```
TCP / HTTP / SOAP connection
    │
    ├─► Protocol detection (SOAP namespace, MLLP start-byte, HTTP path)
    ├─► PROXY protocol header → derive destination port
    ├─► PortResolverService → match PortEntry from S3 / local config
    ├─► mTLS validation (if configured for port)
    ├─► Write payload → S3 (date-partitioned path)
    ├─► Write metadata JSON → S3
    ├─► Enqueue message → SQS FIFO queue
    └─► Return ACK / SOAP response / JSON with interactionId
```

---

## 5. Database Layer (udi-prime)

The PostgreSQL schema follows a **Data Vault 2.0** pattern, managed by the
`udi-prime` TypeScript/Deno layer using the
[SQL Aide](https://github.com/netspective-labs/sql-aide) library.

### Key Schemas

| Schema | Purpose |
|--------|---------|
| `techbd_udi_ingress` | Primary interaction storage — hubs, satellites, links |
| `techbd_udi_assurance` | pgTAP test fixtures and results |
| `techbd_udi_diagnostics` | Diagnostic logs and exception tracking |
| `info_schema_lifecycle` | ISLM migration metadata |

### Core Tables (techbd_udi_ingress)

| Table | Type | Description |
|-------|------|-------------|
| `hub_interaction` | Hub | Unique interaction IDs (all data types) |
| `hub_fhir_bundle` | Hub | Unique FHIR bundle identifiers |
| `hub_uniform_resource` | Hub | Uniform resource tracking |
| `sat_interaction_http_request` | Satellite | HTTP request metadata per interaction |
| `sat_interaction_fhir_request` | Satellite | FHIR-specific request details |
| `sat_interaction_fhir_validation_issue` | Satellite | FHIR validation issues |
| `sat_interaction_fhir_screening_patient` | Satellite | Extracted patient demographics |
| `sat_interaction_flat_file_csv_request` | Satellite | CSV upload details |
| `sat_interaction_hl7_request` | Satellite | HL7v2 interaction metadata |
| `sat_interaction_ccda_request` | Satellite | CCDA interaction metadata |
| `sat_interaction_file_exchange` | Satellite | File exchange tracking |
| `hub_exception` | Hub | System exception tracking |
| `sat_diagnostic_log` | Satellite | Diagnostic log entries |

Migrations are managed with **ISLM (Information Schema Lifecycle Management)**
and executed via `./udictl.ts ic omnibus-fresh` in the `udi-prime` directory.

---

## 6. Security Architecture

### Authentication & Authorisation

- **Tenant ID header** (`X-TechBD-Tenant-ID`) — mandatory on all ingestion
  endpoints; identifies the submitting partner organisation.
- **mTLS (mutual TLS)** — supported on `hub-prime` (forwarding to SHIN-NY) and
  on `nexus-ingestion-api` (inbound MLLP/SOAP connections).  Certificate chains
  are retrieved from AWS Secrets Manager or local classpath resources.
- **API Key** — alternative to mTLS for forwarding to SHIN-NY Data Lake,
  configured via AWS Secrets Manager (`WithApiKeyAuth.apiKeySecretName`).
- **Spring Security** — applied in `hub-prime` for the browser dashboard
  (OAuth2 / form login depending on profile).

### Transport Security

- All public endpoints are expected to be behind an AWS ALB with TLS
  termination (or mTLS pass-through for MLLP ports).
- IP whitelisting (`whitelistIps` in `PortEntry`) is enforced by
  `nexus-ingestion-api` at the application layer.

---

## 7. Environments

| Profile | Description |
|---------|-------------|
| `devl` | Local development |
| `sandbox` | Integration sandbox |
| `phiqa` | PHI Quality Assurance |
| `stage` | Pre-production staging |
| `phiprod` | PHI Production |

Active profile is selected via the `SPRING_PROFILES_ACTIVE` environment variable.
Database connection strings follow the convention
`${SPRING_PROFILES_ACTIVE}_TECHBD_UDI_DS_PRIME_JDBC_URL`.

---

## 8. Observability

- **Spring Boot Actuator** — health, info, metrics, Prometheus endpoints
  exposed under `/actuator` (authorisation required for sensitive endpoints).
- **OpenTelemetry** — instrumentation hooks for distributed tracing.
- **CloudWatch** — nexus-ingestion-api emits protocol-specific metrics and
  logs using the `interactionId` as the correlation key.
- **Interaction IDs** — every inbound request receives a UUID
  (`interactionId`) that is propagated through logs, database records, S3
  object paths, and SQS message attributes for end-to-end traceability.
