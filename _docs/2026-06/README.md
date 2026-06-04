# Polyglot Prime — Documentation

Welcome to the documentation for the **Tech by Design Polyglot Prime** monorepo. This repository is a multi-module healthcare data integration platform for processing HRSN (Health-Related Social Needs) screening data for the New York State Medicaid program.

**Version**: 0.1167.0 | **Java**: 21 LTS | **Spring Boot**: 3.3.3

---

## Architecture

Start here for a platform-wide understanding before diving into individual modules.

| Document | Description |
|----------|-------------|
| [Architecture Overview](architecture.md) | High-level design, data flows, technology stack, dependency graph, and key design decisions |

---

## Spring Boot Services

Runnable applications — each deploys as an independent Spring Boot JAR.

| Module | Description | Doc |
|--------|-------------|-----|
| **hub-prime** | Primary FHIR API hub and operator UI. Accepts FHIR R4 Bundles and CSV submissions via REST. Thymeleaf/HTMX admin interface. | [hub-prime.md](hub-prime.md) |
| **nexus-ingestion-api** | Multi-protocol ingestion service for HL7 v2.x (MLLP/TCP) and C-CDA (SOAP/IHE XDS). Routes payloads to AWS S3 and SQS. | [nexus-ingestion-api.md](nexus-ingestion-api.md) |
| **csv-service** | Standalone CSV-to-FHIR conversion service. Accepts ZIP files containing HRSN screening CSV files and converts them to FHIR R4 Bundles. | [csv-service.md](csv-service.md) |
| **fhir-validation-service** | Dedicated FHIR R4 validation service. Loads SHINNY and US Core IGs at startup; exposes a `/Bundle/$validate` endpoint used by other services. | [fhir-validation-service.md](fhir-validation-service.md) |

---

## Shared Libraries

JAR libraries included as Maven dependencies by the services above. Not runnable on their own.

| Module | Description | Doc |
|--------|-------------|-----|
| **hub-core-lib** | Core library for Hub services. Contains CSV-to-FHIR converters, FHIR orchestration engine, HL7/CCDA services, database config, and utilities. | [hub-core-lib.md](hub-core-lib.md) |
| **nexus-core-lib** | Core library for Nexus services. Contains shared configuration, constants, Data Ledger API client, and utilities. | [nexus-core-lib.md](nexus-core-lib.md) |

---

## Database Layer

| Module | Description | Doc |
|--------|-------------|-----|
| **udi-prime** | PostgreSQL schema (Unified Data Intake), migration scripts, pgTAP database tests, and jOOQ code generation tooling. Built with Deno/TypeScript. | [udi-prime.md](udi-prime.md) |

---

## Testing

| Module | Description | Doc |
|--------|-------------|-----|
| **api-automation** | TypeScript/Playwright integration tests for FHIR REST API endpoints. Covers positive (valid bundles accepted) and negative (invalid bundles rejected with correct errors) scenarios. | [api-automation.md](api-automation.md) |
| **test-automation** | Environment smoke test bundles (FHIR, HL7, CCDA, CSV) for post-deployment verification across Development, Staging, PHI QA, and Production environments. | [test-automation.md](test-automation.md) |

---

## Integration & Operations

| Module | Description | Doc |
|--------|-------------|-----|
| **integration-artifacts** | Mirth Connect channel definitions, XSLT stylesheets, routing rules, global scripts, lookup tables, and templates for HL7 v2.x, CCDA, FHIR, and CSV message transformation. | [integration-artifacts.md](integration-artifacts.md) |
| **support** | Technical specifications, release notes, QA documentation, manual test cases, utility scripts, and IG version management tooling. | [support.md](support.md) |

---

## Quick Reference

### Supported Data Formats

| Format | Ingest Via | Converts To |
|--------|-----------|-------------|
| FHIR R4 Bundle | `hub-prime` REST | Stored as-is |
| CSV (HRSN screening) | `csv-service` or `hub-prime` | FHIR R4 Bundle |
| HL7 v2.x | `nexus-ingestion-api` MLLP/TCP | FHIR R4 Bundle (via Mirth) |
| C-CDA | `nexus-ingestion-api` SOAP | FHIR R4 Bundle (via XSLT) |

### Environments

| Profile | Description | PHI |
|---------|-------------|-----|
| `sandbox` | Local development | No |
| `devl` | Shared development | No |
| `stage` | Pre-production staging | Synthetic |
| `phiqa` | PHI-regulated QA | Yes |
| `phiprod` | Production | Yes |

### Getting Started

```bash
# Clone and configure
git clone https://github.com/tech-by-design/polyglot-prime.git
cd polyglot-prime
cp .envrc.example .envrc
vi .envrc   # set secrets and environment variables

# Build all modules
mvn clean install

# Run hub-prime
cd hub-prime
mvn spring-boot:run
# → http://localhost:8080
```

### Environment Variables (Common)

| Variable | Description |
|----------|-------------|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile (`sandbox`, `devl`, `stage`, etc.) |
| `SPRING_SECURITY_OAUTH2_FUSIONAUTH_CLIENT_ID` | FusionAuth OAuth2 client ID |
| `SPRING_SECURITY_OAUTH2_FUSIONAUTH_CLIENT_SECRET` | FusionAuth OAuth2 client secret |
| `${PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_URL` | PostgreSQL JDBC URL (profile-prefixed) |
| `${PROFILE}_TECHBD_UDI_DS_PRIME_USERNAME` | Database username (profile-prefixed) |
| `${PROFILE}_TECHBD_UDI_DS_PRIME_PASSWORD` | Database password (profile-prefixed) |
| `AWS_S3_BUCKET_NAME` | S3 bucket for raw payload storage |
| `AWS_SQS_QUEUE_NAME` | SQS FIFO queue URL |
| `ORG_TECHBD_SERVICE_AWS_REGION` | AWS region |

---

## Module Dependency Graph

```
hub-prime ──────────────────► hub-core-lib
                                    │
nexus-ingestion-api ────────► nexus-core-lib
                                    │
csv-service ─────────────────────────────────► fhir-validation-service (HTTP)

All services ────────────────────────────────► udi-prime (PostgreSQL schema)
```
