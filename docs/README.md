# Polyglot Prime — Documentation Index

Welcome to the comprehensive documentation for the **Tech by Design Polyglot Prime** monorepo. This index gives you a quick overview of all available documentation and guides you to the right place for any topic.

---

## Table of Contents

| Document | Description |
|----------|-------------|
| [Architecture Overview](architecture.md) | High-level system design, data flows, and inter-service relationships |
| [Development Guide](development.md) | Local environment setup, build instructions, workflow conventions |
| [Testing Guide](testing.md) | Unit, integration, database, and API testing strategies |
| [Deployment Guide](deployment.md) | Environment profiles, containerization, CI/CD, AWS configuration |
| [API Reference](api-reference.md) | Complete list of HTTP endpoints across all services |
| **Module Docs** | |
| [hub-prime](modules/hub-prime.md) | Primary Spring Boot FHIR API hub and Thymeleaf UI application |
| [udi-prime](modules/udi-prime.md) | Universal Data Infrastructure — PostgreSQL schema & jOOQ code generation |
| [fhir-validation-service](modules/fhir-validation-service.md) | Standalone FHIR compliance validation microservice |
| [csv-service](modules/csv-service.md) | CSV / flat-file ingestion and FHIR conversion service |
| [nexus-ingestion-api](modules/nexus-ingestion-api.md) | Multi-protocol (HTTP/MLLP/SOAP) data ingestion gateway |
| [Shared Libraries](modules/shared-libraries.md) | hub-core-lib and nexus-core-lib shared Java libraries |

---

## Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/tech-by-design/polyglot-prime.git
cd polyglot-prime

# 2. Set up environment variables
cp .envrc.example .envrc
direnv allow          # requires direnv; edit .envrc with your secrets

# 3. Build all modules
mvn clean install

# 4. Start the primary hub application
cd hub-prime
mvn spring-boot:run
# → http://localhost:8080
```

See [Development Guide](development.md) for full setup instructions.

---

## Project at a Glance

**Polyglot Prime** is a healthcare data exchange platform built by **Technology By Design (Tech by Design)** that:

- Ingests FHIR R4, HL7v2, CCDA, and CSV flat-file data from partner organisations
- Validates payloads against the **SHIN-NY FHIR Implementation Guide**
- Stores all interactions in a PostgreSQL data vault
- Forwards validated bundles to the SHIN-NY Data Lake (scoring engine)
- Provides a browser-based operational dashboard via Thymeleaf + HTMX

The repository is a **Maven multi-module monorepo** that hosts multiple Spring Boot services, a TypeScript/Deno database layer, integration channel configurations, and automation test suites.
