# Architecture Overview

## Platform Summary

**Tech by Design Polyglot Prime** is a healthcare data integration platform purpose-built for processing Health-Related Social Needs (HRSN) screening data for the New York State (NYEC) Medicaid program. It receives healthcare data in multiple formats (FHIR R4, HL7 v2.x, C-CDA, CSV), validates it against the SHINNY Implementation Guide, and makes it available for downstream analytics and quality reporting.

---

## High-Level Architecture

```
External Submitters
        │
        │  REST (FHIR R4, CSV)
        │  MLLP / TCP (HL7 v2.x)
        │  SOAP/HTTP (C-CDA / XDS)
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│                    Ingestion Layer                         │
│                                                           │
│  ┌──────────────┐     ┌──────────────────────────────┐   │
│  │  hub-prime   │     │   nexus-ingestion-api         │   │
│  │  (REST/FHIR/ │     │   (MLLP / TCP / SOAP)        │   │
│  │   CSV)       │     │                              │   │
│  └──────┬───────┘     └───────────┬──────────────────┘   │
│         │                         │                       │
│         │                         │ AWS S3 + SQS          │
└─────────┼─────────────────────────┼───────────────────────┘
          │                         │
          ▼                         ▼
┌──────────────────┐   ┌────────────────────────────────────┐
│  csv-service     │   │  integration-artifacts             │
│  (CSV → FHIR)    │   │  (Mirth channels: HL7/CCDA → FHIR) │
└────────┬─────────┘   └────────────────┬───────────────────┘
         │                              │
         └──────────────┬───────────────┘
                        │
                        ▼
           ┌────────────────────────┐
           │ fhir-validation-service│
           │ (HAPI FHIR + SHINNY IG)│
           └────────────┬───────────┘
                        │
                        ▼
           ┌────────────────────────┐
           │     udi-prime          │
           │   (PostgreSQL 16)      │
           │  Interactions, outcomes│
           │  jOOQ generated models │
           └────────────────────────┘
```

---

## Module Taxonomy

### Runnable Services (Spring Boot)

| Module | Protocol(s) | Port |
|--------|------------|------|
| [hub-prime](hub-prime.md) | REST (HTTP/S) | 8080 |
| [nexus-ingestion-api](nexus-ingestion-api.md) | MLLP, TCP, SOAP/HTTP | 8080 + dynamic TCP ports |
| [csv-service](csv-service.md) | REST (HTTP/S) | 8080 |
| [fhir-validation-service](fhir-validation-service.md) | REST (HTTP/S) | 8080 |

### Shared Libraries (JAR, not runnable)

| Module | Consumed By |
|--------|-------------|
| [hub-core-lib](hub-core-lib.md) | `hub-prime` |
| [nexus-core-lib](nexus-core-lib.md) | `nexus-ingestion-api` |

### Database Layer

| Module | Technology |
|--------|-----------|
| [udi-prime](udi-prime.md) | PostgreSQL 16, Deno/TypeScript, pgTAP, jOOQ |

### Testing

| Module | Type |
|--------|------|
| [api-automation](api-automation.md) | Playwright TypeScript integration tests |
| [test-automation](test-automation.md) | Smoke test bundles per environment |

### Configuration & Integration

| Module | Purpose |
|--------|---------|
| [integration-artifacts](integration-artifacts.md) | Mirth Connect channels, XSLT, scripts |
| [support](support.md) | Specs, release notes, QA docs, scripts |

---

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 LTS, TypeScript (Deno / Node.js) |
| Application Framework | Spring Boot 3.3.3 |
| Build | Maven (multi-module), Deno |
| FHIR Engine | HAPI FHIR 8.2.2 |
| HL7 v2.x | Apache Camel 4.10.0 (MLLP), HAPI HL7 v2.7/v2.8 |
| CCDA | XSLT (Mirth Connect), IPF 4.1.0 |
| Database | PostgreSQL 16 |
| ORM / SQL | Hibernate 6, jOOQ 3.19 |
| Connection Pool | HikariCP |
| UI | Thymeleaf 3, HTMX 2.0 |
| Security | Spring Security, OAuth2 OIDC (FusionAuth) |
| Cloud | AWS S3, SQS, Secrets Manager |
| Observability | OpenTelemetry, Micrometer |
| Feature Flags | Togglz |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Testing | JUnit 5, AssertJ, Playwright, pgTAP |
| Containers | Docker |

---

## Data Flow: FHIR Bundle Submission

```
1. Submitter POSTs FHIR Bundle JSON to hub-prime /Bundle
2. InteractionsFilter captures raw request (headers + body)
3. FhirController delegates to FHIRService (in hub-core-lib)
4. OrchestrationEngine runs:
   a. PrePopulateSupport: normalize bundle
   b. FhirBundleValidator: validate against SHINNY IG
   c. PostPopulateSupport: enrich outcome
5. Interaction record written to PostgreSQL via jOOQ
6. OperationOutcome returned to submitter
7. UI operators can review interaction in hub-prime console
```

---

## Data Flow: CSV Submission

```
1. Submitter POSTs ZIP of 4 CSV files to csv-service /flatfile/csv/Bundle
2. CsvService validates ZIP structure and identifies file types
3. CsvOrchestrationEngine coordinates FileProcessor instances
4. CsvBundleProcessorService + CsvToFhirConverter build FHIR Bundle
5. FhirValidationServiceClient calls fhir-validation-service for validation
6. Outcome stored in PostgreSQL
7. FHIR Bundle + OperationOutcome returned to submitter
```

---

## Data Flow: HL7 v2.x Ingestion

```
1. Sending system connects to nexus-ingestion-api via MLLP/TCP
2. Apache Camel MLLP component receives HL7 message
3. MessageProcessorService runs:
   a. MetadataBuilderService: extract tenant, message type, source IP
   b. S3UploadStep: store raw HL7 payload in AWS S3
   c. SqsPublishStep: publish message reference to SQS FIFO queue
4. AcknowledgementService generates HL7 ACK (AA) response
5. AWS SQS consumer (integration-artifacts) picks up message
6. Mirth Connect channel transforms HL7 → FHIR → forwards to hub-prime
```

---

## Security Model

| Boundary | Mechanism |
|----------|-----------|
| External API callers | Bearer token (API key) |
| Admin UI | OAuth2 OIDC via FusionAuth |
| GitHub integration | GitHub OAuth |
| Service-to-service | Network-level controls (VPC / security groups) |
| Secrets | AWS Secrets Manager |
| Host Header attacks | `HostHeaderValidationFilter` in each service |

---

## Multi-Environment Strategy

All services use Spring Boot profiles with an environment-variable-driven configuration pattern:

```
SPRING_PROFILES_ACTIVE=devl
devl_TECHBD_UDI_DS_PRIME_JDBC_URL=jdbc:postgresql://...
```

| Environment | Profile | PHI Data |
|-------------|---------|---------|
| Local / Sandbox | `sandbox` | No |
| Development | `devl` | No |
| Staging | `stage` | Synthetic |
| PHI QA | `phiqa` | Yes |
| PHI Production | `phiprod` | Yes |

---

## Dependency Graph

```
hub-prime
    └── hub-core-lib
            └── (jOOQ generated classes from udi-prime)

nexus-ingestion-api
    └── nexus-core-lib
            └── (jOOQ generated classes from udi-prime)

csv-service
    └── (independent, mirrors converters from hub-core-lib)
    └── calls fhir-validation-service (HTTP)

fhir-validation-service
    └── (standalone, no Java lib dependencies)

udi-prime
    └── generates jOOQ classes → used by hub-core-lib, nexus-core-lib
```

---

## Key Design Decisions

1. **Modular library separation**: `hub-core-lib` and `nexus-core-lib` are separate so each service family can evolve independently. Merging them would create unwanted cross-family coupling.

2. **Standalone FHIR validator**: `fhir-validation-service` is deployed as its own service so IG package loading (which is slow) happens once, not per-request. Other services call it via HTTP.

3. **CSV service as a separate deployment**: `csv-service` handles CSV conversion independently of `hub-prime`, enabling independent scaling and deployment for CSV-heavy workloads.

4. **Protocol-specialized ingestion**: `nexus-ingestion-api` handles non-HTTP protocols (MLLP, TCP, SOAP) separately from `hub-prime`, which is purely REST. This prevents protocol complexity from polluting the main FHIR API.

5. **Data Vault schema**: The `udi-prime` PostgreSQL schema uses Data Vault 2.0 patterns to ensure full audit history, enabling temporal queries and regulatory compliance without losing historical data.

6. **jOOQ over pure JPA**: jOOQ is preferred for query-heavy operations because it compiles SQL at build time (type safety) and avoids N+1 issues common in ORM-heavy code.

7. **HTMX for UI**: HTMX enables a progressive, server-side-rendered UI without a full SPA framework, reducing frontend complexity while delivering interactive behavior.
