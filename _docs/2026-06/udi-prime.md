# udi-prime

## Overview

`udi-prime` (Unified Data Intake — Prime) is the database tier of the Polyglot Prime platform. It provides the PostgreSQL schema definitions, database migration scripts, and jOOQ code generation tooling used by the Java services (`hub-prime`, `hub-core-lib`, `nexus-core-lib`, `csv-service`, `fhir-validation-service`).

`udi-prime` is **not a Maven module**. It is a TypeScript/Deno-based utility project that manages:
- PostgreSQL DDL (schema, tables, views, stored procedures)
- pgTAP database tests
- jOOQ code generation for type-safe SQL in the Java services
- Database migration and control via `udictl.ts`

---

## Directory Structure

```
udi-prime/
├── udictl.ts                        # Main CLI entry point (Deno/TypeScript)
├── src/
│   ├── main/
│   │   └── postgres/
│   │       └── ingestion-center/   # PostgreSQL DDL scripts
│   │           ├── *.sql           # Schema definitions, views, procedures
│   │           └── migrations/     # Version-ordered migration scripts
│   └── test/
│       └── postgres/
│           └── ingestion-center/   # pgTAP database unit tests
├── support/
│   └── jooq/
│       └── lib/                    # jOOQ generator JARs and config
└── lib/                            # External Deno/Java dependencies
```

---

## `udictl.ts` — Database Control CLI

`udictl.ts` is the main entry point for all database operations. It is a Deno TypeScript script that wraps `psql` and other tools.

### Usage

```bash
# Show available commands
deno run --allow-all udictl.ts --help

# Apply all DDL migrations
deno run --allow-all udictl.ts migrate

# Run pgTAP tests
deno run --allow-all udictl.ts test

# Generate jOOQ code
deno run --allow-all udictl.ts jooq-generate

# Show current database version
deno run --allow-all udictl.ts version
```

### Key Commands

| Command | Description |
|---------|-------------|
| `migrate` | Applies pending DDL migration scripts in version order |
| `test` | Runs pgTAP unit tests against the current schema |
| `jooq-generate` | Generates Java type-safe query classes from the live schema |
| `version` | Displays the current deployed schema version |
| `rollback` | Rolls back the most recent migration (if reversible) |

---

## PostgreSQL Schema (`ingestion-center`)

The `ingestion-center` schema is the central namespace for all interaction and healthcare data stored by the platform.

### Core Tables

| Table | Description |
|-------|-------------|
| `hub_operation_session` | Records each processing session (submission context) |
| `hub_interaction` | Master record for every inbound interaction (FHIR, HL7, CSV, CCDA) |
| `sat_interaction_http_request` | HTTP request details (headers, body, method, path) |
| `sat_interaction_http_response` | HTTP response details (status, body, headers) |
| `sat_interaction_fhir_request` | FHIR-specific request metadata |
| `sat_interaction_fhir_response` | FHIR validation outcome and response |
| `sat_interaction_hl7_response` | HL7 processing outcome |
| `sat_interaction_csv_response` | CSV processing and conversion outcome |
| `hub_exception` | Records processing exceptions with stack traces |
| `hub_tenant` | Registered tenant (Qualified Entity) records |

### Views

Views provide pre-joined, human-readable projections used by the hub-prime UI:

| View | Description |
|------|-------------|
| `interaction_detail` | Full join of HTTP + FHIR/HL7/CSV interaction for UI display |
| `fhir_validation_issue` | Flattened validation issues per bundle |
| `recent_interactions` | Last N interactions per tenant |
| `data_quality_summary` | Aggregated error/warning counts per tenant per day |

### Stored Procedures

| Procedure | Description |
|-----------|-------------|
| `register_interaction(...)` | Inserts a new hub_interaction + satellite records atomically |
| `interaction_http_request(...)` | Stores HTTP request data |
| `interaction_http_response(...)` | Stores HTTP response data |
| `purge_old_interactions(days)` | Deletes interactions older than the specified number of days |

---

## Data Vault Pattern

The schema follows a **Data Vault 2.0**-inspired pattern:
- **Hubs** (`hub_*`): Business keys — represent distinct business entities (interactions, tenants, sessions)
- **Satellites** (`sat_*`): Contextual attributes — time-variant descriptive data attached to hubs
- **Links** (`lnk_*`): Relationships between hubs

This pattern ensures:
- Full audit trail with no data overwriting
- Temporal query capability (what was the state at time T?)
- Extensibility without schema breaking changes

---

## jOOQ Code Generation

jOOQ classes are generated from the live PostgreSQL schema using the jOOQ generator configured in `support/jooq/`. The generated classes live in `hub-core-lib` and are used by `hub-prime`, `csv-service`, and `fhir-validation-service` for type-safe SQL queries.

### Generation Flow

```
PostgreSQL schema (udi-prime)
          │
          ▼
  jOOQ generator (support/jooq/lib/)
          │
          ▼
  Generated classes in hub-core-lib/src/main/java/
  (org.jooq.generated.* or similar package)
          │
          ▼
  Used by hub-prime, csv-service, fhir-validation-service
  via DSLContext for type-safe queries
```

### Running Code Generation

```bash
# From udi-prime directory
deno run --allow-all udictl.ts jooq-generate

# Or directly via Maven plugin configured in hub-core-lib
cd hub-core-lib
mvn generate-sources
```

---

## pgTAP Database Tests

Database-level unit tests are written in **pgTAP** (a TAP-compliant testing framework for PostgreSQL).

Tests verify:
- Stored procedure behavior
- View correctness
- Constraint enforcement
- Migration idempotency

```bash
# Run pgTAP tests
deno run --allow-all udictl.ts test
```

---

## Database Connection

All Java services connect to the UDI Prime database using the profile-substitution pattern:

```
${SPRING_PROFILES_ACTIVE}_TECHBD_UDI_DS_PRIME_JDBC_URL
${SPRING_PROFILES_ACTIVE}_TECHBD_UDI_DS_PRIME_USERNAME
${SPRING_PROFILES_ACTIVE}_TECHBD_UDI_DS_PRIME_PASSWORD
```

For example, with `SPRING_PROFILES_ACTIVE=devl`:
```
devl_TECHBD_UDI_DS_PRIME_JDBC_URL=jdbc:postgresql://db-host:5432/techbd_udi_prime
devl_TECHBD_UDI_DS_PRIME_USERNAME=techbd
devl_TECHBD_UDI_DS_PRIME_PASSWORD=secret
```

---

## Environments

| Environment | Profile | Description |
|-------------|---------|-------------|
| Sandbox | `sandbox` | Local development |
| Development | `devl` | Shared dev database |
| Staging | `stage` | Pre-production |
| PHI QA | `phiqa` | Regulated QA environment with real-ish data |
| PHI Production | `phiprod` | Production — PHI data |

---

## Prerequisites

- **Deno** 2.x — for running `udictl.ts`
- **PostgreSQL 16** — target database
- **psql** — PostgreSQL CLI client
- **Java 21** — for jOOQ code generation (invoked by `udictl.ts`)

---

## Related Modules

- [hub-prime](hub-prime.md) — primary consumer of the generated schema and jOOQ classes
- [hub-core-lib](hub-core-lib.md) — hosts generated jOOQ classes; provides `CoreUdiPrimeJpaConfig`
- [nexus-core-lib](nexus-core-lib.md) — also uses the UDI Prime JPA config
- [csv-service](csv-service.md) — persists CSV processing outcomes to this schema
- [fhir-validation-service](fhir-validation-service.md) — persists validation outcomes to this schema
