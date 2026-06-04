# hub-core-lib

## Overview

`hub-core-lib` is the shared Java core library for Hub services. It is a standalone JAR (not a Spring Boot application) that packages common configuration, models, services, and converters used by `hub-prime` and other Hub-family services. It avoids duplication of CSV-to-FHIR conversion logic, FHIR validation, and utility code across services.

- **Artifact ID**: `hub-core-lib`
- **Group ID**: `org.techbd`
- **Version**: `0.1167.0`
- **Packaging**: JAR (library, not an application)
- **Java**: 21 LTS
- **Spring Boot**: 3.3.3 (BOM / dependency management only)

---

## Module Structure

```
hub-core-lib/
└── src/main/java/
    └── org/techbd/
        ├── config/           # Configuration, constants, app initialization
        ├── converters/csv/   # CSV-to-FHIR resource converters (11 types)
        ├── exceptions/       # Error codes and exception classes
        ├── model/csv/        # CSV data models / domain objects
        ├── service/
        │   ├── ccda/         # CCDA service
        │   ├── csv/          # CSV orchestration and processing
        │   ├── dataledger/   # Data Ledger API client
        │   ├── fhir/         # FHIR service, validation, orchestration engine
        │   ├── hl7/          # HL7 service
        │   └── vfs/          # Virtual File System ingress
        └── util/             # Logging, dates, FHIR helpers, CSV utilities
```

---

## Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `spring-boot-starter-web` | 3.3.3 | Spring MVC baseline |
| `spring-boot-starter-security` | 3.3.3 | Security baseline |
| `spring-boot-starter-webflux` | 3.3.3 | Reactive HTTP client |
| `spring-boot-starter-jooq` | 3.3.1 | Type-safe SQL |
| `hapi-fhir-base` | 8.2.2 | HAPI FHIR core |
| `hapi-fhir-structures-r4` | 8.2.2 | FHIR R4 models |
| `hapi-fhir-validation` | 8.2.2 | FHIR validation engine |
| `hapi-fhir-validation-resources-r4` | 8.2.2 | R4 validation profiles |
| `hapi-fhir-caching-caffeine` | 8.2.2 | Validation cache |
| `hapi-fhir-client` | 8.2.2 | FHIR HTTP client |
| `postgresql` | — | PostgreSQL JDBC driver |
| `HikariCP` | — | Connection pooling |
| `opencsv` | 5.8 | CSV parsing |
| `jackson-databind` | — | JSON processing |
| `jackson-dataformat-yaml` | — | YAML processing |
| `nimbus-jose-jwt` | — | JWT/OIDC token handling |
| `aws-java-sdk-secretsmanager` | 2.28.0 | AWS Secrets Manager |

---

## Configuration (`org.techbd.config`)

| Class | Description |
|-------|-------------|
| `Configuration` | Core configuration properties bean |
| `CoreAppConfig` | Primary `@Configuration` — wires beans for FHIR, jOOQ, async |
| `AppInitializationConfig` | Post-startup initialization hooks |
| `CoreUdiPrimeJpaConfig` | JPA / DataSource configuration for UDI Prime database |
| `CoreUdiReaderConfig` | Read-only datasource configuration |
| `CoreAsyncConfig` | `@EnableAsync` with thread pool configuration |
| `SpringContextHolder` | Static holder for `ApplicationContext` |
| `Constant` / `Constants` | Application-wide constants |
| `Helpers` | Host header resolution, URL building |
| `Interactions` | Interaction record model |
| `CsvProcessingState` | State machine for CSV processing lifecycle |
| `Nature` / `Origin` / `SourceType` / `State` | Domain enumerations |

---

## CSV-to-FHIR Converters (`org.techbd.converters.csv`)

These converters transform flat CSV rows into individual FHIR R4 resources that are assembled into a complete Bundle.

| Converter Class | FHIR Resource | Description |
|----------------|---------------|-------------|
| `CsvToFhirConverter` | Bundle (root) | Orchestrates all sub-converters; assembles the final FHIR Bundle |
| `BundleConverter` | Bundle metadata | Sets Bundle type, timestamp, and identifiers |
| `PatientConverter` | Patient | Maps demographic data to a FHIR Patient resource |
| `OrganizationConverter` | Organization | Creates the submitting Organization resource |
| `EncounterConverter` | Encounter | Maps visit/encounter data |
| `ConsentConverter` | Consent | Maps consent records |
| `ProcedureConverter` | Procedure | Maps procedure/intervention data |
| `ScreeningResponseObservationConverter` | Observation (screening) | Maps HRSN screening responses |
| `SexualOrientationObservationConverter` | Observation (orientation) | Maps sexual orientation observations |
| `BaseConverter` | — | Abstract base with shared helper methods |
| `IConverter` | — | Interface defining the `convert()` contract |

---

## CSV Processing Service (`org.techbd.service.csv`)

| Class | Description |
|-------|-------------|
| `CsvService` | Entry point for CSV processing; validates headers and dispatches |
| `CsvBundleProcessorService` | Coordinates multi-file CSV bundle processing |
| `CodeLookupService` | Resolves SNOMED, LOINC, and custom codes from lookup tables |
| `FileProcessor` | Low-level per-file processing step (in `engine/`) |
| `CsvOrchestrationEngine` | Manages ordered processing steps, collects metrics |
| `SimpleMultipartFile` | In-memory `MultipartFile` implementation for testing |

---

## FHIR Services (`org.techbd.service.fhir`)

| Class | Description |
|-------|-------------|
| `FHIRService` | High-level FHIR bundle intake: validates, stores, and returns outcomes |
| `FhirReplayService` | Replays previously submitted FHIR bundles |
| `OrchestrationEngine` | Validation pipeline: pre-populate → validate → post-populate |
| `FhirBundleValidator` | Wraps HAPI FHIR validator; loads IG packages at startup |
| `PrePopulateSupport` | Hook called before validation (normalization, enrichment) |
| `PostPopulateSupport` | Hook called after validation (outcome enrichment) |

---

## HL7 and CCDA Services

| Class | Package | Description |
|-------|---------|-------------|
| `HL7Service` | `org.techbd.service.hl7` | Parses and processes HL7 v2 messages |
| `CCDAService` | `org.techbd.service.ccda` | Processes CCD-A (C-CDA) clinical documents |

---

## Data Ledger Client (`org.techbd.service.dataledger`)

`CoreDataLedgerApiClient` is a WebFlux-based HTTP client that posts interaction records to the TechBD Data Ledger service for audit and quality tracking.

---

## VFS Integration (`org.techbd.service.vfs`)

| Class | Description |
|-------|-------------|
| `VfsCoreService` | Mounts and reads from Virtual File System (VFS) sources |
| `VfsIngressConsumer` | Polls VFS locations for new files to ingest |

---

## CSV Data Models (`org.techbd.model.csv`)

| Class | Description |
|-------|-------------|
| `DemographicData` | Patient/person demographic fields |
| `ScreeningProfileData` | HRSN screening profile header data |
| `ScreeningObservationData` | Individual screening observation rows |
| `QeAdminData` | Qualified Entity administrative metadata |
| `FileDetail` | Metadata about an uploaded file (name, size, type) |
| `FileType` | Enumeration of supported CSV file types |
| `CsvProcessingMetrics` | Timing and count metrics for a processing run |
| `CsvDataValidationStatus` | Per-row validation status and error messages |
| `PayloadAndValidationOutcome` | Combined payload + validation result |

---

## Utility Classes (`org.techbd.util`)

| Class | Description |
|-------|-------------|
| `AppLogger` | Structured SLF4J logger with JSON fields |
| `TemplateLogger` | Logs template rendering operations |
| `SystemDiagnosticsLogger` | JVM/system diagnostics at startup |
| `AWSUtil` | Fetches secrets from AWS Secrets Manager |
| `DateUtil` | Date/time formatting helpers |
| `JsonText` | Serializes objects to JSON strings |
| `CsvConstants` | CSV column name constants and headers |
| `CsvConversionUtil` | CSV row parsing, type coercion, validation |
| `ConceptReaderUtils` | Reads FHIR concept maps from classpath |
| `CoreFHIRUtil` | FHIR resource building helpers |
| `FileUtils` | File I/O helpers |

---

## Database Configuration

The library provides two JPA datasource configurations:

- **`CoreUdiPrimeJpaConfig`**: Full read-write datasource pointing to the UDI Prime PostgreSQL database. Connection configured via environment variables.
- **`CoreUdiReaderConfig`**: Read-only datasource for reporting queries.

Both use HikariCP connection pooling. jOOQ `DSLContext` is configured alongside JPA for type-safe queries.

---

## How It Is Used

`hub-prime` declares a dependency on `hub-core-lib`:

```xml
<dependency>
    <groupId>org.techbd</groupId>
    <artifactId>hub-core-lib</artifactId>
    <version>${project.version}</version>
</dependency>
```

Spring component scanning in `hub-prime` picks up beans defined in `hub-core-lib` because both share the `org.techbd` base package.

---

## Related Modules

- [hub-prime](hub-prime.md) — the primary application that consumes this library
- [nexus-core-lib](nexus-core-lib.md) — analogous library for the Nexus service family
- [csv-service](csv-service.md) — standalone CSV service that replicates converters from this library
