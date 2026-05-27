# Testing Guide

## Overview

Polyglot Prime uses a layered testing strategy:

| Layer | Technology | Location |
|-------|-----------|---------|
| Unit tests (Java) | JUnit 5 + AssertJ | `src/test/java` in each module |
| Database tests | pgTAP | `udi-prime/src/test/postgres` |
| API tests | Playwright + TypeScript | `api-automation/` |
| Smoke tests | HTTP-based scripts | `test-automation/` |
| FHIR IG conformance | JUnit 5 + HAPI FHIR | `hub-prime/src/test/java/.../IgPublicationIssuesTest.java` |

---

## Java Unit Tests

### Running Tests

```bash
# Run all tests in the entire monorepo
mvn test

# Run tests in a specific module
cd hub-prime
mvn test

# Run a single test class
mvn -Dtest=org.techbd.orchestrate.fhir.IgPublicationIssuesTest test

# Run tests matching a pattern
mvn -Dtest="*Fhir*" test
```

### Test Reports

After running `mvn test`, reports are in:
- **Surefire XML**: `target/surefire-reports/*.xml`
- **HTML report**: `mvn surefire-report:report` → `target/site/surefire-report.html`

### hub-prime Tests

Located in `hub-prime/src/test/java/org/techbd/`:

| Package | Description |
|---------|------------|
| `orchestrate/fhir/` | FHIR orchestration and IG conformance tests |
| `service/` | Service-layer unit tests |
| `util/` | Utility helper tests |

#### SHIN-NY FHIR IG Tests (`IgPublicationIssuesTest`)

The `IgPublicationIssuesTest` validates that the platform correctly processes
the official example bundles published at
`https://shinny.org/ImplementationGuide/HRSN/StructureDefinition-SHINNYBundleProfile-examples.html`.

**Test fixtures validated:**

1. `AHCHRSNQuestionnaireResponseExample`
2. `AHCHRSNScreeningResponseExample`
3. `NYScreeningResponseExample`
4. `ObservationAssessmentFoodInsecurityExample`
5. `ServiceRequestExample`
6. `TaskCompletedExample`
7. `TaskExample`
8. `TaskOutputProcedureExample`

**Running against a local IG version:**

Option A — Replace canonical URL references in fixture files:
```bash
sed -i 's|https://shinny.org/ImplementationGuide/HRSN|http://localhost:8000/ImplementationGuide/HRSN|g' \
    path/to/fixture.json
```

Option B — Redirect DNS by modifying `/etc/resolv.conf` to point `shinny.org`
to a local web server hosting the development IG.

#### IG Package Version Configuration

When updating the SHIN-NY IG version in `application.yml`:
1. Update `BaseIgValidationTest.getIgPackages()` in the test sources.
2. Download the latest example bundles to
   `hub-prime/src/test/resources/org/techbd/ig-examples/`.
3. Verify `OrchestrationEngineTest` and `IgPublicationIssuesTest` pass.

---

## Database Tests (pgTAP)

`udi-prime` uses [pgTAP](https://pgtap.org/) for database-level testing.

### Location

```
udi-prime/src/test/postgres/ingestion-center/
```

### Running pgTAP Tests

```bash
cd udi-prime

# Run migration + unit tests
./udictl.ts ic migrate --with-tests

# Run just the test suite against an existing DB
./udictl.ts ic test
```

### Test Utilities

A dedicated `techbd_udi_assurance` schema stores:
- `pgtap_fixtures_json` — JSON test fixtures used in pgTAP assertions.
- `pgtap_test_result` — recorded test outcomes for traceability.

---

## API Automation Tests (`api-automation`)

Located in `api-automation/`, this suite uses **Node.js + Playwright** to test
REST endpoints.

### Setup

```bash
cd api-automation
npm install
```

### Running Tests

```bash
# Run all tests (parallel by default)
npx playwright test

# Run a single test file
npx playwright test tests/FHIR-BundlePositive.test.ts

# Run negative-scenario tests
npx playwright test tests/FHIR-BundleNegative.test.ts
```

### Structure

| Path | Description |
|------|------------|
| `tests/FHIR-BundlePositive.test.ts` | Valid FHIR bundle submission tests |
| `tests/FHIR-BundleNegative.test.ts` | Invalid/malformed bundle tests |
| `sections/request_validate_data.ts` | Request builder helpers |
| `testdata/FHIR-Data/` | Sample FHIR bundle fixtures |
| `testdata/expectedValidationIssues.ts` | Expected validation error definitions |
| `utils/logger-util.ts` | Logging utilities |

---

## Smoke Tests (`test-automation`)

Environment-targeted smoke tests are organised by environment and data format:

| Directory | Environment | Format |
|-----------|------------|-------|
| `FHIR-Bundle-SmokeTest-Devl/` | Development | FHIR |
| `FHIR-Bundle-SmokeTest-PHI-QA/` | PHI QA | FHIR |
| `FHIR-Bundle-SmokeTest-Stage/` | Staging | FHIR |
| `CCDA-Bundle-SmokeTest-PHI-QA/` | PHI QA | CCDA |
| `CCDA-Bundle-SmokeTest-Stage/` | Staging | CCDA |
| `CSV-Bundle-SmokeTest-PHI-QA/` | PHI QA | CSV |
| `CSV-Bundle-SmokeTest-Stage/` | Staging | CSV |
| `HL7-Bundle-SmokeTest-PHI-QA/` | PHI QA | HL7v2 |
| `HL7-Bundle-SmokeTest-Stage/` | Staging | HL7v2 |

These tests send real payloads to the target environment and verify expected
responses.

---

## Integration Tests (`nexus-ingestion-api`)

Integration tests for the Nexus ingestion API are located in:
```
nexus-ingestion-api/src/test/java/org/techbd/ingest/integrationtests/
```

These require a running LocalStack (S3 + SQS) instance.  See
`support/nexus-ingestion-api/README.md` for LocalStack setup instructions.

---

## Testing Best Practices

1. **Java 21 required** for all JUnit tests; install via SDKMAN if needed.
2. **Avoid network calls** in unit tests; mock external services with Mockito
   or use `@SpringBootTest` with `@MockBean` for integration tests.
3. **Fixture data** should be stored under `src/test/resources/` in the
   relevant module.
4. **pgTAP tests** must be idempotent — they can be run multiple times
   without side effects.
5. **Do not remove or weaken tests** to make a build pass; fix the underlying
   issue instead.
