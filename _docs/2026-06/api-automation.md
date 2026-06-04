# api-automation

## Overview

`api-automation` is the TypeScript/Playwright-based API test automation framework for the Polyglot Prime platform. It provides automated integration tests for REST API endpoints, validates FHIR bundle submission and rejection behavior, and serves as the primary regression suite for `hub-prime`'s FHIR API.

- **Language**: TypeScript
- **Framework**: Playwright (API testing mode)
- **Runtime**: Node.js
- **Scope**: REST API testing (not UI testing)

---

## Directory Structure

```
api-automation/
├── playwright.config.ts           # Playwright configuration
├── package.json                   # Node.js dependencies
├── tsconfig.json                  # TypeScript configuration
├── sections/
│   └── request_validate_data.ts   # Reusable request/response validation helpers
├── testdata/
│   ├── expectedValidationIssues.ts # Expected FHIR validation error catalogs
│   └── FHIR-Data/                  # FHIR bundle JSON test fixtures
│       ├── valid/                  # Bundles expected to pass validation
│       └── invalid/                # Bundles expected to fail with known errors
├── tests/
│   ├── FHIR-BundlePositive.test.ts # Tests for valid bundle acceptance
│   └── FHIR-BundleNegative.test.ts # Tests for invalid bundle rejection
└── utils/
    └── logger-util.ts              # Test logging utilities
```

---

## Test Suites

### Positive Tests (`FHIR-BundlePositive.test.ts`)

Verifies that valid FHIR R4 Bundles conforming to the SHINNY IG are:
- Accepted with HTTP 200 OK
- Returned with a valid `OperationOutcome` containing no `error`-severity issues
- Persisted correctly (verifiable via GET requests)

### Negative Tests (`FHIR-BundleNegative.test.ts`)

Verifies that invalid FHIR Bundles are:
- Rejected with appropriate HTTP status codes (4xx)
- Returned with an `OperationOutcome` containing specific expected errors
- Match the expected error catalog in `expectedValidationIssues.ts`

---

## Test Data

### FHIR Bundle Fixtures (`testdata/FHIR-Data/`)

JSON files representing FHIR R4 Bundles in various states:
- Complete and valid SHINNY-conformant bundles
- Bundles missing required fields (Patient.name, Encounter.status, etc.)
- Bundles with invalid code values
- Bundles with referential integrity violations

### Expected Validation Issues (`expectedValidationIssues.ts`)

A catalog of known validation errors keyed by bundle type and IG version. Used to assert that the service returns exactly the expected set of validation issues, preventing regression where a fix silently removes a different validation check.

---

## Configuration

### `playwright.config.ts`

| Setting | Description |
|---------|-------------|
| `baseURL` | Target API base URL (from environment: `BASE_URL`) |
| `timeout` | Per-request timeout (default: 30 000 ms) |
| `retries` | Number of retries on failure (default: 0 for API tests) |
| `reporter` | HTML reporter + console output |
| `use.extraHTTPHeaders` | Default headers including `Content-Type: application/fhir+json` and auth headers |

### Environment Variables

```
BASE_URL          # Target API base URL, e.g. https://hub-prime.sandbox.techbd.org
API_KEY           # API key / Bearer token for authenticated requests
SHINNY_IG_VERSION # IG version used to select expected validation issues
```

---

## Reusable Helpers (`sections/request_validate_data.ts`)

Provides typed wrapper functions for common test operations:

| Function | Description |
|----------|-------------|
| `submitBundle(bundle)` | POST a FHIR Bundle to `/Bundle` and return the parsed `OperationOutcome` |
| `validateBundle(bundle)` | POST to `/Bundle/$validate` without persisting |
| `assertNoErrors(outcome)` | Assert OperationOutcome has no `error`-severity issues |
| `assertHasErrors(outcome, expected)` | Assert outcome contains all expected error messages |
| `assertHttpStatus(response, status)` | Assert HTTP response status code |

---

## Running Tests

```bash
# Install dependencies
npm install

# Run all API tests against sandbox
BASE_URL=https://hub-prime.sandbox.techbd.org npx playwright test

# Run only positive tests
npx playwright test tests/FHIR-BundlePositive.test.ts

# Run with HTML report
npx playwright test --reporter=html
open playwright-report/index.html

# Run in CI mode (no browser)
npx playwright test --reporter=line
```

---

## CI Integration

The test suite is designed for CI/CD pipelines:
- Exit code 0 on all passing, non-zero on any failure
- JUnit XML output supported via Playwright's `junit` reporter
- Can be parameterized with `BASE_URL` to target different environments

---

## Related Modules

- [hub-prime](hub-prime.md) — primary API under test
- [fhir-validation-service](fhir-validation-service.md) — validation behavior under test
- [test-automation](test-automation.md) — smoke tests (simpler, environment-focused)
- [support](support.md) — test specifications and manual test cases
