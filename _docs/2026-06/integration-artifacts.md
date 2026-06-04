# integration-artifacts

## Overview

`integration-artifacts` is a configuration and template repository containing the integration definitions, routing rules, transformation scripts, and lookup tables used by the Polyglot Prime platform's integration layer. These artifacts are deployed to integration middleware (such as Mirth Connect or similar HL7 integration engines) and govern how healthcare messages are transformed, routed, and dispatched.

- **Type**: Configuration / Template repository
- **Language**: XML, JavaScript (Mirth channel scripts), SQL, JSON
- **Primary consumer**: Integration engine (Mirth Connect or equivalent)

---

## Directory Structure

```
integration-artifacts/
├── version.json                 # Version tracking for artifact releases
├── README.md                    # Integration artifact documentation
├── Router/                      # Message routing channel configurations
├── aws-queue-listener/          # AWS SQS listener channel setup
├── ccda/                        # C-CDA transformation templates
├── code-templates/              # Reusable code template library
├── custom-lib/                  # Custom JavaScript/Java libraries
├── fhir/                        # FHIR-specific transformation artifacts
├── flatfile/                    # Flat file (CSV) processing templates
├── global-scripts/              # Shared scripts across all channels
├── hl7v2/                       # HL7 v2.x transformation templates
└── lookup-manager/              # Lookup table configurations and data
```

---

## Component Descriptions

### `Router/`

Contains channel definitions for the central message router. The router:
- Receives inbound messages from all protocol listeners (MLLP, TCP, HTTP)
- Inspects message headers/content to determine routing destination
- Dispatches to the appropriate downstream processor (S3, SQS, FHIR service, etc.)

Key files:
- Channel XML definitions (one per logical routing path)
- Routing rule scripts
- Destination connector configurations

---

### `aws-queue-listener/`

Configuration for a dedicated channel that listens to AWS SQS queues:
- Polls SQS FIFO queues for messages published by `nexus-ingestion-api`
- Deserializes message payloads
- Routes to downstream transformation channels
- Handles SQS message acknowledgement (delete-on-success)

---

### `ccda/`

C-CDA (Consolidated Clinical Document Architecture) transformation artifacts:
- **XSLT stylesheets**: Transform C-CDA XML documents into FHIR R4 resources
- **Channel scripts**: Pre- and post-transformation JavaScript for C-CDA channels
- **Mapping rules**: Element-level field mappings (ClinicalDocument → Patient, Encounter, Observation, etc.)
- **Test CCD-A documents**: Sample documents for channel testing

Supported C-CDA source systems:
- AthenaHealth
- Epic
- Medent
- Generic C-CDA (standard-conformant)

---

### `code-templates/`

Reusable JavaScript code templates shared across multiple channels:
- Message parsing helpers
- Error handling templates
- Logging templates
- HTTP client utilities
- Data transformation utilities

In Mirth Connect, code templates are imported into channels by reference, promoting DRY (Don't Repeat Yourself) configuration.

---

### `custom-lib/`

Custom Java or JavaScript libraries bundled for use within the integration engine:
- Extended FHIR utility functions
- HL7 message builder helpers
- Custom validators
- Tenant-specific business logic

---

### `fhir/`

FHIR-specific integration artifacts:
- FHIR Bundle assembly templates
- Resource reference resolution helpers
- IG-specific transformation rules
- Submission scripts for the FHIR REST API

---

### `flatfile/`

Flat file (CSV) processing templates:
- CSV parsing channel definitions
- Column-to-field mapping configurations
- CSV validation rules
- Batch file ingestion scripts

These mirror the conversion logic in `csv-service` but at the integration engine layer for environments where the Java service is not in the path.

---

### `global-scripts/`

JavaScript utility scripts available to all channels:
- Common logging functions (`logInfo`, `logError`, `logWarning`)
- Tenant resolution and lookup functions
- HTTP request helper (`sendHttpRequest`)
- JSON utility functions
- UUID generation
- Date/time formatting

Global scripts in Mirth Connect are loaded once and available in the scope of every channel.

---

### `hl7v2/`

HL7 v2.x transformation templates:
- Message type–specific transformation scripts (ADT, ORU, MDM, etc.)
- HL7-to-FHIR field mappings
- ACK (acknowledgement) generation templates
- Segment parsers (MSH, PID, PV1, OBX, etc.)
- HL7 message validators

Supported HL7 message types:
- `ADT^A01` (Admit/Visit notification)
- `ADT^A03` (Discharge)
- `ORU^R01` (Observation results)
- `MDM^T02` (Document notification)

---

### `lookup-manager/`

Lookup table definitions and data:
- Code system mappings (local codes → SNOMED, LOINC, ICD-10)
- Tenant-to-routing configuration mappings
- Organization identifier mappings
- Value set reference data

These tables are loaded at channel startup and used during message transformation to resolve display names, standard codes, and routing targets.

---

## Version Tracking

`version.json` records the current version of the artifact bundle, enabling:
- Coordinated deployment with Java service versions
- Rollback to a previous artifact version if needed
- Audit trail for which artifact version processed which messages

```json
{
  "version": "0.1167.0",
  "released": "2025-xx-xx",
  "description": "Integration artifact bundle for polyglot-prime v0.1167.0"
}
```

---

## Deployment

Artifacts in this directory are deployed to the integration engine using the project's CI/CD pipeline or manually via the engine's import tool:

```bash
# Import all channels to Mirth Connect
mirth-cli import --dir Router/ --server https://mirth.techbd.org --user admin

# Import CCDA templates
mirth-cli import --dir ccda/ --server https://mirth.techbd.org --user admin
```

---

## Related Modules

- [nexus-ingestion-api](nexus-ingestion-api.md) — Java service that feeds messages into SQS, which these artifacts consume
- [hub-prime](hub-prime.md) — FHIR API that receives forwarded messages from these channels
- [csv-service](csv-service.md) — Java service handling CSV processing (parallel to `flatfile/` artifacts)
- [test-automation](test-automation.md) — smoke tests verify end-to-end behavior of these integration channels
- [support](support.md) — specifications and documentation for integration channel design
