# Shared Libraries

This document covers the two shared Java libraries in the Polyglot Prime
monorepo: **hub-core-lib** and **nexus-core-lib**.

---

## hub-core-lib

### Overview

`hub-core-lib` is the primary shared Java library used by `hub-prime`,
`csv-service`, and `fhir-validation-service`.  It contains:

- **FHIR orchestration engine** — HAPI FHIR validation with SHIN-NY IG support
- **CSV processing engine** — ZIP extraction, Python-based validation, FHIR
  conversion
- **FHIRService** — forwarding validated bundles to the SHIN-NY Data Lake
  (mTLS, API key, etc.)
- **jOOQ data access layer** — type-safe SQL for all database interactions
- **Common configuration** — `CoreAppConfig` bound to `org.techbd` properties
- **Utility classes** — UUID generation, logging, HTTP helpers

### Module Location

```
hub-core-lib/
├── src/main/java/org/techbd/
│   ├── SpringContextHolder.java        # Static ApplicationContext accessor
│   ├── config/
│   │   └── CoreAppConfig.java          # Shared configuration properties
│   ├── converters/                      # Data type converters
│   ├── exceptions/                      # Exception hierarchy
│   ├── model/                           # Shared domain models
│   ├── service/
│   │   ├── csv/
│   │   │   ├── CsvService.java          # CSV validation & forwarding
│   │   │   └── engine/
│   │   │       └── CsvOrchestrationEngine.java
│   │   └── fhir/
│   │       └── FHIRService.java         # FHIR forwarding to SHIN-NY
│   └── util/                            # Utility helpers
└── src/main/resources/
    └── nexus-core-lib/                  # Per-profile YAML configs loaded by nexus services via SpringContextHolder
        ├── application.yml
        ├── application-devl.yml
        ├── application-sandbox.yml
        ├── application-phiqa.yml
        ├── application-stage.yml
        └── application-phiprod.yml
```

### Key Classes

#### CoreAppConfig

**`org.techbd.config.CoreAppConfig`**

Spring Boot `@ConfigurationProperties` class bound to the `org.techbd` prefix.
Shared by all services that depend on this library.

Key fields:

| Field | Type | Description |
|-------|------|-------------|
| `version` | String | Application version |
| `defaultDatalakeApiUrl` | String | Default SHIN-NY Data Lake API URL |
| `defaultDataLakeApiAuthn` | record | Authentication config (mTLS or API key) |
| `structureDefinitionsUrls` | Map | Resource type → StructureDefinition URL |
| `igPackages` | Map | IG version configs |
| `validationSeverityLevel` | String | Min severity to report |
| `dataLedgerApiUrl` | String | Data Ledger API URL |
| `csv.validation` | record | Python script paths for CSV validation |

Authentication sub-records:

| Record | Fields |
|--------|--------|
| `DefaultDataLakeApiAuthn` | `mTlsStrategy`, `mTlsAwsSecrets`, `mTlsResources`, `withApiKeyAuth`, `postStdinPayloadToNyecDataLakeExternal` |
| `MTlsAwsSecrets` | `mTlsKeySecretName`, `mTlsCertSecretName` |
| `MTlsResources` | `mTlsKeyResourceName`, `mTlsCertResourceName` |
| `WithApiKeyAuth` | `apiKeyHeaderName`, `apiKeySecretName` |

#### FHIRService

**`org.techbd.service.fhir.FHIRService`**

Handles forwarding of FHIR bundles to the SHIN-NY Data Lake.  Supports
multiple authentication strategies:

| Strategy | Description |
|----------|-------------|
| `NO_MTLS` | Plain HTTPS POST |
| `AWS_SECRETS` | Certificate loaded from AWS Secrets Manager |
| `RESOURCE` | Certificate loaded from classpath |
| `POST_STDIN_PAYLOAD` | Delegates to an external command (stdin) |
| `API_KEY` | API key authentication |

The strategy is selected at runtime based on `CoreAppConfig.defaultDataLakeApiAuthn`.

#### CsvOrchestrationEngine

**`org.techbd.service.csv.engine.CsvOrchestrationEngine`**

Manages CSV validation sessions via a fluent builder pattern:

```java
CsvOrchestrationEngine.OrchestrationSession session = engine.session()
    .withMasterInteractionId(interactionId)
    .withSessionId(UuidUtil.generateUuid())
    .withTenantId(tenantId)
    .withFile(uploadedFile)
    .withRequestParameters(params)
    .build();

engine.orchestrate(session);
Map<String, Object> results = session.getValidationResults();
```

The session:
1. Extracts CSV files from the ZIP archive
2. Runs the Python validation script
3. Converts valid records to FHIR bundles
4. Validates bundles with HAPI FHIR
5. Saves combined results to PostgreSQL

#### CsvService (hub-core-lib)

**`org.techbd.service.csv.CsvService`**

The shared CsvService (used by both `hub-prime` and `csv-service`) exposes:

| Method | Description |
|--------|-------------|
| `validateCsvFile()` | Entry point for single-file validation |
| `processZipFile()` | Entry point for ZIP file processing |
| `runValidationProcess()` | Creates and runs `OrchestrationSession` |

#### SpringContextHolder

**`org.techbd.SpringContextHolder`**

Provides static access to the Spring `ApplicationContext`.  Used by nexus
modules to load YAML configuration at startup without full Spring Boot
bootstrapping.

---

## nexus-core-lib

### Overview

`nexus-core-lib` is a Nexus-specific shared library used by
`nexus-ingestion-api`.  It provides utilities and configuration specific to the
Nexus integration platform.

### Module Location

```
nexus-core-lib/
├── src/main/java/org/techbd/corelib/
│   └── config/
│       └── CoreAppConfig.java       # Nexus-specific configuration
└── src/main/java/
    └── org/
        └── ...                      # Nexus utility classes
```

### Key Classes

#### CoreAppConfig (nexus)

**`org.techbd.corelib.config.CoreAppConfig`**

Nexus-specific `@ConfigurationProperties` class bound to the `org.techbd`
prefix.  Configures connection strings, queue names, and bucket references
for the Nexus services.

---

## Using Shared Libraries

Both libraries are declared as Maven dependencies in the consuming module's
`pom.xml`:

```xml
<!-- hub-prime / csv-service / fhir-validation-service -->
<dependency>
    <groupId>org.techbd</groupId>
    <artifactId>hub-core-lib</artifactId>
    <version>${project.version}</version>
</dependency>

<!-- nexus-ingestion-api -->
<dependency>
    <groupId>org.techbd</groupId>
    <artifactId>nexus-core-lib</artifactId>
    <version>${project.version}</version>
</dependency>
```

The libraries are installed into the local Maven repository during the root
`mvn clean install` build.

---

## Building the Libraries Independently

```bash
# Build hub-core-lib
cd hub-core-lib
mvn clean install

# Build nexus-core-lib
cd nexus-core-lib
mvn clean install
```
