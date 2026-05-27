# csv-service

## Overview

**csv-service** is a standalone Spring Boot microservice for ingesting and
validating **CSV flat-file data** (submitted as ZIP archives).  It converts
CSV screening data into FHIR R4 bundles conformant with the SHIN-NY
Implementation Guide and optionally forwards the resulting bundles to the
SHIN-NY Data Lake.

The same CSV processing logic is also available through `hub-prime` (via the
shared `hub-core-lib` library), so `csv-service` is used when CSV processing
needs to be deployed as an independently scalable service.

---

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Runtime | Java 21, Spring Boot 3.3 |
| CSV validation | Python 3 (`frictionless` data package) |
| FHIR generation + validation | HAPI FHIR R4 |
| Database access | jOOQ (PostgreSQL) |
| Build | Maven |

---

## Module Location

```
csv-service/
├── src/main/java/org/techbd/csv/
│   ├── CsvApplication.java        # Spring Boot entry point
│   ├── config/
│   │   └── AppConfig.java         # Service configuration
│   ├── controller/
│   │   └── CsvController.java     # REST endpoint handler
│   ├── converters/                 # Data type converters
│   ├── feature/                    # Feature flags
│   ├── model/                      # Domain models
│   ├── service/
│   │   └── CsvService.java         # Core business logic
│   └── util/                       # Utilities
└── src/main/resources/
    └── application.yml             # Spring Boot configuration
```

---

## Processing Flow

```
POST /flatfile/csv/Bundle/$validate (multipart ZIP)
    │
    ▼
CsvController.handleCsvUpload()
    │   ├── Validate file presence and tenant ID
    │   └── Build request parameter map
    ▼
CsvService.validateCsvFile()
    │   ├── Assign master interaction ID (UUID)
    │   ├── Save ZIP to inbound folder
    │   └── Choose sync or async path
    ▼
CsvOrchestrationEngine.OrchestrationSession (hub-core-lib)
    │   ├── Extract CSV files from ZIP
    │   ├── Run Python validation script (frictionless + NYHER IG checks)
    │   ├── Convert each validated CSV row → FHIR Bundle JSON
    │   └── Run HAPI FHIR validation on generated bundles
    ▼
Persist to PostgreSQL
    │   ├── sat_interaction_flat_file_csv_request (ZIP level)
    │   └── sat_interaction_fhir_validation_issue (per bundle, per issue)
    ▼
Optional: Forward bundles to SHIN-NY Data Lake
```

### Sync vs Async Processing

The `X-TechBD-Immediate` header controls the processing mode:

- **`true` (synchronous)**: The HTTP response is held until all CSV files in
  the ZIP are processed.  Returns the full OperationOutcome.
- **`false` / absent (asynchronous)**: The service immediately returns an
  acknowledgement with the `interactionId`.  Use `GET /Bundle/$status/{id}`
  to poll for results.

---

## Key Classes

### CsvController

**`org.techbd.csv.controller.CsvController`**

Handles `POST /flatfile/csv/Bundle/$validate`.  Validates that the uploaded
file is present and non-empty, extracts request parameters, and delegates to
`CsvService`.

### CsvService

**`org.techbd.csv.service.CsvService`**

Core orchestration logic:

| Method | Description |
|--------|-------------|
| `validateCsvFile()` | Entry point: assigns interaction ID, saves file, dispatches sync/async |
| `runValidationProcess()` | Creates and runs an `OrchestrationSession` |
| `processZipFile()` | Extracts ZIP content and triggers per-file processing |
| `buildAsyncResponse()` | Builds the interim acknowledgement JSON for async requests |

---

## Configuration

`application.yml` (excerpt):

```yaml
org:
  techbd:
    version: ${project.version}
    baseFHIRURL: ${TECHBD_BASE_FHIR_URL}
    csv:
      validation:
        pythonScriptPath: ${TECHBD_PYTHON_SCRIPT_PATH}support/specifications/flat-file/validate-nyher-fhir-ig-equivalent.py
        pythonExecutable: python3
        packagePath: ${TECHBD_PYTHON_SCRIPT_PATH}support/specifications/flat-file/datapackage-nyher-fhir-ig-equivalent.json
        inboundPath: /app/techbyDesign/flatFile/inbound
        outputPath: /app/techbyDesign/flatFile/outbound
        ingressHomePath: /app/techbyDesign/flatFile/ingress
    dataLedgerApiUrl: ${TECHBD_DATA_LEDGER_API_URL}
    udi:
      prime:
        jdbc:
          url: ${${SPRING_PROFILES_ACTIVE}_TECHBD_UDI_DS_PRIME_JDBC_URL:}
          username: ${${SPRING_PROFILES_ACTIVE}_TECHBD_UDI_DS_PRIME_JDBC_USERNAME:}
          password: ${${SPRING_PROFILES_ACTIVE}_TECHBD_UDI_DS_PRIME_JDBC_PASSWORD:}
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
```

### Required Environment Variables

| Variable | Description |
|----------|-------------|
| `SPRING_PROFILES_ACTIVE` | Active profile |
| `TECHBD_BASE_FHIR_URL` | Base URL for FHIR resource generation |
| `TECHBD_PYTHON_SCRIPT_PATH` | Path prefix to Python validation scripts |
| `TECHBD_DATA_LEDGER_API_URL` | Data Ledger API endpoint |
| `${PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_URL` | PostgreSQL JDBC URL |
| `${PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_USERNAME` | DB username |
| `${PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_PASSWORD` | DB password |

---

## Python Validation Scripts

The CSV validation relies on Python scripts located in
`support/specifications/flat-file/`:

| File | Description |
|------|-------------|
| `validate-nyher-fhir-ig-equivalent.py` | Main validation script |
| `datapackage-nyher-fhir-ig-equivalent.json` | Frictionless Data Package descriptor |

The scripts validate CSV structure, field types, and value constraints against
the NYHER FHIR IG–equivalent rules, then convert valid records to FHIR bundles.

---

## Running the Service

```bash
cd csv-service
export SPRING_PROFILES_ACTIVE=devl
export TECHBD_PYTHON_SCRIPT_PATH=/path/to/polyglot-prime/
export TECHBD_BASE_FHIR_URL=http://localhost:8080
mvn spring-boot:run
```

---

## Building

```bash
cd csv-service
mvn clean package -DskipTests
java -jar target/csv-service-*.jar
```
