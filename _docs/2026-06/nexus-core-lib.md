# nexus-core-lib

## Overview

`nexus-core-lib` is the shared core library for the Nexus service family. It is a standalone JAR (not a runnable application) that provides common configuration classes, constants, domain types, data access helpers, and utilities shared across `nexus-ingestion-api` and any future Nexus-family services.

- **Artifact ID**: `nexus-core-lib`
- **Group ID**: `org.techbd`
- **Version**: `0.1167.0`
- **Packaging**: JAR (library)
- **Java**: 21 LTS
- **Spring Boot**: 3.3.3 (BOM / dependency management only)
- **AWS SDK**: 2.28.0

---

## Module Structure

```
nexus-core-lib/
└── src/main/java/
    └── org/techbd/corelib/
        ├── config/           # Configuration, constants, enums, app config
        ├── service/
        │   └── dataledger/   # Data Ledger API client
        └── util/             # Logging, date, FHIR, JSON, UUID utilities
```

---

## Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `spring-boot-starter-web` | 3.3.3 | Spring MVC / HTTP client baseline |
| `postgresql` | — | PostgreSQL JDBC driver |
| `spring-boot-starter-jooq` | 3.3.1 | Type-safe SQL (jOOQ) |
| `commons-text` | — | Apache Commons text utilities |
| `snakeyaml` | — | YAML parsing |
| `aws-java-sdk-secretsmanager` | 2.28.0 | AWS Secrets Manager access |

---

## Configuration Package (`org.techbd.corelib.config`)

| Class | Description |
|-------|-------------|
| `Configuration` | `@ConfigurationProperties` bean for application-level settings |
| `CoreAppConfig` | Primary `@Configuration` — wires shared beans |
| `CoreUdiPrimeJpaConfig` | JPA / DataSource setup for the UDI Prime database |
| `Constant` | Single-value string constants (header names, defaults) |
| `Constants` | Grouped constants (HTTP headers, FHIR profiles, etc.) |
| `Header` | HTTP header name constants |
| `Helpers` | Utility methods for host/URL resolution, request analysis |
| `CsvProcessingState` | State machine transitions for CSV processing |
| `Nature` | Enumeration of interaction natures (inbound, outbound, etc.) |
| `Origin` | Enumeration of message origin types |
| `SourceType` | Enumeration of data source types (HL7, FHIR, CCDA, CSV) |
| `State` | General state enumeration |

---

## Data Ledger Client (`org.techbd.corelib.service.dataledger`)

`DataLedgerApiClient` is a Spring `@Component` that posts audit records to the TechBD Data Ledger service. It uses Spring WebFlux `WebClient` for non-blocking HTTP calls.

**Key responsibilities:**
- Posts interaction metadata (source, tenant, timestamps, validation outcomes) to the ledger
- Supports fire-and-forget (async) posting to avoid blocking ingestion
- Configurable ledger endpoint via `Configuration` properties

---

## Utility Classes (`org.techbd.corelib.util`)

| Class | Description |
|-------|-------------|
| `AppLogger` | Structured SLF4J logger with JSON field support for consistent log formatting |
| `TemplateLogger` | Specialized logger for template processing events |
| `SystemDiagnosticsLogger` | Logs JVM and system diagnostics at application startup |
| `AWSUtil` | Retrieves secrets from AWS Secrets Manager by secret name; caches results in memory |
| `DateUtil` | Date/time formatting and parsing utilities |
| `JsonText` | Serializes Java objects to pretty-printed or compact JSON strings |
| `CoreFHIRUtil` | Low-level FHIR resource utilities (resource type checking, ID generation) |
| `UuidUtil` | Generates deterministic and random UUIDs with consistent formatting |

---

## Database Configuration

`CoreUdiPrimeJpaConfig` wires up a PostgreSQL `DataSource` backed by HikariCP and a jOOQ `DSLContext`. The datasource is configured entirely via environment variables using the profile-substitution pattern:

```
${SPRING_PROFILES_ACTIVE}_TECHBD_UDI_DS_PRIME_JDBC_URL
${SPRING_PROFILES_ACTIVE}_TECHBD_UDI_DS_PRIME_USERNAME
${SPRING_PROFILES_ACTIVE}_TECHBD_UDI_DS_PRIME_PASSWORD
```

This pattern ensures clean environment separation between sandbox, devl, stage, phiqa, and phiprod.

---

## Relationship to hub-core-lib

`nexus-core-lib` and `hub-core-lib` serve analogous purposes for their respective service families:

| Aspect | `nexus-core-lib` | `hub-core-lib` |
|--------|-----------------|----------------|
| Primary consumer | `nexus-ingestion-api` | `hub-prime` |
| Package root | `org.techbd.corelib` | `org.techbd` |
| CSV converters | No | Yes (11 converters) |
| FHIR validation | No | Yes |
| HL7 / CCDA services | No | Yes |
| Data Ledger client | Yes (`DataLedgerApiClient`) | Yes (`CoreDataLedgerApiClient`) |
| AWS utilities | Yes | Yes |

The two libraries are intentionally kept separate to allow each service family to evolve its dependencies independently.

---

## How It Is Used

`nexus-ingestion-api` declares a dependency on this library:

```xml
<dependency>
    <groupId>org.techbd</groupId>
    <artifactId>nexus-core-lib</artifactId>
    <version>${project.version}</version>
</dependency>
```

Spring component scanning in `nexus-ingestion-api` picks up beans from `nexus-core-lib` because both share the `org.techbd` base package hierarchy.

---

## Building

```bash
# Build only nexus-core-lib
cd nexus-core-lib
mvn clean install

# Build full Nexus stack (lib + API)
mvn clean install -pl nexus-core-lib,nexus-ingestion-api --also-make
```

---

## Related Modules

- [nexus-ingestion-api](nexus-ingestion-api.md) — primary consumer of this library
- [hub-core-lib](hub-core-lib.md) — analogous library for the Hub service family
- [udi-prime](udi-prime.md) — provides the PostgreSQL schema used by the JPA configuration here
