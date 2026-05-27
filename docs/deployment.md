# Deployment Guide

## Environments

| Profile name | Description | Database variable prefix |
|-------------|-------------|------------------------|
| `devl` | Local / developer | `devl_` |
| `sandbox` | Integration sandbox | `sandbox_` |
| `phiqa` | PHI Quality Assurance | `phiqa_` |
| `stage` | Pre-production staging | `stage_` |
| `phiprod` | PHI Production | `phiprod_` |

Set the active profile via:
```bash
export SPRING_PROFILES_ACTIVE=phiqa
```

---

## Environment Variables

All sensitive configuration is injected via environment variables; **never
commit secrets to source code**.

### Database (all services)

```
${PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_URL      JDBC URL (PostgreSQL)
${PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_USERNAME  DB username
${PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_PASSWORD  DB password
```

Example for the `phiqa` profile:
```
phiqa_TECHBD_UDI_DS_PRIME_JDBC_URL=jdbc:postgresql://db-host:5432/techbd
phiqa_TECHBD_UDI_DS_PRIME_JDBC_USERNAME=techbd_app
phiqa_TECHBD_UDI_DS_PRIME_JDBC_PASSWORD=<secret>
```

### FHIR / hub-prime

```
TECHBD_BASE_FHIR_URL            Base URL used in generated FHIR resources
TECHBD_DATA_LEDGER_API_URL      Data Ledger API endpoint
TECHBD_DATA_LEDGER_TRACKING_ENABLED   true/false
TECHBD_DATA_LEDGER_DIAGNOSTICS_ENABLED  true/false
```

### CSV service

```
TECHBD_PYTHON_SCRIPT_PATH   Path prefix for Python validation scripts
TECHBD_BASE_FHIR_URL        Same as hub-prime
```

### nexus-ingestion-api

```
PORT_CONFIG_S3_BUCKET    S3 bucket name for port configuration JSON
AWS_REGION               AWS region (e.g., us-east-1)
```

---

## Building Production Artifacts

### Fat JARs

```bash
# Build all modules
mvn clean package -DskipTests

# Artifacts are placed in each module's target/ directory
ls hub-prime/target/*.jar
ls fhir-validation-service/target/*.jar
ls csv-service/target/*.jar
ls nexus-ingestion-api/target/*.jar
```

### Running a Fat JAR

```bash
java -jar hub-prime/target/hub-prime-*.jar \
     --spring.profiles.active=phiqa
```

---

## Containerisation (Docker)

The `support/archive/containers/sandbox/hub-prime/` directory contains a
reference Dockerfile and `docker-compose.yml` for the sandbox environment.

### Building the Image

```bash
cd support/archive/containers/sandbox/hub-prime
docker-compose build --no-cache
```

### Starting the Stack

```bash
docker-compose up -d
```

The hub application starts on port `8080` by default.

---

## Database Migrations (udi-prime)

Database schema is managed by the TypeScript/Deno migration framework in
`udi-prime/`.

### Prerequisites

- Deno installed
- PostgreSQL accessible
- `.pgpass` configured (or passwords available via environment)

### Running Migrations

```bash
cd udi-prime

# Full refresh: generate SQL + run migrations + generate jOOQ JAR
./udictl.ts ic omnibus-fresh

# With JAR deployment to dependent modules
./udictl.ts ic omnibus-fresh --deploy-jar

# Individual steps
./udictl.ts ic generate sql          # generate SQL DDL files
./udictl.ts ic migrate               # apply migrations to DB
./udictl.ts ic generate java jooq    # regenerate jOOQ Java sources
./udictl.ts ic generate docs         # generate SchemaSpy docs
```

After regenerating the jOOQ JAR, run `mvn clean` in dependent modules
(`hub-prime`, `csv-service`, `fhir-validation-service`) to pick up the new JAR.

---

## AWS Infrastructure

### Required AWS Services

| Service | Usage |
|---------|-------|
| **RDS Aurora PostgreSQL** | Primary database |
| **S3** | Payload storage (nexus-ingestion-api) and port configuration |
| **SQS FIFO** | Message queue for async ingestion |
| **Secrets Manager** | mTLS certificates and API keys |
| **CloudWatch** | Metrics and logs |
| **ALB (Application Load Balancer)** | TLS termination / mTLS pass-through |

### S3 Bucket Layout (nexus-ingestion-api)

Payloads are stored with date-partitioned keys:
```
s3://<bucket>/<dataDir>/YYYY/MM/DD/<interactionId>.json
s3://<bucket>/<metadataDir>/YYYY/MM/DD/<interactionId>-metadata.json
```

### SQS Message Attributes

Each SQS message includes:
- `interactionId` — UUID for end-to-end correlation
- `sourceId`, `msgType` — from the inbound request path
- `tenantId` — resolved from request headers or port config

### Secrets Manager Keys

| Secret | Used By | Description |
|--------|---------|-------------|
| `techbd-nyec-dataledger-api-key` | csv-service, hub-prime | Data Ledger API key |
| `<mtls-cert-secret-name>` | hub-prime | mTLS client certificate PEM |
| `<mtls-key-secret-name>` | hub-prime | mTLS client private key PEM |
| `<mtls-bundle>-bundle.pem` | nexus-ingestion-api | CA bundle for mTLS validation |

---

## LocalStack for Local Development

For local development without a real AWS account, use
[LocalStack](https://localstack.cloud/) to emulate S3 and SQS.

```yaml
# docker-compose.yml (from support/nexus-ingestion-api/)
version: "3.8"
services:
  localstack:
    container_name: localstack
    image: localstack/localstack:latest
    ports:
      - "4566:4566"
    environment:
      - SERVICES=s3,sqs
      - DEBUG=1
```

```bash
docker-compose up -d

# Create required S3 bucket
aws --endpoint-url=http://localhost:4566 s3 mb s3://my-local-bucket

# Create SQS FIFO queue
aws --endpoint-url=http://localhost:4566 sqs create-queue \
    --queue-name my-queue.fifo \
    --attributes FifoQueue=true
```

---

## Health Checks

All Spring Boot services expose actuator endpoints:

```
GET /actuator/health        → {"status":"UP",...}
GET /actuator/info          → build and git metadata
GET /actuator/metrics       → Micrometer metrics
GET /actuator/prometheus    → Prometheus-format metrics
```

Only `health` is accessible without authentication by default.  Others require
an authorised session (configurable via `management.endpoint.*.enabled`).

---

## CI/CD

CI/CD pipelines are defined under `.github/` and run on pull-request and merge
events.  Key pipeline stages:

1. **Build** — `mvn clean install` across all modules
2. **Test** — Unit tests + pgTAP database tests
3. **Package** — Build fat JARs
4. **Deploy** — Push container images to ECR and trigger deployment to the
   target environment
