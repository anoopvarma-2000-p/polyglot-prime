# fhir-validation-service

## Overview

`fhir-validation-service` is a standalone Spring Boot microservice dedicated exclusively to FHIR R4 Bundle validation. It loads Implementation Guide (IG) packages at startup and exposes a REST API that other services (e.g., `csv-service`, `hub-prime`) call to validate FHIR Bundles without each service needing to maintain its own HAPI FHIR validator instance.

- **Artifact ID**: `fhir-validation-service`
- **Group ID**: `org.techbd`
- **Version**: `0.1167.0`
- **Packaging**: JAR (Spring Boot executable)
- **Java**: 21 LTS
- **Spring Boot**: 3.3.3
- **AWS SDK**: 2.28.0

---

## Architecture

```
fhir-validation-service/
└── src/main/java/
    └── org/techbd/fhir/
        ├── FhirValidationApplication.java   # @SpringBootApplication
        ├── config/           # App config, constants, security
        ├── controller/       # FHIR, health, feature toggle, exception handler
        ├── exceptions/       # Error codes, validation exceptions
        ├── feature/          # Feature toggles (Togglz)
        ├── service/          # FHIR service + orchestration engine
        │   ├── engine/       # OrchestrationEngine
        │   └── validation/   # FhirBundleValidator, pre/post populate hooks
        └── util/             # ConceptReaderUtils, FHIRUtil, FileUtils
```

---

## Main Entry Point

| Class | Description |
|-------|-------------|
| `org.techbd.fhir.FhirValidationApplication` | `@SpringBootApplication` — bootstrap |
| `org.techbd.fhir.config.AppConfig` | Primary `@Configuration` class |

---

## Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `spring-boot-starter-web` | 3.3.3 | REST API and Tomcat |
| `spring-boot-starter-security` | 3.3.3 | HTTP security |
| `spring-boot-starter-actuator` | 3.3.3 | Health / metrics |
| `hapi-fhir-base` | 8.2.2 | HAPI FHIR core |
| `hapi-fhir-structures-r4` | 8.2.2 | FHIR R4 resource models |
| `hapi-fhir-validation` | 8.2.2 | FHIR validation engine |
| `hapi-fhir-validation-resources-r4` | 8.2.2 | R4 base validation profiles |
| `hapi-fhir-client` | 8.2.2 | FHIR HTTP client |
| `hapi-fhir-caching-caffeine` | 8.2.2 | Validation result caching |
| `spring-boot-starter-jooq` | 3.3.1 | Type-safe SQL (for result persistence) |
| `postgresql` | — | PostgreSQL JDBC driver |
| `springdoc-openapi-starter-webmvc-ui` | — | Swagger / OpenAPI UI |
| `opentelemetry-spring-boot-starter` | — | Distributed tracing |
| `micrometer-tracing` | — | Metrics |
| `aws-java-sdk-secretsmanager` | 2.28.0 | AWS Secrets Manager |
| `jackson-databind` / `jackson-dataformat-yaml` | — | JSON + YAML processing |
| `commons-text` | — | Text utilities |
| `togglz-spring-boot-starter` | — | Feature toggles |

---

## REST API Endpoints

### FHIR Validation (`FhirController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/Bundle/$validate` | Validate a FHIR R4 Bundle; returns `OperationOutcome` |
| `POST` | `/Bundle` | Validate and optionally persist a FHIR Bundle |
| `GET` | `/metadata` | FHIR Capability Statement for this service |

The response body for validation is a FHIR `OperationOutcome` resource containing:
- `issue.severity` (`error`, `warning`, `information`)
- `issue.code` and `issue.details.text` with the specific violation
- `issue.location` pointing to the failing element path

### Health Check (`HealthCheckController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/actuator/health` | Spring Boot health endpoint |
| `GET` | `/health` | Service-level health (includes IG load status) |

### Feature Toggles (`FeatureToggleController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/features` | List feature flags |
| `POST` | `/features/{name}` | Toggle feature flag |

---

## Validation Engine

### `FhirBundleValidator`

The core validator wraps HAPI FHIR's `FhirValidator`. At application startup it:

1. Creates a `FhirContext` for FHIR R4
2. Loads base validation packages:
   - US Core Implementation Guide
   - SDOH Clinical Care IG
   - SDC (Structured Data Capture) IG
3. Loads domain-specific IG packages:
   - **SHINNY v1.8.1** — New York State HRSN Implementation Guide (production)
   - **test-SHINNY v1.9.1** — Pre-release IG for testing new profiles
4. Configures a `NpmPackageValidationSupport` for each IG
5. Assembles the validation support chain

### `OrchestrationEngine`

The validation orchestrator that manages the full lifecycle:

```
Input Bundle (JSON/XML string)
        │
        ▼
  PrePopulateSupport      ← normalize, enrich, strip invalid fields
        │
        ▼
  FhirBundleValidator     ← run HAPI FHIR validation against loaded IGs
        │
        ▼
  PostPopulateSupport     ← enrich OperationOutcome, add context metadata
        │
        ▼
  ValidationOutcome       ← returned to caller
```

### `PrePopulateSupport`

Called before validation. Can:
- Normalize resource identifiers
- Set default values required by the IG
- Strip fields that cause known false-positive validation errors

### `PostPopulateSupport`

Called after validation. Can:
- Add submission metadata to the outcome
- Map error codes to human-readable messages
- Annotate warnings that are acceptable for the current IG version

---

## Implementation Guides

The service loads multiple IG packages. Each IG is a `.tgz` file conforming to FHIR Package specification.

| IG | Version | Profile Scope |
|----|---------|--------------|
| SHINNY | 1.8.1 | New York State HRSN screening bundles (production) |
| test-SHINNY | 1.9.1 | Next-generation HRSN profiles (testing) |
| US Core | current | Base US patient, practitioner, organization profiles |
| SDOH Clinical Care | current | Social determinants of health observations |
| SDC (StructuredDataCapture) | current | Questionnaire-based screening forms |

IG package `.tgz` files are stored in the classpath under `src/main/resources/ig-packages/`.

---

## Utility Classes (`org.techbd.fhir.util`)

| Class | Description |
|-------|-------------|
| `FHIRUtil` | FHIR context creation, resource serialization/deserialization, type helpers |
| `ConceptReaderUtils` | Reads FHIR ConceptMap and ValueSet resources from classpath for custom validation |
| `FileUtils` | Classpath and filesystem resource loading helpers |

---

## Error Handling

| Class | Description |
|-------|-------------|
| `GlobalExceptionHandler` | `@ControllerAdvice` — converts exceptions to structured JSON responses |
| `JsonValidationException` | Thrown when the input cannot be parsed as valid JSON before FHIR validation |
| `ErrorCode` | Enumeration of service-level error codes with HTTP status mappings |

---

## Security Configuration

`SecurityConfig` configures:
- Bearer token (API key) authentication for the `/Bundle` and `/Bundle/$validate` endpoints
- Actuator endpoints open without authentication
- CSRF disabled (stateless REST service)

---

## Feature Toggles

Feature flags (via Togglz) allow enabling/disabling specific validation behavior at runtime:

| Example Use Case |
|-----------------|
| Enable/disable loading of test-SHINNY v1.9.1 IG |
| Switch between strict and permissive validation modes |
| Enable extended OperationOutcome annotations |

Managed via `FeatureEnum`, `TogglzConfiguration`, and the `/features` endpoint.

---

## Configuration

### Key `application.yml` Properties

| Property | Description |
|----------|-------------|
| `spring.profiles.active` | Active profile (`sandbox`, `devl`, etc.) |
| `org.techbd.ig-packages.shinny.version` | SHINNY IG version to load |
| `org.techbd.ig-packages.test-shinny.version` | Test SHINNY IG version to load |
| `org.techbd.fhir.validation.cache-size` | Caffeine cache size for validation results |

### Environment Variables

```
SPRING_PROFILES_ACTIVE
TECHBD_UDI_DS_PRIME_JDBC_URL
TECHBD_UDI_DS_PRIME_USERNAME
TECHBD_UDI_DS_PRIME_PASSWORD
```

### Configuration Profiles

| Profile | File | Environment |
|---------|------|-------------|
| `devl` | `application-devl.yml` | Development |
| Default | `application.yml` | Sandbox / base |

---

## Observability

- **Tracing**: OpenTelemetry with OTLP exporter
- **Metrics**: Micrometer / Spring Boot Actuator
- **Validation Timing**: Validation duration is recorded per request and logged

---

## Startup Behavior

IG package loading happens at Spring context startup via `@PostConstruct` or `ApplicationRunner`. The application will fail fast if a required IG package cannot be loaded, preventing silent validation failures in production.

---

## Building and Running

```bash
# Build
cd fhir-validation-service
mvn clean package

# Run
java -jar target/fhir-validation-service-*.jar

# Swagger UI: http://localhost:8080/swagger-ui.html
# Health check: http://localhost:8080/actuator/health
```

---

## Host Header Validation

`HostHeaderValidationFilter` (in `org.techbd.fhir.config`) validates the `Host` HTTP header on incoming requests to prevent Host Header Injection attacks. It rejects requests with unexpected or malformed host values early in the filter chain.

---

## Related Modules

- [hub-prime](hub-prime.md) — calls this service for FHIR validation (via `FHIRService`)
- [csv-service](csv-service.md) — calls this service after CSV-to-FHIR conversion
- [hub-core-lib](hub-core-lib.md) — contains an embedded `FhirBundleValidator` for in-process validation
- [udi-prime](udi-prime.md) — provides the PostgreSQL schema for storing validation results
