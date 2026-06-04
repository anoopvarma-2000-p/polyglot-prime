# csv-service

## Overview

`csv-service` is a standalone Spring Boot microservice dedicated to receiving, processing, and converting CSV-formatted HRSN (Health-Related Social Needs) screening data into FHIR R4 Bundles. It validates the resulting bundles against the SHINNY Implementation Guide and forwards them to downstream systems.

- **Artifact ID**: `csv-service`
- **Group ID**: `org.techbd`
- **Version**: `0.1167.0`
- **Packaging**: JAR (Spring Boot executable)
- **Java**: 21 LTS
- **Spring Boot**: 3.3.3

---

## Architecture

`csv-service` is the standalone deployment of the CSV-to-FHIR conversion pipeline. The same conversion logic also exists in `hub-core-lib` (used by `hub-prime`). This service allows the CSV pipeline to be scaled, deployed, and secured independently.

```
csv-service/
└── src/main/java/
    └── org/techbd/csv/
        ├── CsvApplication.java         # @SpringBootApplication
        ├── config/           # App configuration, constants, security
        ├── controller/       # REST controllers, health, exception handler
        ├── converters/       # 11 CSV-to-FHIR resource converters
        ├── feature/          # Feature toggles (Togglz)
        ├── model/            # CSV domain models
        ├── service/          # Orchestration, processing, FHIR validation client
        │   ├── engine/       # Orchestration engine and file processor
        │   └── vfs/          # Virtual File System ingress
        └── util/             # CSV utility constants and helpers
```

---

## Main Entry Point

| Class | Description |
|-------|-------------|
| `org.techbd.csv.CsvApplication` | `@SpringBootApplication` — application bootstrap |
| `org.techbd.csv.config.AppConfig` | Primary `@Configuration` class |

---

## Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `spring-boot-starter-web` | 3.3.3 | REST API and Tomcat |
| `spring-boot-starter-security` | 3.3.3 | HTTP security |
| `spring-boot-starter-actuator` | 3.3.3 | Health / metrics |
| `spring-boot-starter-webflux` | 3.3.3 | Reactive HTTP client (for FHIR validator calls) |
| `hapi-fhir-base` | 8.2.2 | HAPI FHIR core |
| `hapi-fhir-structures-r4` | 8.2.2 | FHIR R4 resource models |
| `hapi-fhir-validation` | 8.2.2 | FHIR validation engine |
| `hapi-fhir-validation-resources-r4` | 8.2.2 | R4 validation profiles |
| `hapi-fhir-caching-caffeine` | 8.2.2 | Validation result caching |
| `spring-boot-starter-jooq` | 3.3.1 | Type-safe SQL |
| `postgresql` | — | PostgreSQL JDBC driver |
| `opencsv` | 5.8 | CSV parsing |
| `jackson-databind` | — | JSON processing |
| `springdoc-openapi-starter-webmvc-ui` | — | OpenAPI / Swagger UI |
| `opentelemetry-spring-boot-starter` | — | Distributed tracing |
| `micrometer-tracing` | — | Metrics tracing |
| `togglz-spring-boot-starter` | — | Feature toggles |

---

## REST API Endpoints

### CSV Ingestion (`CsvController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/flatfile/csv/Bundle` | Upload a ZIP of CSV files; convert and submit as FHIR Bundle |
| `POST` | `/flatfile/csv/Bundle/$validate` | Validate CSV ZIP contents; returns validation outcome without storing |
| `GET` | `/flatfile/csv/Bundle/{id}` | Retrieve the outcome of a previous CSV submission |

### Health Check (`HealthCheckController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/actuator/health` | Standard Spring Boot health endpoint |
| `GET` | `/health` | Application-level health summary |

### Feature Toggles (`FeatureToggleController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/features` | List all feature flags and their current state |
| `POST` | `/features/{name}` | Toggle a specific feature flag |

---

## CSV-to-FHIR Converters (`org.techbd.csv.converters`)

The service converts multi-file CSV submissions into a single FHIR R4 Bundle. Each CSV file type maps to one or more FHIR resources via dedicated converter classes:

| Converter Class | FHIR Resource Produced | Source CSV |
|----------------|----------------------|------------|
| `CsvToFhirConverter` | Bundle (orchestrator) | All files |
| `BundleConverter` | Bundle metadata | Profile/admin data |
| `PatientConverter` | Patient | `DEMOGRAPHIC_DATA` |
| `OrganizationConverter` | Organization | `QE_ADMIN_DATA` |
| `EncounterConverter` | Encounter | `SCREENING_PROFILE_DATA` |
| `ConsentConverter` | Consent | `SCREENING_PROFILE_DATA` |
| `ProcedureConverter` | Procedure | `SCREENING_OBSERVATION_DATA` |
| `ScreeningResponseObservationConverter` | Observation (HRSN screening) | `SCREENING_OBSERVATION_DATA` |
| `SexualOrientationObservationConverter` | Observation (orientation) | `DEMOGRAPHIC_DATA` |
| `BaseConverter` | — | Abstract base with common helpers |
| `IConverter` | — | Interface contract (`convert()` method) |

### CSV File Types

| Enum Value | Description |
|-----------|-------------|
| `DEMOGRAPHIC_DATA` | Patient identity and demographic fields |
| `QE_ADMIN_DATA` | Qualified Entity and organization data |
| `SCREENING_PROFILE_DATA` | Screening encounter header record |
| `SCREENING_OBSERVATION_DATA` | Individual screening question/answer rows |

---

## CSV Processing Pipeline

```
POST /flatfile/csv/Bundle  (multipart ZIP)
          │
          ▼
   CsvController
          │
          ▼
   CsvService               ← validates ZIP structure, identifies file types
          │
          ▼
   CsvOrchestrationEngine   ← coordinates ordered processing steps
          │
          ├─► FileProcessor         ← per-file parsing via OpenCSV
          │       │
          │       ▼
          │   CsvBundleProcessorService  ← assembles FHIR Bundle from rows
          │       │
          │       ▼
          │   CsvToFhirConverter     ← delegates to per-resource converters
          │
          └─► FhirValidationServiceClient  ← calls fhir-validation-service
                      │
                      ▼
              Store result + return outcome
```

---

## Code Lookup Service

`CodeLookupService` resolves clinical codes used in FHIR resource construction:
- LOINC codes for screening questions
- SNOMED CT codes for conditions and procedures
- Custom TechBD value sets
- Codes are loaded from classpath resources at startup and cached in memory

---

## FHIR Validation Integration

`FhirValidationServiceClient` is a WebFlux `WebClient`-based HTTP client that delegates FHIR bundle validation to the [`fhir-validation-service`](fhir-validation-service.md). This decouples IG loading from the CSV service and allows independent scaling.

Configuration:
```yaml
org:
  techbd:
    fhir-validation-service:
      base-url: ${FHIR_VALIDATION_SERVICE_URL}
```

---

## VFS Ingress (`org.techbd.csv.service.vfs`)

| Class | Description |
|-------|-------------|
| `VfsCoreService` | Mounts VFS sources (local, SFTP, S3-backed) |
| `VfsIngressConsumer` | Polls VFS locations for new ZIP files to process automatically |

This enables batch ingestion from shared file system locations without HTTP push.

---

## Data Models (`org.techbd.csv.model`)

| Class | Description |
|-------|-------------|
| `DemographicData` | Parsed row from `DEMOGRAPHIC_DATA` CSV |
| `ScreeningProfileData` | Parsed row from `SCREENING_PROFILE_DATA` CSV |
| `ScreeningObservationData` | Parsed row from `SCREENING_OBSERVATION_DATA` CSV |
| `QeAdminData` | Parsed row from `QE_ADMIN_DATA` CSV |
| `FileDetail` | Metadata about an individual file in the ZIP |
| `FileType` | Enum of recognized CSV file types |
| `CsvProcessingMetrics` | Timing and row counts for a processing run |
| `CsvDataValidationStatus` | Per-row validation result (pass/fail + messages) |
| `PayloadAndValidationOutcome` | Final combined result: converted Bundle + validation outcome |

---

## Security Configuration

`SecurityConfig` configures:
- Stateless session management
- API key / Bearer token authentication for submission endpoints
- Actuator endpoints accessible without authentication
- CORS settings for cross-origin API clients

---

## Async Processing

`AsyncConfig` enables Spring `@Async` with a bounded thread pool. CSV ZIP processing is executed asynchronously to prevent HTTP timeout on large submissions.

---

## Feature Toggles

| Feature | Description |
|---------|-------------|
| Defined in `FeatureEnum` | Runtime flags for experimental behavior |
| Managed by Togglz | Persistent state, console-accessible |

---

## Configuration

### Key `application.yml` Properties

| Property | Description |
|----------|-------------|
| `spring.profiles.active` | Active Spring profile (`sandbox`, `devl`, `stage`, etc.) |
| `spring.servlet.multipart.max-file-size` | Maximum CSV ZIP upload size |
| `org.techbd.fhir-validation-service.base-url` | URL of the FHIR validation service |
| OpenTelemetry exporter config | Tracing endpoint for distributed traces |

### Environment Variables

```
SPRING_PROFILES_ACTIVE
FHIR_VALIDATION_SERVICE_URL
TECHBD_UDI_DS_PRIME_JDBC_URL
TECHBD_UDI_DS_PRIME_USERNAME
TECHBD_UDI_DS_PRIME_PASSWORD
```

---

## Observability

- **Tracing**: OpenTelemetry with configurable exporter (OTLP/Zipkin)
- **Metrics**: Micrometer + Spring Boot Actuator
- **Structured Logging**: JSON-formatted logs via `AppLogger`

---

## Building and Running

```bash
# Build
cd csv-service
mvn clean package

# Run
java -jar target/csv-service-*.jar

# Access Swagger UI at http://localhost:8080/swagger-ui.html
```

---

## Related Modules

- [hub-core-lib](hub-core-lib.md) — contains the shared CSV converter logic also used by hub-prime
- [fhir-validation-service](fhir-validation-service.md) — downstream validator called by this service
- [hub-prime](hub-prime.md) — alternative entry point for CSV ingestion (via `/flatfile/csv/Bundle`)
- [test-automation](test-automation.md) — CSV smoke test suites for PHI-QA and Stage environments
