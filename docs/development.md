# Development Guide

## Prerequisites

| Tool | Minimum Version | Notes |
|------|----------------|-------|
| Java (JDK) | 21 LTS | Required for all Spring Boot modules. Install via [SDKMAN!](https://sdkman.io/) |
| Maven | 3.9+ | `mvn` must be on `$PATH` |
| PostgreSQL | 16 | Only required for full integration; see [udi-prime](modules/udi-prime.md) |
| Deno | 1.40+ | Required for `udi-prime` schema generation (`udictl.ts`) |
| Node.js | 18+ | Required for `api-automation` tests |
| Python | 3.10+ | Required for CSV validation scripts |
| direnv | any | Recommended for managing environment variables |
| Docker | 24+ | Optional; used for containerised local stack |

---

## Repository Setup

```bash
# 1. Clone
git clone https://github.com/tech-by-design/polyglot-prime.git
cd polyglot-prime

# 2. Configure environment variables
cp .envrc.example .envrc
# Edit .envrc — never commit secrets
direnv allow
```

### Key Environment Variables

| Variable | Required By | Description |
|----------|------------|-------------|
| `SPRING_PROFILES_ACTIVE` | All services | Active profile: `devl`, `sandbox`, `phiqa`, `stage`, `phiprod` |
| `${PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_URL` | All services | PostgreSQL JDBC URL for the active profile |
| `${PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_USERNAME` | All services | Database username |
| `${PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_PASSWORD` | All services | Database password |
| `TECHBD_BASE_FHIR_URL` | csv-service, hub-prime | Base URL for generated FHIR resources |
| `TECHBD_DATA_LEDGER_API_URL` | csv-service | Data Ledger API endpoint |
| `TECHBD_PYTHON_SCRIPT_PATH` | csv-service | Path prefix to Python validation scripts |

---

## Building the Project

### Build All Modules (from root)

```bash
mvn clean install
```

This builds and installs all Maven modules in dependency order:
`hub-core-lib` → `nexus-core-lib` → `fhir-validation-service` → `csv-service`
→ `nexus-ingestion-api` → `hub-prime`

### Build a Single Module

```bash
# Example: build only hub-prime (assumes dependencies already installed)
cd hub-prime
mvn clean package -DskipTests
```

### Skip Tests During Build

```bash
mvn clean install -DskipTests
```

---

## Running Services Locally

### hub-prime (Primary Hub)

```bash
cd hub-prime
mvn spring-boot:run
# Application starts at http://localhost:8080
# Swagger UI:    http://localhost:8080/swagger-ui/index.html
# Actuator:      http://localhost:8080/actuator/health
```

### fhir-validation-service

```bash
cd fhir-validation-service
mvn spring-boot:run
# Starts on the port configured in application.yml (default varies by profile)
```

### csv-service

```bash
cd csv-service
mvn spring-boot:run
```

### nexus-ingestion-api

```bash
cd nexus-ingestion-api
mvn spring-boot:run
# HTTP listener: port 8080 (configurable)
# TCP/MLLP listener: port 7980 (TCP_DISPATCHER_PORT)
```

---

## Project Structure Conventions

### Maven Multi-Module Layout

Each top-level directory is an independent Maven module:

```
polyglot-prime/
├── pom.xml                   # Parent POM (groupId: org.techbd, version: 0.x.y)
├── hub-prime/                # Spring Boot app — main entry point for FHIR data
├── hub-core-lib/             # Shared Java library (included by hub-prime, csv-service)
├── fhir-validation-service/  # Standalone FHIR validation service
├── csv-service/              # CSV / flat-file service
├── nexus-core-lib/           # Nexus-specific shared library
├── nexus-ingestion-api/      # Nexus multi-protocol ingestion service
└── udi-prime/                # Deno/TypeScript database layer (NOT a Maven module)
```

### Package Naming

All Java classes use the base package `org.techbd`, with sub-packages per module:

| Module | Base Package |
|--------|-------------|
| hub-prime | `org.techbd.service.http.hub.prime` / `org.techbd.controller` |
| hub-core-lib | `org.techbd` |
| fhir-validation-service | `org.techbd.fhir` |
| csv-service | `org.techbd.csv` |
| nexus-ingestion-api | `org.techbd.ingest` |
| nexus-core-lib | `org.techbd.corelib` |

### Configuration Properties

All modules use `@ConfigurationProperties` bound to the `org.techbd` prefix.
Application-specific properties are in `src/main/resources/application.yml`
(shared) and `application-{profile}.yml` (per-environment overrides).

---

## Code Style and Quality

- **Java 21** with records, sealed classes, and text blocks where appropriate.
- **Lombok** annotations (`@Getter`, `@Setter`, `@Builder`, etc.) reduce
  boilerplate; avoid mixing Lombok with hand-written accessors.
- **Spring idioms**: constructor injection (not field injection), `@Service`,
  `@Component`, `@Repository` stereotypes.
- **jOOQ** for all database access — avoid raw JDBC or JPA `@Query` for
  complex queries.

---

## Dependency Management

Third-party JARs that are not in Maven Central are stored in the module-level
`lib/` folder and referenced in `pom.xml` via `<scope>system</scope>` or a
local repository configuration. Do not commit transitive dependencies —
use Maven coordinates where possible.

---

## IDE Setup

### IntelliJ IDEA (recommended)

1. **Open** the root `pom.xml` as a Maven project.
2. **Mark** `src/main/java` as *Sources Root* and `src/test/java` as *Test
   Sources Root* in each module.
3. Install the **Lombok** plugin and enable annotation processing.
4. Set the **Project SDK** to Java 21.

### VS Code

1. Install the **Extension Pack for Java** (`vscjava.vscode-java-pack`).
2. Open the repository root.
3. The `.vscode/` directory contains recommended settings and launch
   configurations.

---

## Git Workflow

1. Create a feature branch from `main`: `git checkout -b feature/your-feature`
2. Make focused, incremental commits.
3. Run `mvn test` (at minimum in the affected module) before pushing.
4. Open a Pull Request targeting `main`.
5. CI/CD pipelines run automatically on PR creation.

---

## Versioning

The project version is managed via the Maven `${revision}` property in the
root `pom.xml` (e.g., `0.1160.0`).  The `flatten-maven-plugin` resolves the
CI-friendly version in published artifacts.  Increment the version in the root
`pom.xml` before releasing.
