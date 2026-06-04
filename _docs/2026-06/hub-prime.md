# hub-prime

## Overview

`hub-prime` is the primary Spring Boot application in the Polyglot Prime monorepo. It serves as the central FHIR API hub, providing REST endpoints for FHIR bundle ingestion, validation, and processing, along with a Thymeleaf/HTMX-based administrative UI.

- **Artifact ID**: `hub-prime`
- **Group ID**: `org.techbd`
- **Version**: `0.1167.0`
- **Packaging**: JAR (Spring Boot executable)
- **Java**: 21 LTS
- **Spring Boot**: 3.3.3
- **Depends On**: `hub-core-lib`

---

## Architecture

`hub-prime` is a monolithic Spring Boot application that orchestrates healthcare data across multiple standards (FHIR R4, HL7 v2.x, CCDA, CSV). It exposes both an interactive web UI (for operators) and REST API endpoints (for data submitters / integration partners).

```
hub-prime/
├── src/main/java/
│   ├── lib/aide/           # Resource management, tabular data, VFS utilities
│   └── org/techbd/
│       ├── component/      # Session management
│       ├── conf/           # App configuration bootstrap
│       ├── controller/     # Webhook and CSV REST controllers
│       ├── orchestrate/    # FHIR and SFTP orchestration logic
│       ├── service/        # Core business services
│       │   ├── http/       # Filters, security, interactions, helpers
│       │   └── http/hub/prime/
│       │       ├── api/    # FHIR and Expect REST API controllers
│       │       ├── health/ # Health check indicators
│       │       ├── route/  # Route mapping tree
│       │       └── ux/     # Thymeleaf UI controllers
│       └── util/           # Shared utilities
├── src/main/resources/
│   ├── application.yml           # Base configuration
│   ├── application-devl.yml
│   ├── application-sandbox.yml
│   ├── application-stage.yml
│   ├── application-phiqa.yml
│   ├── application-phiprod.yml
│   ├── public/                   # Static web assets
│   └── templates/                # Thymeleaf HTML templates
│       ├── fragments/
│       ├── layout/
│       ├── login/
│       ├── mock/
│       └── page/
```

---

## Main Entry Point

| Class | Description |
|-------|-------------|
| `org.techbd.service.http.hub.prime.Application` | `@SpringBootApplication` — application bootstrap |
| `org.techbd.service.http.hub.prime.ServletInitializer` | WAR deployment support |
| `org.techbd.service.http.hub.prime.AppConfig` | Central `@Configuration` class |

---

## Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `spring-boot-starter-web` | 3.3.3 | REST API and Tomcat server |
| `spring-boot-starter-security` | 3.3.3 | Authentication / Authorization |
| `spring-boot-starter-oauth2-client` | 3.3.3 | OAuth2 / FusionAuth OIDC login |
| `spring-boot-starter-thymeleaf` | 3.3.3 | Server-side HTML templates |
| `spring-boot-starter-data-jpa` | 3.3.3 | JPA / Hibernate ORM |
| `spring-boot-starter-webflux` | 3.3.3 | Reactive HTTP client |
| `spring-boot-starter-actuator` | 3.3.3 | Health, metrics endpoints |
| `spring-boot-starter-mail` | 3.3.3 | Email notifications |
| `hapi-fhir-base` | 8.2.2 | HAPI FHIR core |
| `hapi-fhir-structures-r4` | 8.2.2 | FHIR R4 resource models |
| `hapi-fhir-validation` | 8.2.2 | FHIR validation engine |
| `hapi-fhir-validation-resources-r4` | 8.2.2 | R4 validation resources |
| `hapi-fhir-client` | 8.2.2 | FHIR HTTP client |
| `hapi-structures-v27` / `v28` | — | HL7 v2.7/v2.8 message structures |
| `hl7v2-fhir-converter` | 1.0.10 | HL7 v2 → FHIR R4 converter |
| `spring-boot-starter-jooq` | 3.3.1 | Type-safe SQL via jOOQ |
| `jooq-jackson-extensions` | 3.19.16 | jOOQ JSON support |
| `postgresql` | 42.7.11 | PostgreSQL JDBC driver |
| `springdoc-openapi-starter-webmvc-ui` | 2.5.0 | OpenAPI / Swagger UI |
| `opentelemetry-spring-boot-starter` | — | Distributed tracing |
| `htmx-spring-boot-thymeleaf` | — | HTMX integration for Thymeleaf |
| `opencsv` | 5.8 | CSV parsing |
| `aws-java-sdk-secretsmanager` | 2.x | AWS Secrets Manager |
| `github-api` | — | GitHub API client |

---

## REST API Endpoints

### FHIR API (`FhirController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/Bundle` | Submit and validate a FHIR R4 Bundle |
| `POST` | `/Bundle/$validate` | Validate a FHIR R4 Bundle without persisting |
| `GET` | `/metadata` | FHIR Capability Statement |
| `GET` | `/Bundle/{id}` | Retrieve a previously submitted Bundle |

### CSV API (`CsvController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/flatfile/csv/Bundle` | Submit CSV data for FHIR conversion and ingestion |
| `POST` | `/flatfile/csv/Bundle/$validate` | Validate CSV without persisting |

### Expect / Interaction API (`ExpectController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/Bundle/$expect` | Register expectations for incoming bundles |

---

## UI Controllers (Thymeleaf / HTMX)

| Controller | Route Prefix | Description |
|------------|-------------|-------------|
| `PrimeController` | `/` | Dashboard / home page |
| `ConsoleController` | `/console` | Admin console |
| `InteractionsController` | `/interactions` | Browse and inspect submitted interactions |
| `DataQualityController` | `/data-quality` | Data quality reports |
| `NeedAttentionController` | `/need-attention` | Items requiring attention |
| `DocsController` | `/docs` | Documentation browser |
| `ContentController` | `/content` | Content management |
| `ExperimentsController` | `/experiments` | Experimental features |
| `ShellController` | `/shell` | Admin shell commands |
| `MavenController` | `/maven` | Maven project info |
| `TabularRowsController` | `/tabular` | Tabular data viewer |

---

## Security

Security is configured in `SecurityConfig` and `NoAuthSecurityConfig`:

- **OAuth2 / FusionAuth**: Production authentication via OIDC. Configured by environment variables (`SPRING_SECURITY_OAUTH2_FUSIONAUTH_*`).
- **GitHub OAuth**: Secondary auth path (`GitHubUserAuthorizationFilter`, `GitHubUsersService`).
- **FusionAuth Webhook**: Receives user lifecycle events at `FusionAuthWebhookController`.
- **No-Auth mode**: `NoAuthSecurityConfig` can be activated for sandbox/local development.

Required environment variables for OAuth:

```
SPRING_SECURITY_OAUTH2_FUSIONAUTH_CLIENT_ID
SPRING_SECURITY_OAUTH2_FUSIONAUTH_CLIENT_SECRET
SPRING_SECURITY_OAUTH2_FUSIONAUTH_REDIRECT_URI
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_FUSIONAUTH_AUTHORIZATION_URI
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_FUSIONAUTH_TOKEN_URI
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_FUSIONAUTH_USER_INFO_URI
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_FUSIONAUTH_JWK_SET_URI
```

---

## Filters

| Filter | Purpose |
|--------|---------|
| `InteractionsFilter` | Captures and logs all HTTP request/response interactions |
| `FusionAuthUserAuthorizationFilter` | Validates FusionAuth JWT tokens |
| `GitHubUserAuthorizationFilter` | Validates GitHub OAuth tokens |
| `CustomRequestWrapper` | Wraps `HttpServletRequest` for body re-reading |

---

## Database

- **Database**: PostgreSQL 16
- **ORM**: Hibernate / Spring Data JPA (`ddl-auto: none`)
- **Query Layer**: jOOQ for type-safe SQL queries
- **Connection Pool**: HikariCP (via Spring Boot default)
- **Config class**: `OLTPConfiguration` manages the jOOQ `DSLContext`

The database schema is managed externally via [udi-prime](udi-prime.md).

---

## Observability

- **Tracing**: OpenTelemetry via `opentelemetry-spring-boot-starter`
- **Metrics**: Micrometer + Actuator
- **Interactions**: All HTTP traffic is captured in `InteractionsFilter` and stored via `InteractionService`
- **Custom**: `UxReportableObservability` for UI-level reporting

---

## Health Checks

| Indicator | Class | What it checks |
|-----------|-------|---------------|
| Bundle Health | `BundleHealthIndicator` | That FHIR bundle processing is functional |
| Bundle Validate Health | `BundleValidateHealthIndicator` | That the FHIR validation engine is available |

Exposed at `/actuator/health`.

---

## Configuration Profiles

| Profile | File | Environment |
|---------|------|-------------|
| `sandbox` | `application-sandbox.yml` | Local / sandbox |
| `devl` | `application-devl.yml` | Development |
| `stage` | `application-stage.yml` | Staging |
| `phiqa` | `application-phiqa.yml` | PHI QA |
| `phiprod` | `application-phiprod.yml` | PHI Production |

Activated via `SPRING_PROFILES_ACTIVE` environment variable.

---

## Key Utility Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `Helpers` | `org.techbd.service.http` | Host/URL resolution, request helpers |
| `Interactions` | `org.techbd.service.http` | Interaction model and storage |
| `AWSUtil` | `org.techbd.util` | AWS Secrets Manager integration |
| `FHIRUtil` | `org.techbd.util` | FHIR-specific helper methods |
| `CsvConversionUtil` | `org.techbd.util` | CSV parsing and conversion |
| `DateUtil` | `org.techbd.util` | Date formatting |
| `JsonText` | `org.techbd.util` | JSON serialization helpers |
| `InterpolateEngine` | `org.techbd.util` | Template string interpolation |

---

## SFTP Integration

`SftpManager` and `SftpAccountsOrchctlConfig` manage SFTP connections for outbound delivery of processed bundles. Configuration is environment-driven.

---

## Session Management

- `SessionRegistry`: Tracks active HTTP sessions
- `SessionCleanupListener`: Cleans up stale sessions on context destroy

---

## Building and Running

```bash
# Build from root (builds all dependencies first)
mvn clean install

# Run hub-prime
cd hub-prime
mvn spring-boot:run

# Application available at http://localhost:8080
```

---

## Related Modules

- [hub-core-lib](hub-core-lib.md) — shared library (CSV converters, FHIR services, orchestration engine)
- [udi-prime](udi-prime.md) — PostgreSQL schema and jOOQ code generation
- [fhir-validation-service](fhir-validation-service.md) — standalone FHIR validator
