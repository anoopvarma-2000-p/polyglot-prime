# udi-prime

## Overview

**udi-prime** is the **Universal Data Infrastructure (UDI)** layer for Polyglot
Prime.  It manages the PostgreSQL database schema used by all ingestion
services and provides type-safe Java database access via auto-generated
[jOOQ](https://www.jooq.org/) code.

Unlike the other modules, `udi-prime` is **not** a Maven/Java project.  It is a
**Deno / TypeScript** project that uses the
[SQL Aide](https://github.com/netspective-labs/sql-aide) library to:

1. Define the database schema as TypeScript code (type-safe DDL)
2. Generate PostgreSQL migration scripts (`.sql` / `.psql` files)
3. Execute migrations against a live PostgreSQL database via `psql`
4. Generate a jOOQ JAR from live database metadata via JDBC
5. Produce SchemaSpy HTML documentation

---

## Directory Layout

```
udi-prime/
├── udictl.ts                          # Main CLI entry point (Deno)
├── src/
│   ├── main/
│   │   └── postgres/
│   │       └── ingestion-center/
│   │           ├── migrations/        # TypeScript migration definitions
│   │           │   ├── models-dv.ts   # Data Vault 2.0 hub/satellite definitions
│   │           │   ├── migrate-basic-infrastructure.ts
│   │           │   ├── migrate-models-dv.ts
│   │           │   ├── migrate-ddl-stored-routine-interaction.ts
│   │           │   ├── migrate-cron.ts
│   │           │   └── ...
│   │           └── *.psql             # Raw PostgreSQL scripts
│   └── test/
│       └── postgres/
│           └── ingestion-center/      # pgTAP test suites
├── support/
│   └── jooq/
│       └── lib/                       # jOOQ JAR and JDBC drivers
└── lib/                               # External dependencies
```

---

## Database Schema

The schema follows a **Data Vault 2.0** pattern with three PostgreSQL schemas:

### techbd_udi_ingress (primary)

Stores all inbound interaction data.

**Hubs** (unique business keys):

| Table | Description |
|-------|------------|
| `hub_interaction` | Every inbound request, identified by UUID |
| `hub_fhir_bundle` | Unique FHIR bundle identifiers |
| `hub_uniform_resource` | Uniform resources (files, URLs, etc.) |
| `hub_exception` | System-level exceptions |

**Satellites** (descriptive data about hubs):

| Table | Description |
|-------|------------|
| `sat_interaction_http_request` | HTTP metadata for every interaction |
| `sat_interaction_fhir_request` | FHIR-specific request details |
| `sat_interaction_fhir_validation_issue` | Per-issue validation results |
| `sat_interaction_fhir_screening_patient` | Extracted patient info from FHIR |
| `sat_interaction_fhir_screening_organization` | Extracted org info |
| `sat_interaction_fhir_session_diagnostic` | Session-level diagnostics |
| `sat_interaction_flat_file_csv_request` | CSV/ZIP upload details |
| `sat_interaction_hl7_request` | HL7v2 interaction metadata |
| `sat_interaction_hl7_validation_errors` | HL7 validation issues |
| `sat_interaction_ccda_request` | CCDA interaction metadata |
| `sat_interaction_ccda_validation_errors` | CCDA validation issues |
| `sat_interaction_file_exchange` | File exchange tracking |
| `sat_interaction_zip_file_request` | ZIP file submission metadata |
| `sat_interaction_user` | User/session information |
| `sat_diagnostic_log` | Application diagnostic log entries |
| `sat_expectation_http_request` | Expected HTTP request patterns |

**Links** (relationships between hubs):

| Table | Description |
|-------|------------|
| `lnk_uniform_resource_fhir_bundle` | Link between resources and FHIR bundles |

**Reference / support tables**:

| Table | Description |
|-------|------------|
| `ref_code_lookup` | Code lookup reference table |
| `json_action_rule` | JSON transformation/action rules |
| `users` | Application user records |

### techbd_udi_assurance

| Table | Description |
|-------|------------|
| `pgtap_fixtures_json` | JSON fixtures for pgTAP database tests |
| `pgtap_test_result` | Recorded pgTAP test outcomes |

### techbd_udi_diagnostics

Diagnostic and exception tracking tables.

---

## Prerequisites

- [Deno](https://deno.land/) ≥ 1.40
- PostgreSQL 16 accessible from the machine
- `.pgpass` file configured (or passwords available via environment variables)
- ISLM migration schemas installed (part of the SQL Aide package)

---

## Usage

### udictl.ts CLI

The primary CLI is `udi-prime/udictl.ts`.

```bash
cd udi-prime

# Full refresh (generate + migrate + generate jOOQ JAR)
./udictl.ts ic omnibus-fresh

# Full refresh and copy JAR to dependent modules
./udictl.ts ic omnibus-fresh --deploy-jar

# Individual commands
./udictl.ts ic generate --help        # list code generation options
./udictl.ts ic generate sql           # generate all *.sql files
./udictl.ts ic generate java jooq     # generate jOOQ JAR from live DB
./udictl.ts ic generate docs          # generate SchemaSpy HTML docs
./udictl.ts ic generate docs --serve 4343  # serve docs at localhost:4343
./udictl.ts ic migrate                # run pending migrations
./udictl.ts ic test                   # run pgTAP tests
```

### jOOQ Code Generation

The jOOQ JAR is generated from live database metadata (JDBC introspection) and
stored in `udi-prime/support/jooq/`.  It is referenced as a local dependency
by `hub-prime`, `csv-service`, and `fhir-validation-service`.

After regenerating the JAR, run `mvn clean` in the dependent modules to pick
up the new version.

---

## Migration Workflow

Migrations use the **ISLM (Information Schema Lifecycle Management)** pattern
from SQL Aide:

1. Each migration is a TypeScript file that defines a stored procedure
   `migrate_v<timestamp>_<description>_idempotent()`.
2. The ISLM framework tracks which migrations have been applied.
3. Migrations are idempotent — safe to re-run.
4. Run `./udictl.ts ic migrate` to apply all pending migrations.

---

## pgTAP Tests

Database tests are in `src/test/postgres/ingestion-center/` and use the
[pgTAP](https://pgtap.org/) framework.

```bash
./udictl.ts ic migrate --with-tests   # migrate + run tests
```

Test fixtures (JSON payloads) are stored in `techbd_udi_assurance.pgtap_fixtures_json`.

---

## SchemaSpy Documentation

SchemaSpy generates interactive HTML documentation of the database schema:

```bash
./udictl.ts ic generate docs --serve 4343
# Open http://localhost:4343 in a browser
```

The generated documentation includes:
- Entity-relationship diagrams
- Table descriptions and column definitions
- Constraint and index details
- Relationship navigation
