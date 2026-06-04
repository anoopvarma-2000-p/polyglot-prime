# test-automation

## Overview

`test-automation` contains smoke test bundles for validating the Polyglot Prime platform across multiple environments, data formats, and deployment stages. These are pre-packaged test payloads (HL7, FHIR, CCDA, CSV bundles) and associated scripts used to confirm that a deployed environment is accepting and processing healthcare data correctly.

- **Type**: Test data and smoke test scripts
- **Purpose**: Environment readiness verification after deployment
- **Formats covered**: FHIR, HL7 v2.x, CCDA, CSV

---

## Directory Structure

```
test-automation/
├── FHIR-Bundle-SmokeTest-Devl/        # FHIR smoke tests — Development
├── FHIR-Bundle-SmokeTest-PHI-QA/      # FHIR smoke tests — PHI QA
├── FHIR-Bundle-SmokeTest-Stage/       # FHIR smoke tests — Staging
├── CCDA-Bundle-SmokeTest-PHI-QA/      # CCDA smoke tests — PHI QA
├── CCDA-Bundle-SmokeTest-Stage/       # CCDA smoke tests — Staging
├── CSV-Bundle-SmokeTest-PHI-QA/       # CSV smoke tests — PHI QA
├── CSV-Bundle-SmokeTest-Stage/        # CSV smoke tests — Staging
├── HL7-Bundle-SmokeTest-PHI-QA/       # HL7 smoke tests — PHI QA
└── HL7-Bundle-SmokeTest-Stage/        # HL7 smoke tests — Staging
```

---

## Smoke Test Suites

### FHIR Bundle Smoke Tests

| Directory | Target Environment |
|-----------|-------------------|
| `FHIR-Bundle-SmokeTest-Devl` | Development (`devl`) |
| `FHIR-Bundle-SmokeTest-PHI-QA` | PHI QA (`phiqa`) |
| `FHIR-Bundle-SmokeTest-Stage` | Staging (`stage`) |

Each suite contains:
- Representative FHIR R4 Bundle JSON files (valid SHINNY-conformant bundles)
- Submission scripts (shell or HTTP collection files)
- Expected response files for comparison

**What they verify:**
- The `/Bundle` endpoint accepts valid submissions
- The FHIR validation service responds correctly
- Response `OperationOutcome` contains no errors for known-good payloads
- HTTP 200 is returned for valid bundles

---

### CCDA Bundle Smoke Tests

| Directory | Target Environment |
|-----------|-------------------|
| `CCDA-Bundle-SmokeTest-PHI-QA` | PHI QA |
| `CCDA-Bundle-SmokeTest-Stage` | Staging |

Each suite contains:
- C-CDA XML document samples
- Submission scripts targeting the CCDA ingestion endpoint
- Expected processing results

**What they verify:**
- CCDA documents are accepted and parsed correctly
- CCDA-to-FHIR conversion produces the expected FHIR resources
- No critical processing errors

---

### CSV Bundle Smoke Tests

| Directory | Target Environment |
|-----------|-------------------|
| `CSV-Bundle-SmokeTest-PHI-QA` | PHI QA |
| `CSV-Bundle-SmokeTest-Stage` | Staging |

Each suite contains:
- ZIP archives containing the four CSV file types:
  - `DEMOGRAPHIC_DATA.csv`
  - `QE_ADMIN_DATA.csv`
  - `SCREENING_PROFILE_DATA.csv`
  - `SCREENING_OBSERVATION_DATA.csv`
- Submission scripts targeting `/flatfile/csv/Bundle`
- Expected FHIR Bundle output for comparison

**What they verify:**
- All four CSV files are correctly parsed
- CSV-to-FHIR conversion produces a valid Bundle
- The FHIR validator accepts the converted Bundle
- Expected patient/observation records are present in the output

---

### HL7 v2.x Bundle Smoke Tests

| Directory | Target Environment |
|-----------|-------------------|
| `HL7-Bundle-SmokeTest-PHI-QA` | PHI QA |
| `HL7-Bundle-SmokeTest-Stage` | Staging |

Each suite contains:
- HL7 v2.x message files (`.hl7` or `.txt`)
- MLLP submission scripts or TCP send utilities
- Expected ACK responses and FHIR conversion outputs

**What they verify:**
- MLLP listener accepts HL7 v2.x messages on the expected port
- HL7-to-FHIR conversion completes without errors
- Correct ACK (`AA`) response is generated
- Converted FHIR resources appear in the interaction log

---

## Environment Matrix

| Format | Devl | PHI QA | Stage |
|--------|------|--------|-------|
| FHIR   | ✓    | ✓      | ✓     |
| CCDA   |      | ✓      | ✓     |
| CSV    |      | ✓      | ✓     |
| HL7    |      | ✓      | ✓     |

Development smoke tests focus on FHIR (the primary integration path). PHI QA and Stage environments cover all formats because they are closer to production behavior.

---

## Usage Pattern

Smoke tests are typically run:

1. **Post-deployment**: Immediately after a new release is deployed to verify the environment is healthy
2. **Pre-release sign-off**: As part of a release checklist before promoting to the next environment
3. **Incident triage**: To quickly determine if a reported issue is environment-wide or request-specific

```bash
# Example: Submit a FHIR bundle smoke test to Stage
cd FHIR-Bundle-SmokeTest-Stage
./submit.sh https://hub-prime.stage.techbd.org $API_KEY

# Example: Submit a CSV smoke test to PHI QA
cd CSV-Bundle-SmokeTest-PHI-QA
./submit.sh https://csv-service.phiqa.techbd.org $API_KEY
```

---

## Relationship to Other Test Modules

| Module | Type | Depth | When Run |
|--------|------|-------|----------|
| `test-automation` | Smoke tests | Shallow (happy path only) | Post-deploy |
| `api-automation` | Integration tests | Deep (positive + negative) | Pre-merge / CI |
| `support/testcases` | Manual test cases | Exploratory | Release QA |
| `udi-prime` pgTAP | Database unit tests | Deep (schema level) | Database migrations |

---

## Related Modules

- [hub-prime](hub-prime.md) — FHIR API under test
- [csv-service](csv-service.md) — CSV endpoint under test
- [nexus-ingestion-api](nexus-ingestion-api.md) — HL7/CCDA ingestion under test
- [api-automation](api-automation.md) — deeper automated integration tests
- [support](support.md) — manual test case documentation
