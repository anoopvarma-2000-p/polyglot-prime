# API Reference

All services expose a Swagger / OpenAPI UI at `/swagger-ui/index.html` when running.

---

## hub-prime Endpoints

Base URL (local): `http://localhost:8080`

### FHIR Endpoints (`/`)

#### `GET /metadata`
Returns the FHIR server's capability/conformance statement (XML).

---

#### `POST /Bundle`
Validate, persist, and forward a FHIR bundle to the SHIN-NY Data Lake.

**Headers (required)**

| Header | Description |
|--------|------------|
| `X-TechBD-Tenant-ID` | Tenant identifier (mandatory) |
| `Content-Type` | `application/json` or `application/fhir+json` |

**Optional headers**

| Header | Description |
|--------|------------|
| `X-TechBD-DataLake-API-URL` | Override the default SHIN-NY Data Lake URL |
| `X-TechBD-IG-Version` / `X-SHIN-NY-IG-Version` | Specify the SHIN-NY IG version to validate against |
| `X-Correlation-ID` | Client-provided correlation ID |
| `X-TechBD-Validation-Severity-Level` | Override validation severity (`fatal`, `error`, `warning`, `information`) |

**Response**: `application/json` — OperationOutcome with validation results and
forwarding status.

---

#### `POST /Bundle/$validate`
Validate a FHIR bundle **without** storing or forwarding it.

Same headers as `POST /Bundle`.

**Response**: `application/json` — OperationOutcome with validation results
only.

---

#### `GET /Bundle/$status/{bundleSessionId}`
Poll the processing status of an async bundle submission.

**Path variable**: `bundleSessionId` — the interaction ID returned by a
previous `POST /Bundle` call.

**Response**: JSON with `status` and `message` fields, or the full
OperationOutcome if processing is complete.

---

#### `GET /mock/shinny-data-lake/1115-validate/{resourcePath}.json`
Returns mock JSON responses that simulate the SHIN-NY Data Lake 1115 Waiver
validation (scorecard) server.  For testing and development only.

---

### CSV Endpoints

#### `POST /flatfile/csv/Bundle/$validate`
Validate and process a ZIP file containing CSV flat-file data.

**Content-Type**: `multipart/form-data`

**Form fields**

| Field | Type | Description |
|-------|------|------------|
| `file` | `MultipartFile` | ZIP archive containing CSV files |

**Headers**

| Header | Description |
|--------|------------|
| `X-TechBD-Tenant-ID` | Tenant identifier (mandatory) |
| `X-TechBD-Immediate` | `true` for synchronous processing; omit/`false` for async |

**Response**: JSON OperationOutcome or async acknowledgement with
`interactionId`.

---

### UI / Dashboard Endpoints

The hub-prime Thymeleaf UI exposes browser pages including:

| Path | Description |
|------|------------|
| `/` | Dashboard home |
| `/login` | Authentication page |
| `/interactions` | Interaction history viewer |
| `/docs/**` | Dynamic documentation resources |

---

## fhir-validation-service Endpoints

This service exposes the same FHIR endpoint contract as hub-prime but **only**
validates — it does not forward to the Data Lake.

Base URL: configured per deployment (default `http://localhost:<port>`).

#### `POST /Bundle/$validate`
Validate a FHIR bundle against the SHIN-NY IG.

**Headers**: same as hub-prime's `/Bundle/$validate`.

**Response**: OperationOutcome JSON.

---

#### `GET /Bundle/$status/{bundleSessionId}`
Check the status of an async validation request.

---

## csv-service Endpoints

Standalone CSV validation service (same contract as hub-prime's CSV endpoint).

#### `POST /flatfile/csv/Bundle/$validate`
See hub-prime's CSV endpoint above for full specification.

---

## nexus-ingestion-api Endpoints

### HTTP Endpoints

#### `POST /ingest`
Accept a payload over HTTP.

**Headers**

| Header | Description |
|--------|------------|
| `X-TechBD-Tenant-ID` | Tenant identifier |
| `Content-Type` | Payload content type (e.g., `application/json`, `text/xml`) |

**Response**: JSON with `interactionId` and processing status.

---

#### `POST /ws` / `POST /xds/XDSbRepositoryWS`
SOAP-over-HTTP endpoints.

The service detects SOAP envelopes by namespace and routes them accordingly.
Returns a standards-compliant SOAP response envelope.

---

### TCP / MLLP

The service listens on **port 7980** (`TCP_DISPATCHER_PORT`) for raw TCP and
MLLP connections.  Frame boundaries are detected by MLLP start (`0x0B`) and
end (`0x1C 0x0D`) bytes.

**Response**: HL7 ACK or NACK message in MLLP framing.

---

## Common Response Formats

### OperationOutcome (FHIR)

```json
{
  "OperationOutcome": {
    "validationResults": [
      {
        "profileUrl": "https://shinny.org/us/ny/hrsn/...",
        "issues": [
          {
            "severity": "error",
            "message": "...",
            "location": "Bundle.entry[0]"
          }
        ]
      }
    ]
  }
}
```

### Async Acknowledgement

```json
{
  "status": "Accepted",
  "interactionId": "550e8400-e29b-41d4-a716-446655440000",
  "message": "Processing started asynchronously"
}
```

### Error Response

```json
{
  "status": "Error",
  "message": "An unexpected system error occurred."
}
```

---

## Request Headers Reference

| Header | Services | Description |
|--------|---------|-------------|
| `X-TechBD-Tenant-ID` | All | Mandatory tenant identifier |
| `X-TechBD-DataLake-API-URL` | hub-prime | Override SHIN-NY Data Lake URL |
| `X-TechBD-IG-Version` | hub-prime, fhir-validation-service | Request specific IG version |
| `X-SHIN-NY-IG-Version` | hub-prime | Alternative IG version header |
| `X-Correlation-ID` | All | Client-provided request correlation ID |
| `X-TechBD-Validation-Severity-Level` | hub-prime, fhir-validation-service | Validation severity threshold |
| `X-TechBD-Base-FHIR-URL` | hub-prime | Override base FHIR URL for resource generation |
| `X-TechBD-Immediate` | csv-service | `true` = synchronous processing |
| `X-TechBD-Override-Request-URI` | hub-prime | Override the request URI recorded in the database |
| `X-TechBD-HealthCheck` | All | Health-check marker; skip normal processing |
| `X-Amzn-Mtls-Clientcert` | nexus-ingestion-api | URL-encoded PEM client certificate (injected by ALB) |
