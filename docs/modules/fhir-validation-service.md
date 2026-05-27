# fhir-validation-service

## Overview

**fhir-validation-service** is a standalone Spring Boot microservice dedicated
to FHIR R4 bundle validation.  It exposes the same `/Bundle/$validate` and
`/Bundle/$status/{id}` endpoints as `hub-prime` but does **not** forward
validated bundles to the SHIN-NY Data Lake.

This service is suitable for deployments where validation needs to be offloaded
from the main hub, or where external clients require a dedicated validation
endpoint.

---

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Runtime | Java 21, Spring Boot 3.3 |
| FHIR validation | HAPI FHIR R4 |
| Database access | jOOQ (PostgreSQL) |
| Build | Maven |

---

## Module Location

```
fhir-validation-service/
├── src/main/java/org/techbd/fhir/
│   ├── FhirValidationApplication.java   # Spring Boot entry point
│   ├── config/
│   │   └── AppConfig.java               # Service configuration
│   ├── controller/
│   │   └── FhirController.java          # REST endpoint handlers
│   ├── exceptions/                       # Custom exception types
│   ├── feature/                          # OpenFeature flag definitions
│   ├── service/                          # Business logic
│   └── util/                             # Utility helpers
└── src/main/resources/
    ├── application.yml                   # Shared configuration
    └── application-devl.yml             # Development profile overrides
```

---

## Key Classes

### Application Entry Point

**`org.techbd.fhir.FhirValidationApplication`**  
Standard `@SpringBootApplication`.

### Configuration

**`org.techbd.fhir.config.AppConfig`**

Bound to the `org.techbd` properties prefix.  Key properties:

| Property | Description |
|----------|-------------|
| `version` | Application version |
| `defaultDatalakeApiUrl` | SHIN-NY Data Lake base URL (used for status checks) |
| `operationOutcomeHelpUrl` | Help URL included in OperationOutcome responses |
| `structureDefinitionsUrls` | Map of resource type → StructureDefinition path |
| `baseFHIRURL` | Base FHIR URL for resource generation |
| `fhirVersion` | Supported FHIR version string |
| `igVersion` | Active IG version |
| `igPackages.fhir-v4` | Map of IG package configurations |
| `validationSeverityLevel` | Minimum severity to report (`error`, `warning`, etc.) |
| `dataLedgerApiUrl` | Data Ledger integration URL |

### FHIR Controller

**`org.techbd.fhir.controller.FhirController`**

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/Bundle/$validate` | Validate a FHIR bundle |
| `GET` | `/Bundle/$status/{bundleSessionId}` | Check async validation status |

---

## API

### `POST /Bundle/$validate`

**Headers (required)**

| Header | Description |
|--------|------------|
| `X-TechBD-Tenant-ID` | Tenant identifier |
| `Content-Type` | `application/json` or `application/fhir+json` |

**Request body**: FHIR Bundle JSON

**Response**: `200 OK` with OperationOutcome JSON

```json
{
  "OperationOutcome": {
    "validationResults": [
      {
        "profileUrl": "https://shinny.org/us/ny/hrsn/StructureDefinition-SHINNYBundleProfile",
        "issues": []
      }
    ]
  }
}
```

### `GET /Bundle/$status/{bundleSessionId}`

Returns the validation status for the given interaction ID.

---

## Configuration

`application.yml` (excerpt):

```yaml
org:
  techbd:
    version: ${project.version}
    ig-packages:
      fhir-v4:
        shinny-packages:
          shinny-v1-8-1:
            profile-base-url: http://shinny.org/us/ny/hrsn
            package-path: ig-packages/shin-ny-ig/shinny/v1.8.1
            ig-version: 1.8.1
    validation-severity-level: error
    udi:
      prime:
        jdbc:
          url: ${${SPRING_PROFILES_ACTIVE}_TECHBD_UDI_DS_PRIME_JDBC_URL:}
          username: ${${SPRING_PROFILES_ACTIVE}_TECHBD_UDI_DS_PRIME_JDBC_USERNAME:}
          password: ${${SPRING_PROFILES_ACTIVE}_TECHBD_UDI_DS_PRIME_JDBC_PASSWORD:}
```

---

## Running the Service

```bash
cd fhir-validation-service
export SPRING_PROFILES_ACTIVE=devl
mvn spring-boot:run
```

---

## Building

```bash
cd fhir-validation-service
mvn clean package
java -jar target/fhir-validation-service-*.jar
```
