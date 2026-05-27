# nexus-ingestion-api

## Overview

**nexus-ingestion-api** is a **source-agnostic, multi-protocol data ingestion
gateway**.  It accepts healthcare data payloads over three transport protocols:

- **HTTP / HTTPS** — REST-style `POST /ingest` and SOAP-over-HTTP
- **MLLP (Minimum Lower Layer Protocol)** — used by HL7v2 systems
- **SOAP / XDS** — IHE XDS.b document repository endpoints

Every message is:
1. Assigned a unique `interactionId` (UUID)
2. Written to an **AWS S3 bucket** (payload + metadata JSON)
3. Published to an **AWS SQS FIFO queue** for downstream processing
4. Acknowledged to the client in the appropriate protocol format (HL7 ACK,
   SOAP response, or JSON)

---

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Runtime | Java 21, Spring Boot 3.3 |
| TCP/MLLP | Netty (custom TCP listener) |
| Storage | AWS S3 |
| Messaging | AWS SQS FIFO |
| Security | mTLS (ALB pass-through) |
| Observability | CloudWatch |
| Build | Maven |

---

## Module Location

```
nexus-ingestion-api/
├── src/main/java/org/techbd/ingest/
│   ├── NexusIngestionApiApplication.java   # Spring Boot entry point
│   ├── commons/                             # Shared constants and helpers
│   ├── config/
│   │   ├── AppConfig.java                   # Service configuration
│   │   └── PortConfig.java                  # Port configuration (loaded from S3)
│   ├── controller/
│   │   └── InteractionsFilter.java          # Servlet filter (mTLS, interaction ID)
│   ├── endpoint/                             # HTTP endpoint handlers
│   ├── exceptions/                           # Exception types
│   ├── feature/                              # Feature flags
│   ├── interceptors/                         # Request interceptors
│   ├── listener/                             # TCP/MLLP listener
│   ├── model/                                # Domain models
│   ├── processor/                            # Message processors per protocol
│   ├── service/
│   │   └── portconfig/
│   │       └── PortResolverService.java      # Port → PortEntry resolution
│   └── util/                                 # Utility classes
└── src/main/resources/
    ├── application.yml
    └── list.json                             # Local port config (dev/sandbox)
```

---

## High-Level Flow

```
Inbound connection (HTTP / MLLP / SOAP)
    │
    ├─► Protocol detection
    │       ├── SOAP namespace present → SOAP processor
    │       ├── MLLP start-byte (0x0B) → MLLP processor
    │       └── HTTP path → HTTP processor
    │
    ├─► PROXY protocol header → extract destination port + client IP
    │
    ├─► PortResolverService.resolve(requestContext)
    │       └── Match PortEntry from S3 / local config
    │
    ├─► mTLS validation (if PortEntry.mtls is set)
    │       ├── Parse X-Amzn-Mtls-Clientcert header
    │       ├── Load CA bundle from S3
    │       └── PKIX certificate chain validation
    │
    ├─► Assign interactionId (UUID)
    │
    ├─► Write payload to S3
    │       └── s3://<bucket>/<dataDir>/YYYY/MM/DD/<interactionId>.json
    │
    ├─► Write metadata to S3
    │       └── s3://<bucket>/<metadataDir>/YYYY/MM/DD/<interactionId>-metadata.json
    │
    ├─► Publish to SQS FIFO queue
    │       └── messageGroupId = destination + source metadata
    │
    └─► Return ACK / SOAP response / JSON
```

---

## Port Configuration

Every inbound request is matched against a list of `PortEntry` records.

**In production**: loaded from S3 bucket (`PORT_CONFIG_S3_BUCKET`).  
**In development**: loaded from `src/main/resources/list.json`.

### PortEntry Fields

| Field | Type | Description |
|-------|------|-------------|
| `port` | int | Listening port number |
| `protocol` | String | `HTTP` or `TCP` |
| `responseType` | String | ACK format: `mllp`, `pnr`, `pix`, JSON |
| `execType` | String | `sync` or `async` |
| `queue` | String | SQS FIFO queue name |
| `dataDir` | String | S3 sub-path prefix for payload |
| `metadataDir` | String | S3 sub-path prefix for metadata |
| `route` | String | Applicable HTTP route (e.g., `/ingest`, `/xds/XDSbRepositoryWS`) |
| `sourceId` | String | Expected sender identifier |
| `msgType` | String | Expected message type (e.g., `ORU`, `ADT`) |
| `mtls` | String | mTLS certificate profile name (e.g., `txd`) |
| `mtlsEnabled` | boolean | Whether mTLS is required |
| `whitelistIps` | List\<String\> | Allowed CIDR blocks |
| `keepAliveTimeout` | int | TCP keep-alive duration (seconds) |
| `ipAddress` | String | Optional IP binding |
| `cidrBlock` | String | CIDR for allowed networks |
| `ackContentType` | String | Content-type for ACK response |

### Resolution Strategy

1. **Source + message type match**: if `sourceId` and `msgType` match both the
   URL path `/{sourceId}/{msgType}` and the PortEntry, this takes priority.
2. **Port + protocol match**: first PortEntry with matching `port` and
   `protocol`.
3. **No match**: `IllegalArgumentException` → request rejected.

---

## mTLS Validation

The service supports inbound mTLS for MLLP and SOAP connections.

The AWS ALB is configured for **mTLS pass-through** (TLS is not terminated at
the ALB).  The client certificate is injected into the request via the
`X-Amzn-Mtls-Clientcert` header (URL-encoded PEM).

Validation flow:
1. Parse client certificate chain from the header.
2. Look up the CA bundle name from `PortEntry.mtls` (e.g., `txd` →
   `txd-bundle.pem`).
3. Load the CA bundle from the configured S3 bucket.
4. Perform PKIX certificate chain validation.
5. If validation fails → return `401 Unauthorized`.

---

## AWS Integration

### S3 Object Layout

```
s3://<PAYLOAD_BUCKET>/
├── <dataDir>/YYYY/MM/DD/<interactionId>.json
└── <metadataDir>/YYYY/MM/DD/<interactionId>-metadata.json
```

### SQS Message

| Attribute | Value |
|-----------|-------|
| `MessageGroupId` | Derived from destination port + source metadata |
| `MessageDeduplicationId` | `interactionId` (UUID) |
| Body | JSON with `interactionId`, payload location, metadata |

---

## Local Development with LocalStack

Use LocalStack to emulate S3 and SQS:

```bash
# Start LocalStack
docker-compose up -d

# Create bucket
aws --endpoint-url=http://localhost:4566 s3 mb s3://local-ingestion-bucket

# Create SQS FIFO queue
aws --endpoint-url=http://localhost:4566 sqs create-queue \
    --queue-name local-queue.fifo \
    --attributes FifoQueue=true,ContentBasedDeduplication=true
```

See `support/nexus-ingestion-api/README.md` for detailed LocalStack setup.

---

## Running the Service

```bash
cd nexus-ingestion-api
export SPRING_PROFILES_ACTIVE=devl
mvn spring-boot:run
# HTTP listener: http://localhost:8080
# TCP/MLLP listener: port 7980
```

---

## Building

```bash
cd nexus-ingestion-api
mvn clean package -DskipTests
java -jar target/nexus-ingestion-api-*.jar
```

---

## Integration Tests

Integration tests are in:
```
src/test/java/org/techbd/ingest/integrationtests/
```

They require a running LocalStack instance.  See the
[README](../../nexus-ingestion-api/src/test/java/org/techbd/ingest/integrationtests/README.md)
for setup instructions.
