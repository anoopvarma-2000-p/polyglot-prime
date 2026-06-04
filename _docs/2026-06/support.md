# support

## Overview

The `support` directory is the project's non-production work product repository. It contains technical specifications, release documentation, quality assurance materials, utility scripts, test case definitions, and operational tooling that support the development, deployment, and maintenance of the Polyglot Prime platform.

- **Type**: Documentation and operational tooling
- **Contains**: Scripts, specs, release notes, QA materials, containers config

---

## Directory Structure

```
support/
├── archive/              # Historical artifacts and deprecated materials
├── bin/                  # Utility scripts and operational binaries
├── nexus-ingestion-api/  # Nexus API-specific documentation and support
├── nyec-ig-version/      # New York State HRSN IG versioning tools
├── qualityfolio/         # Quality metrics, dashboards, and QA documentation
├── release-notes/        # Version-by-version release documentation
├── specifications/       # Technical specifications and design documents
├── support/              # Nested support materials (team runbooks, etc.)
└── testcases/            # Manual test case definitions
```

---

## `bin/`

Utility scripts for operational and development tasks:
- Database management scripts (backup, restore, anonymize PHI)
- Deployment helper scripts
- Environment setup scripts
- Certificate management utilities
- Log extraction and parsing tools

---

## `nexus-ingestion-api/`

Documentation and support materials specific to the Nexus Ingestion API:
- API integration guides for external partners
- MLLP / TCP connection setup guides
- SOAP endpoint WSDL and documentation
- Port configuration reference
- Troubleshooting guides

---

## `nyec-ig-version/`

Tooling for managing New York State HRSN (Health-Related Social Needs) Implementation Guide versions:
- Scripts to fetch and compare IG package versions from the FHIR registry
- Changelogs between IG versions (e.g., SHINNY v1.8.1 → v1.9.1)
- Validation output comparisons across IG versions
- Guidance on upgrading to a new IG version

This tooling ensures that when the state updates the IG, the platform team can assess the impact on existing submissions before updating the production validator.

---

## `qualityfolio/`

Quality assurance documentation and dashboards:
- Quality metrics definitions and SLAs
- Validation error rate dashboards
- Data completeness reports
- Tenant onboarding quality checklists
- QA sign-off templates for releases

---

## `release-notes/`

Versioned release documentation:
- Per-version changelogs
- Breaking change notices
- Migration guides for external API consumers
- Hotfix notes

Release notes follow the project version scheme (`0.1167.0`, etc.) matching `pom.xml`.

---

## `specifications/`

Technical specifications and design documents:

| Document Type | Description |
|--------------|-------------|
| API specifications | REST API endpoint contracts, request/response schemas |
| Data format specs | CSV file format definitions, column requirements, validation rules |
| Integration specs | MLLP connection parameters, SOAP WSDL, XDS integration profiles |
| Security specs | Authentication flows, authorization model, encryption requirements |
| IG conformance specs | How the platform maps to the SHINNY and US Core IGs |
| Architecture documents | System topology, component interaction diagrams |

---

## `testcases/`

Manual test case definitions for QA engineers and release sign-off:
- Test case IDs and descriptions
- Pre-conditions and test data requirements
- Step-by-step test procedures
- Expected results
- Pass/fail criteria

Organized by:
- Feature area (FHIR ingestion, CSV processing, HL7 ingestion, authentication, UI)
- Test level (smoke, functional, regression, negative)
- Environment (devl, stage, phiqa, phiprod)

---

## `archive/`

Historical materials that are no longer active but retained for reference:
- Deprecated API versions
- Old integration channel configurations
- Previous IG versions and their test outputs
- Legacy data format definitions

---

## Relationship to Other Modules

| `support/` content | Related module |
|-------------------|---------------|
| API specifications | [hub-prime](hub-prime.md), [nexus-ingestion-api](nexus-ingestion-api.md) |
| CSV format specs | [csv-service](csv-service.md), [hub-core-lib](hub-core-lib.md) |
| IG version tooling | [fhir-validation-service](fhir-validation-service.md) |
| Integration channel guides | [integration-artifacts](integration-artifacts.md) |
| Manual test cases | [test-automation](test-automation.md), [api-automation](api-automation.md) |
| Database scripts | [udi-prime](udi-prime.md) |

---

## Usage

```bash
# Run a utility script
bash support/bin/backup-db.sh $DB_HOST $DB_NAME

# Check IG version differences
deno run --allow-all support/nyec-ig-version/compare.ts v1.8.1 v1.9.1
```
