# nexus-core-lib Module Deep Analysis

Document version: 1.0  
Date: 2026-03-17  
Scope: Full source and test analysis of the nexus-core-lib Maven module

## 1. Executive Summary

`nexus-core-lib` is the **shared domain library** for the polyglot-prime platform. It provides the core business logic consumed by downstream modules (`hub-prime`, `nexus-ingestion-api`, etc.) without being a runnable application itself. Its responsibilities span:

- **FHIR validation** — HL7 FHIR R4 bundle validation against SHIN-NY Implementation Guides using HAPI FHIR with configurable engines (HAPI, HL7 Embedded, HL7 API) and OpenTelemetry instrumentation.
- **CSV-to-FHIR conversion** — Pipeline converting flat CSV files (demographics, screening, QE admin data) into compliant FHIR R4 Bundles using an ordered converter chain.
- **Database persistence** — JOOQ-based interaction recording to PostgreSQL for FHIR bundles, CSV payloads, CCDA documents, and HL7v2 messages with full audit trails.
- **Scoring engine forwarding** — Sending validated FHIR bundles to external scoring engines (NYEC data lake) with mTLS, API key, and stdin-based authentication strategies.
- **Data ledger integration** — Async tracking of interaction events to an external Data Ledger API for accountability and monitoring.
- **Configuration management** — Typed configuration properties, IG package management, profile URL resolution, and multi-environment YAML support.

This module has a **PostgreSQL database dependency** via JOOQ and uses auto-generated JOOQ classes from a system-scoped JAR (`techbd-udi-jooq-ingress.auto.jar`).

## 2. Module Inventory and Size

### 2.1 Source footprint

- Main Java files: **66**
- Test Java files: **18**
- YAML config files: **6** (application.yml + 5 profile-specific)
- JSON resource files: **2** (IG value sets)
- Test resource files: **33** (FHIR example bundles)

### 2.2 Package composition (major)

| Package | Files | Role |
|---------|-------|------|
| `org.techbd.config` | 16 | Configuration, constants, enums, database config, interaction models |
| `org.techbd.converters.csv` | 10 | CSV-to-FHIR resource converters (Patient, Encounter, Consent, Organization, Procedure, Observations) |
| `org.techbd.service.fhir` | 3 | FHIR validation orchestration, bundle processing, replay |
| `org.techbd.service.fhir.engine` | 1 | Orchestration engine with validation engine factory/cache |
| `org.techbd.service.fhir.validation` | 3 | FHIR pre/post-populate support, validator config holder |
| `org.techbd.service.csv` | 4 | CSV file processing, validation, bundle generation |
| `org.techbd.service.csv.engine` | 2 | CSV orchestration engine, file processor/validator |
| `org.techbd.service.ccda` | 1 | CCDA document persistence |
| `org.techbd.service.hl7` | 1 | HL7v2 message persistence |
| `org.techbd.service.dataledger` | 1 | External Data Ledger API client |
| `org.techbd.service.vfs` | 2 | Virtual filesystem abstraction for file ingestion |
| `org.techbd.model.csv` | 9 | CSV data models (DemographicData, QeAdminData, ScreeningData, etc.) |
| `org.techbd.exceptions` | 2 | Error codes, JSON validation exception |
| `org.techbd.util` | 6 | AWS, logging, date, JSON, system diagnostics utilities |
| `org.techbd.util.csv` | 2 | CSV constants, conversion utilities |
| `org.techbd.util.fhir` | 3 | FHIR utilities (profile URLs, concept readers, file IO) |
| `org.techbd` | 1 | SpringContextHolder (static context bootstrap) |

### 2.3 Build metadata

- Packaging: `jar` (library)
- Parent: `polyglot-prime`
- Java target: 21
- Name: "Java Core Library for Hub Prime"
- **Note:** maven-jar-plugin configured with `mainClass=com.example.app.Main` (clearly incorrect/placeholder)
- **Note:** `testFailureIgnore=true` in surefire plugin — tests may be failing silently

## 3. Dependency and Build Analysis

### 3.1 Key direct dependencies

| Category | Dependencies | Versions |
|----------|-------------|----------|
| **FHIR** | hapi-fhir-base, hapi-fhir-client, hapi-fhir-structures-r4, hapi-fhir-validation, hapi-fhir-validation-resources-r4, hapi-fhir-caching-caffeine | 8.2.2 |
| **Database** | postgresql, spring-boot-starter-jooq, jooq-jackson-extensions, HikariCP, jakarta.persistence-api | pg 42.7.3, jooq 3.3.1/3.19.10, HikariCP 5.1.0, JPA 3.2.0-M2 |
| **AWS** | aws-secretsmanager, aws-sqs | 2.17.87 |
| **Spring** | spring-boot-starter-web, spring-boot-starter-security, spring-boot-starter-webflux, spring-boot-starter-validation | managed by parent |
| **Observability** | micrometer-registry-otlp, micrometer-tracing, micrometer-tracing-bridge-otel, opentelemetry-exporter-otlp, opentelemetry-sdk-extension-autoconfigure | OTel 1.46.0 |
| **JSON** | jackson-databind, jackson-dataformat-yaml, org.json | Jackson 2.17.1, org.json 20250107 |
| **CSV** | opencsv | 5.8 |
| **GraalVM** | graal-sdk, js (POM type) | SDK 22.3.0, JS 24.1.2 |
| **Crypto** | bcprov-jdk18on | 1.80 |
| **Auth** | oauth2-oidc-sdk | 10.8 |
| **XML** | jaxb-api (javax 2.3.1), jaxb-core/jaxb-impl (4.0.5) |
| **Misc** | commons-text 1.12.0, snakeyaml 2.0, joda-time 2.12.5, okio 3.9.1, commons-vfs2-spring-boot-starter 1.0.1, reactor-netty, lombok |
| **Test** | junit-jupiter 5.10.0, assertj-core 3.24.2, mockito-junit-jupiter 5.10.0 |

### 3.1.1 System-scoped dependency

```xml
<dependency>
    <groupId>org.techbd.udi.auto</groupId>
    <artifactId>udi-jooq-ingress</artifactId>
    <scope>system</scope>
    <systemPath>${basedir}/lib/techbd-udi-jooq-ingress.auto.jar</systemPath>
</dependency>
```

This pre-built JAR contains auto-generated JOOQ classes for the PostgreSQL database schema and is the primary database access layer.

### 3.2 Notable dependency/build characteristics

1. **System-scoped JOOQ JAR.** `techbd-udi-jooq-ingress.auto.jar` is committed as a binary artifact. This breaks reproducible builds and makes schema changes opaque.

2. **AWS SDK version mismatch.** AWS SecretsManager and SQS are at v2.17.87, while `nexus-ingestion-api` uses v2.28.0. This inconsistency means the same platform uses two different AWS SDK versions.

3. **GraalVM version mismatch.** `graal-sdk` at 22.3.0 vs. `js` at 24.1.2. These are from different major releases.

4. **Mixed JAXB versions.** `jaxb-api` (javax, v2.3.1) alongside `jaxb-core`/`jaxb-impl` (jakarta, v4.0.5). The javax and jakarta namespaces should not be mixed.

5. **testFailureIgnore=true.** Test failures are silently ignored in CI builds, undermining test quality signals.

6. **Placeholder mainClass.** `maven-jar-plugin` has `mainClass=com.example.app.Main` which doesn't exist.

7. **JPA milestone dependency.** `jakarta.persistence-api` at 3.2.0-M2 is a pre-release milestone.

8. **Dual JSON libraries.** Both Jackson (`jackson-databind`) and `org.json` are present.

9. **Spring Boot starter-jooq pinned to 3.3.1** outside of parent BOM management.

## 3.3 Class Diagram

```mermaid
classDiagram
    direction TB

    %% ──────────────────────────────────────────────
    %% CONFIGURATION LAYER
    %% ──────────────────────────────────────────────

    class SpringContextHolder {
        -ApplicationContext ctx$
        +init()$
        +getBean(Class~T~) T$
        +getTracer() Tracer
    }

    class AppInitializationConfig {
        <<@Configuration>>
    }

    class CoreAppConfig {
        <<@ConfigurationProperties>>
        +version : String
        +baseFHIRURL : String
        +defaultDatalakeApiUrl : String
        +operationOutcomeHelpUrl : String
        +structureDefinitionsUrls : Map
        +igPackages : IgPackages
        +defaultDataLakeApiAuthn : MtlsConfig
        +csv : CsvConfig
        +dataLedgerApiUrl : String
        +dataLedgerTracking : boolean
        +dataLedgerDiagnostics : boolean
        +processingAgent : ProcessingAgent
        +validationSeverityLevel : String
    }

    class Configuration {
        +objectMapper : ObjectMapper$
        +objectMapperConcise : ObjectMapper$
        +getEnvVarValue(name) String$
    }

    class CoreUdiPrimeJpaConfig {
        <<@Configuration>>
        +udiPrimaryDataSource() DataSource
        +primaryDslContext() DSLContext
        +primaryJooqConfiguration() org.jooq.Configuration
    }

    class CoreUdiReaderConfig {
        <<@Configuration>>
        +secondaryDataSource() DataSource
        +secondaryDslContext() DSLContext
    }

    class CoreAsyncConfig {
        <<@Configuration>>
        +taskExecutor() Executor
    }

    class Interactions {
        +history : List~RequestResponseEncountered~
        -MAX_HISTORY : int = 50
        +addHistory(rre)
        +setActiveRequestEnc(params, re)$
        +setTenantId(params, tenantId)$
    }

    class Interactions_RequestEncountered {
        <<record>>
        +tenantId : String
        +requestId : String
        +method : String
        +requestUrl : String
        +clientIpAddress : String
        +headers : Map
        +body : String
    }

    class Interactions_ResponseEncountered {
        <<record>>
        +status : int
        +headers : Map
        +body : String
    }

    class Interactions_RequestResponseEncountered {
        <<record>>
        +interactionId : UUID
        +request : RequestEncountered
        +response : ResponseEncountered
    }

    Interactions *-- Interactions_RequestEncountered
    Interactions *-- Interactions_ResponseEncountered
    Interactions *-- Interactions_RequestResponseEncountered

    class Constant {
        +STATELESS_API_URLS : String[]$
        +UNAUTHENTICATED_URLS : String[]$
    }

    class Constants {
        <<interface>>
        +HEADER_TENANT_ID$
        +HEADER_INTERACTION_ID$
        +HEADER_PROVENANCE$
        ... 90+ constants
    }

    class Helpers {
        +getBaseUrl(request) String$
        +findHeader(request, name) Optional~String~$
    }

    %% ──────────────────────────────────────────────
    %% ENUMS
    %% ──────────────────────────────────────────────

    class Nature {
        <<enumeration>>
        ORIGINAL_REQUEST_RECEIVED
        S3_UPLOAD
        SQS_PUSH
        FORWARD_HTTP_REQUEST
        CSV_VALIDATION_RESULT
        FHIR_ORIGINAL_PAYLOAD
        CCDA_ORIGINAL_PAYLOAD
        HL7_ORIGINAL_PAYLOAD
    }

    class State {
        <<enumeration>>
        ACCEPT_FHIR_BUNDLE
        VALIDATION_SUCCESS
        VALIDATION_FAILED
        INGESTION_SUCCESS
        INGESTION_FAILED
        CONVERTED_TO_FHIR
        CSV_ACCEPT
        CCDA_ACCEPT
        HL7_ACCEPT
    }

    class Origin {
        <<enumeration>>
        HTTP
        SFTP
    }

    class SourceType {
        <<enumeration>>
        FHIR
        CSV
        HL7V2
        CCDA
    }

    class CsvProcessingState {
        <<enumeration>>
        RECEIVED
        PROCESSING_INPROGRESS
        PROCESSING_COMPLETED
        PROCESSING_FAILED
    }

    class CsvDataValidationStatus {
        <<enumeration>>
        SUCCESS
        PARTIAL_SUCCESS
        FAILED
    }

    %% ──────────────────────────────────────────────
    %% CSV DATA MODELS
    %% ──────────────────────────────────────────────

    class DemographicData {
        +patientMrIdValue : String
        +patientMaIdValue : String
        +patientSsnValue : String
        +givenName : String
        +familyName : String
        +gender : String
        +birthDate : String
        +race : String
        +ethnicity : String
        +preferredLanguage : String
        ... 40+ fields
    }

    class QeAdminData {
        +facilityId : String
        +facilityName : String
        +orgTypeCode : String
        +orgTypeDisplay : String
        +addressLine1 : String
        +city : String
        +state : String
        +zip : String
        ... 13 fields
    }

    class ScreeningProfileData {
        +encounterId : String
        +encounterClassCode : String
        +encounterTypeCode : String
        +encounterStartDate : String
        +consentStatus : String
        +consentPolicyAuthority : String
        +procedureCode : String
        ... 30 fields
    }

    class ScreeningObservationData {
        +questionCode : String
        +questionCodeDisplay : String
        +questionCodeSystemName : String
        +answerCode : String
        +answerCodeDisplay : String
        +sdohDomain : String
        ... 18 fields
    }

    class FileDetail {
        <<record>>
        +filename : String
        +fileType : FileType
        +content : String
        +isEncoded : boolean
    }

    class FileType {
        <<enumeration>>
        SDOH_PtInfo
        SDOH_QEadmin
        SDOH_ScreeningProf
        SDOH_ScreeningObs
        +fromFilename(name) FileType$
    }

    class CsvProcessingMetrics {
        +totalFiles : int
        +bundleCount : int
        +validationStatus : CsvDataValidationStatus
    }

    class PayloadAndValidationOutcome {
        <<record>>
        +groupKey : String
        +fileDetails : List~FileDetail~
        +validationResults : Map
    }

    FileDetail --> FileType
    CsvProcessingMetrics --> CsvDataValidationStatus
    PayloadAndValidationOutcome --> FileDetail

    %% ──────────────────────────────────────────────
    %% CSV-TO-FHIR CONVERTER PIPELINE
    %% ──────────────────────────────────────────────

    class IConverter {
        <<interface>>
        +getResourceType() ResourceType
        +getProfileUrl() String
        +convert(bundle, demographic, qeAdmin, screeningProfile, screeningObs, interactionId, idsGenerated, baseFhirUrl) List~BundleEntryComponent~
        +setMeta(resource) void
    }

    class BaseConverter {
        <<abstract>>
        #CODE_LOOKUP : Map~String,String~$
        #SYSTEM_LOOKUP : Map~String,String~$
        #DISPLAY_LOOKUP : Map~String,String~$
        #getCode(key) String
        #getSystem(key) String
        #getDisplay(key) String
        #createExtension(url, value) Extension
        #createReference(type, id) Reference
        #classifyRaceCategory(code) String
    }

    class OrganizationConverter {
        <<@Order(1)>>
        +convert(...) List~BundleEntryComponent~
    }

    class PatientConverter {
        <<@Order(2)>>
        +convert(...) List~BundleEntryComponent~
    }

    class SexualOrientationObservationConverter {
        <<@Order(3)>>
        +convert(...) List~BundleEntryComponent~
    }

    class ConsentConverter {
        <<@Order(4)>>
        +convert(...) List~BundleEntryComponent~
    }

    class EncounterConverter {
        <<@Order(5)>>
        +convert(...) List~BundleEntryComponent~
    }

    class ScreeningResponseObservationConverter {
        <<@Order(6)>>
        +convert(...) List~BundleEntryComponent~
    }

    class ProcedureConverter {
        <<@Order(7)>>
        +convert(...) List~BundleEntryComponent~
    }

    class BundleConverter {
        +createBundle(meta, igVersion) Bundle
    }

    class CsvToFhirConverter {
        -converters : List~IConverter~
        +convert(bundle, demographic, qeAdmin, screeningProfile, screeningObs, interactionId, idsGenerated, baseFhirUrl) String
    }

    IConverter <|.. BaseConverter
    BaseConverter <|-- OrganizationConverter
    BaseConverter <|-- PatientConverter
    BaseConverter <|-- SexualOrientationObservationConverter
    BaseConverter <|-- ConsentConverter
    BaseConverter <|-- EncounterConverter
    BaseConverter <|-- ScreeningResponseObservationConverter
    BaseConverter <|-- ProcedureConverter
    CsvToFhirConverter o-- "1..*" IConverter : ordered chain
    CsvToFhirConverter --> BundleConverter : creates bundle via

    OrganizationConverter ..> QeAdminData : reads
    PatientConverter ..> DemographicData : reads
    ConsentConverter ..> ScreeningProfileData : reads
    EncounterConverter ..> ScreeningProfileData : reads
    ScreeningResponseObservationConverter ..> ScreeningObservationData : reads
    ProcedureConverter ..> ScreeningProfileData : reads

    %% ──────────────────────────────────────────────
    %% FHIR VALIDATION ENGINE
    %% ──────────────────────────────────────────────

    class OrchestrationEngine {
        -sessions : ConcurrentHashMap
        -validationEngineCache : Map
        +orchestrate(request) OrchestrationSession
    }

    class OrchestrationEngine_HapiValidationEngine {
        -fhirContext : FhirContext
        -fhirValidator : FhirValidator
        -igVersion : String
        +validate(payload) ValidationResult
    }

    class OrchestrationEngine_Hl7ValidationEngineEmbedded {
        +validate(payload) ValidationResult
    }

    class OrchestrationEngine_Hl7ValidationEngineApi {
        -apiUrl : String
        +validate(payload) ValidationResult
    }

    class OrchestrationEngine_OrchestrationSession {
        -device : Device
        -validationResults : List
    }

    class OrchestrationEngine_Device {
        <<interface>>
        +getDeviceId() String
        +getDeviceName() String
    }

    OrchestrationEngine *-- OrchestrationEngine_HapiValidationEngine
    OrchestrationEngine *-- OrchestrationEngine_Hl7ValidationEngineEmbedded
    OrchestrationEngine *-- OrchestrationEngine_Hl7ValidationEngineApi
    OrchestrationEngine *-- OrchestrationEngine_OrchestrationSession
    OrchestrationEngine_HapiValidationEngine ..|> OrchestrationEngine_Device
    OrchestrationEngine_Hl7ValidationEngineEmbedded ..|> OrchestrationEngine_Device
    OrchestrationEngine_Hl7ValidationEngineApi ..|> OrchestrationEngine_Device

    class FhirBundleValidator {
        +fhirContext : FhirContext
        +fhirValidator : FhirValidator
        +igVersion : String
        +igPackagePath : String
        +profileUrl : String
    }

    class PrePopulateSupport {
        +build(ctx, npmPackage, version) void
        -loadCodeSystem(ctx, name, file) void
    }

    class PostPopulateSupport {
        +update(ctx) void
        -addLoincCodes(ctx) void
        -addLanguageSubtags(ctx) void
    }

    OrchestrationEngine_HapiValidationEngine --> FhirBundleValidator : configured by
    OrchestrationEngine_HapiValidationEngine --> PrePopulateSupport : uses
    OrchestrationEngine_HapiValidationEngine --> PostPopulateSupport : uses

    %% ──────────────────────────────────────────────
    %% FHIR SERVICE LAYER
    %% ──────────────────────────────────────────────

    class FHIRService {
        <<@Service>>
        -orchestrationEngine : OrchestrationEngine
        -coreAppConfig : CoreAppConfig
        -primaryDslContext : DSLContext
        -dataLedgerClient : CoreDataLedgerApiClient
        +processBundle(payload, tenantId, request, response, ...) Object
        +validateJson(payload) JsonNode
        +validateBundleProfileUrl(bundle, igPackages) String
        +sendToScoringEngine(payload, dataLakeUrl, tenantId, ...) Map
        -isActionDiscard(session) boolean
        -persistInteraction(interactionId, ...) void
    }

    class FhirReplayService {
        <<@Service>>
        -fhirService : FHIRService
        -primaryDslContext : DSLContext
        +replayFailedSubmissions() CompletableFuture
    }

    FHIRService --> OrchestrationEngine : delegates validation
    FHIRService --> CoreAppConfig : reads config
    FHIRService --> CoreDataLedgerApiClient : sends events
    FhirReplayService --> FHIRService : re-submits via

    %% ──────────────────────────────────────────────
    %% CSV SERVICE LAYER
    %% ──────────────────────────────────────────────

    class CsvService {
        <<@Service>>
        -csvOrchestrationEngine : CsvOrchestrationEngine
        -csvBundleProcessorService : CsvBundleProcessorService
        -coreAppConfig : CoreAppConfig
        +validateCsvFile(file, tenantId, request, response, ...) Object
    }

    class CsvBundleProcessorService {
        <<@Service>>
        -csvToFhirConverter : CsvToFhirConverter
        -fhirService : FHIRService
        -dataLedgerClient : CoreDataLedgerApiClient
        +processPayload(groupedData, interactionId, ...) void
    }

    class CsvOrchestrationEngine {
        -sessions : ConcurrentHashMap
        -fileProcessor : FileProcessor
        +orchestrate(file, tenantId, ...) OrchestrationSession
        -runPythonValidation(files) ValidationResult
    }

    class FileProcessor {
        +processAndGroupFiles(zipEntries) Map~String, List~FileDetail~~
        +validateUtf8Encoding(content, filename) List~String~
        -detectFileType(filename) FileType
        -groupByPatientMrId(files) Map
    }

    class CodeLookupService {
        <<@Service>>
        +fetchCodeLookup(dslContext) Map
        +fetchSystemLookup(dslContext) Map
        +fetchDisplayLookup(dslContext) Map
    }

    class SimpleMultipartFile {
        +name : String
        +originalFilename : String
        +contentType : String
        +content : byte[]
        +getInputStream() InputStream
    }

    CsvService --> CsvOrchestrationEngine : validates via
    CsvService --> CsvBundleProcessorService : converts via
    CsvBundleProcessorService --> CsvToFhirConverter : converts CSV→FHIR
    CsvBundleProcessorService --> FHIRService : validates & forwards
    CsvBundleProcessorService --> CoreDataLedgerApiClient : tracks events
    CsvOrchestrationEngine --> FileProcessor : processes files
    BaseConverter ..> CodeLookupService : lookups from

    %% ──────────────────────────────────────────────
    %% PROTOCOL PERSISTENCE SERVICES
    %% ──────────────────────────────────────────────

    class CCDAService {
        <<@Service>>
        -primaryDslContext : DSLContext
        +saveOriginalCcdaPayload(interactionId, ...) void
        +saveValidation(interactionId, ...) void
        +saveFhirConversionResult(interactionId, ...) void
        +saveCcdaValidation(interactionId, ...) void
    }

    class HL7Service {
        <<@Service>>
        -primaryDslContext : DSLContext
        +saveOriginalHl7Payload(interactionId, ...) void
        +saveValidation(interactionId, ...) void
        +saveFhirConversionResult(interactionId, ...) void
    }

    CCDAService --> State : uses states
    HL7Service --> State : uses states

    %% ──────────────────────────────────────────────
    %% EXTERNAL INTEGRATION
    %% ──────────────────────────────────────────────

    class CoreDataLedgerApiClient {
        <<@Service>>
        -httpClient : HttpClient
        -coreAppConfig : CoreAppConfig
        -primaryDslContext : DSLContext
        +processRequest(interactionId, action, payload, ...) CompletableFuture
        -getApiKey() String
        -persistDiagnostics(interactionId, ...) void
    }

    CoreDataLedgerApiClient --> AWSUtil : gets API key via
    CoreDataLedgerApiClient --> CoreAppConfig : reads config

    %% ──────────────────────────────────────────────
    %% VFS / FILE INGESTION
    %% ──────────────────────────────────────────────

    class VfsCoreService {
        +resolveFileObject(uri) FileObject
        +listFiles(dir) List~FileObject~
    }

    class VfsIngressConsumer {
        -vfsCoreService : VfsCoreService
        -csvService : CsvService
        +processIngressPath(path) void
        +extractZip(fileObject) Map~String, byte[]~
    }

    VfsIngressConsumer --> VfsCoreService : resolves files
    VfsIngressConsumer --> CsvService : processes CSVs

    %% ──────────────────────────────────────────────
    %% UTILITIES
    %% ──────────────────────────────────────────────

    class AWSUtil {
        +getSecretValue(secretName, region) String$
    }

    class AppLogger {
        +create(clazz) TemplateLogger$
    }

    class TemplateLogger {
        -delegate : Logger
        -version : String
        +info(msg, args) void
        +warn(msg, args) void
        +error(msg, args) void
        +debug(msg, args) void
    }

    AppLogger --> TemplateLogger : creates

    class DateUtil {
        +parseDate(dateStr) Date$
        +toInstant(dateStr) Instant$
    }

    class JsonText {
        +parse(json) JsonTextResult$
    }

    class JsonText_ValidResult {
        <<sealed>>
        +node : JsonNode
    }

    class JsonText_InvalidResult {
        <<sealed>>
        +error : String
    }

    JsonText --> JsonText_ValidResult
    JsonText --> JsonText_InvalidResult

    class SystemDiagnosticsLogger {
        +logResourceUsage() void$
    }

    class CsvConstants {
        +RACE_CODE_TYPE$
        +ETHNICITY_CODE_TYPE$
        +GENDER_CODE_TYPE$
        ... 38 constants
    }

    class CsvConversionUtil {
        +convertCsvStringToDemographic(csv) List~DemographicData~$
        +convertCsvStringToQeAdmin(csv) List~QeAdminData~$
        +convertCsvStringToScreeningProfile(csv) List~ScreeningProfileData~$
        +convertCsvStringToScreeningObs(csv) List~ScreeningObservationData~$
        +sha256(input) String$
    }

    class CoreFHIRUtil {
        +getProfileUrl(resourceType, config) String$
        +getBaseFhirUrl() String$
        +setBaseFhirUrl(url) void$
        +validateIgVersion(igVersion, igPackages) boolean$
    }

    class ConceptReaderUtils {
        +readConceptsFromPsv(path) List~ConceptDef~$
    }

    class FileUtils {
        +readFileFromClasspath(path) String$
    }

    CsvConversionUtil ..> DemographicData : creates
    CsvConversionUtil ..> QeAdminData : creates
    CsvConversionUtil ..> ScreeningProfileData : creates
    CsvConversionUtil ..> ScreeningObservationData : creates

    %% ──────────────────────────────────────────────
    %% EXCEPTIONS
    %% ──────────────────────────────────────────────

    class ErrorCode {
        <<enumeration>>
        INVALID_JSON
        INVALID_BUNDLE
        VALIDATION_FAILED
        INTERNAL_ERROR
    }

    class JsonValidationException {
        -errorCode : ErrorCode
        -details : String
        +getErrorCode() ErrorCode
    }

    JsonValidationException --> ErrorCode

    %% ──────────────────────────────────────────────
    %% KEY CROSS-CUTTING RELATIONSHIPS
    %% ──────────────────────────────────────────────

    SpringContextHolder ..> AppInitializationConfig : registers
    SpringContextHolder ..> CoreAppConfig : bootstraps
    CoreUdiPrimeJpaConfig ..> CoreUdiReaderConfig : primary ↔ secondary
    FHIRService ..> Interactions : tracks requests
    FHIRService ..> Nature : tags interactions
    FHIRService ..> State : transitions states
    CsvService ..> CsvProcessingState : tracks CSV state
    CsvBundleProcessorService ..> CsvConversionUtil : parses CSVs
```

**Diagram legend:**

| Symbol | Meaning |
|--------|---------|
| `──▷` (inheritance) | Class extends / implements |
| `──>` (association) | Depends on / uses |
| `◇──` (aggregation) | Contains / owns |
| `..>` (dependency) | Creates / reads / weak coupling |
| `<<@Order(N)>>` | Spring ordering in converter pipeline |
| `<<@Service>>` | Spring-managed service bean |
| `<<@Configuration>>` | Spring configuration class |
| `<<record>>` | Java record type |
| `<<enumeration>>` | Java enum |
| `<<sealed>>` | Sealed type (part of sealed hierarchy) |
| `$` suffix | Static member |

**Key architectural patterns visible in the diagram:**

1. **Strategy pattern** — `IConverter` interface with 7 ordered implementations, orchestrated by `CsvToFhirConverter`
2. **Template method** — `BaseConverter` provides shared lookup/extension logic to all converter subclasses
3. **Factory/cache** — `OrchestrationEngine` caches and creates validation engines per IG version
4. **Facade** — `FHIRService` and `CsvService` serve as entry points hiding validation, conversion, persistence, and forwarding complexity
5. **Chain of responsibility** — Converter pipeline processes CSV data through Organization → Patient → Observations → Consent → Encounter → Screening → Procedure
6. **State machine** — `CsvProcessingState` and `State` enums drive processing lifecycle transitions
7. **Decorator** — `TemplateLogger` wraps SLF4J `Logger` with version/thread metadata

## 4. Public Architecture and Responsibilities

### 4.1 Static context bootstrap

#### `SpringContextHolder`

Provides a static `ApplicationContext` for non-Spring-managed code (e.g., Mirth Connect channels):

- Loads `nexus-core-lib/application.yml` and profile-specific YAML from classpath
- Reads `SPRING_PROFILES_ACTIVE` environment variable
- Registers `AppInitializationConfig` which triggers component scanning of `org.techbd`
- Provides `getBean(Class)` for static bean access
- Configures OpenTelemetry `Tracer` with fallback to global default

### 4.2 Configuration layer

#### `CoreAppConfig` (`@ConfigurationProperties(prefix = "org.techbd")`)

Central typed configuration binding:

- `version`, `defaultDatalakeApiUrl`, `operationOutcomeHelpUrl`
- `baseFHIRURL` — Default FHIR profile URL base
- `structureDefinitionsUrls` — Map of resource type → profile URL
- `igPackages` — IG package configuration with version, path, and profile base URL
- `defaultDataLakeApiAuthn` — mTLS strategy configuration (record types)
  - Strategies: `no-mTls`, `aws-secrets`, `mTlsResources`, `post-stdin-payload-to-nyec-datalake-external`, `with-api-key-auth`
- `csv.validation` — Python script paths for CSV validation
- `dataLedgerApiUrl`, `dataLedgerTracking`, `dataLedgerDiagnostics`
- `processingAgent` — Feature flag with tenantId-based activation
- `validationSeverityLevel` — Configurable severity threshold (fatal, error, warning, information)

#### `CoreUdiPrimeJpaConfig` — Primary database (PostgreSQL + JOOQ + HikariCP)

- Creates `udiPrimaryDataSource`, `primaryDslContext`, `primaryJooqConfiguration`
- Conditional on `org.techbd.udi.prime.jdbc.url`
- Configurable pool sizing (max 10, min idle 5, default timeouts)

#### `CoreUdiReaderConfig` — Secondary read-replica database

- Conditional on `org.techbd.udi.uiReadsFromReaderEnabled=true` AND `org.techbd.udi.secondary.jdbc.url`
- Separate `secondaryDslContext` for read operations
- Read-write split pattern support

#### `CoreAsyncConfig`

- Thread pool executor with configurable sizing via environment variables
- Core pool: 20, Max: 50, Queue: 200 (defaults)
- Used for CSV processing and data ledger async operations

#### `Configuration`

- Global Jackson `ObjectMapper` instances (pretty-print and concise)
- Reads all `*TECHBD*` environment variables at startup
- Servlet header name constants (`X-TechBD-*`)

#### `Constant`

- Stateless API URL patterns (no session required)
- Unauthenticated URL patterns (no auth required)
- Security constants (HSTS max age)

#### `Constants` (interface)

- 90+ string constants for request parameters, headers, interaction metadata
- Used across all services for consistent header/parameter naming

### 4.3 Enums and domain types

| Enum | Purpose |
|------|---------|
| `Nature` | Interaction nature types: ORIGINAL_REQUEST_RECEIVED, S3_UPLOAD, FORWARD_HTTP_REQUEST, CSV_VALIDATION_RESULT, etc. |
| `State` | Processing states: ACCEPT_FHIR_BUNDLE, VALIDATION_SUCCESS/FAILED, CONVERTED_TO_FHIR, INGESTION_SUCCESS/FAILED, etc. |
| `Origin` | Message origin: HTTP, SFTP |
| `SourceType` | Payload types: FHIR, CSV, HL7V2, CCDA |
| `CsvProcessingState` | CSV pipeline states: RECEIVED, PROCESSING_INPROGRESS, PROCESSING_COMPLETED, PROCESSING_FAILED |
| `CsvDataValidationStatus` | Validation outcomes: SUCCESS, PARTIAL_SUCCESS, FAILED |

### 4.4 Interactions model

#### `Interactions`

Request/response lifecycle tracking:

- `RequestEncountered` — Captures full request metadata (tenant, method, URL, IP, headers, body, cookies)
- `ResponseEncountered` — Captures response status, headers, body
- `RequestResponseEncountered` — Pairs request + response with interaction UUID
- In-memory history (last 50 interactions, LRU eviction)
- Static helpers for setting active interaction/tenant in parameter maps

### 4.5 CSV-to-FHIR conversion pipeline

#### Architecture overview

```
CSV Files (ZIP) → FileProcessor → CsvOrchestrationEngine → CsvToFhirConverter → FHIR Bundle (JSON)
      │                                    │                       │
      ↓                                    ↓                       ↓
  Encoding validation          Python validation           Ordered converter chain
  Group by patient ID          State tracking               (Organization → Patient →
  File type detection          JOOQ persistence               SexualOrientation → Consent →
                                                               Encounter → Screening →
                                                               Procedure)
```

#### `IConverter` (interface)

Contract for all CSV-to-FHIR resource converters:

- `getResourceType()` — Returns the FHIR ResourceType this converter produces
- `getProfileUrl()` — Returns the SHIN-NY profile URL
- `convert(Bundle, DemographicData, QeAdminData, ScreeningProfileData, List<ScreeningObservationData>, String interactionId, Map<String,String> idsGenerated, String baseFHIRUrl)` — Converts CSV data to BundleEntryComponents
- `setMeta(Resource)` — Applies profile metadata to resources

#### `BaseConverter` (abstract)

Foundation for all converters:

- Code/system/display lookup via `CodeLookupService` → database queries
- Race/ethnicity OMB category classification
- Extension creation helpers
- Static lookup caches (`CODE_LOOKUP`, `SYSTEM_LOOKUP`, `DISPLAY_LOOKUP`)

#### Converter chain (ordered by `@Order`)

| Order | Converter | FHIR Resource |
|-------|-----------|---------------|
| 1 | `OrganizationConverter` | Organization |
| 2 | `PatientConverter` | Patient |
| 3 | `SexualOrientationObservationConverter` | Observation (sexual-orientation) |
| 4 | `ConsentConverter` | Consent |
| 5 | `EncounterConverter` | Encounter |
| 6 | `ScreeningResponseObservationConverter` | Observation (screening-response) |
| 7 | `ProcedureConverter` | Procedure |

#### `BundleConverter`

- Creates the FHIR Bundle container (type: TRANSACTION)
- Sets meta profile URL and security labels (ETH flag for substance abuse sensitivity)

#### `CsvToFhirConverter`

- Orchestrates the converter chain, calling each in order
- Collects BundleEntryComponents from all converters into the Bundle
- Serializes to JSON via HAPI FhirContext

#### CSV data models

| Model | CSV File | Fields |
|-------|----------|--------|
| `DemographicData` | SDOH_PtInfo | 40+ patient fields (identity, demographics, contact, extensions) |
| `QeAdminData` | SDOH_QEadmin | 13 facility/organization fields |
| `ScreeningProfileData` | SDOH_ScreeningProf | 30 encounter/procedure/consent fields |
| `ScreeningObservationData` | SDOH_ScreeningObs | 18 question/answer/category fields |

### 4.6 CSV processing services

#### `CsvService`

Entry point for CSV validation and processing:

- Accepts ZIP file uploads
- Supports both synchronous (`immediate=true`) and asynchronous processing
- State tracking: RECEIVED → PROCESSING_INPROGRESS → PROCESSING_COMPLETED/FAILED
- Delegates to `CsvOrchestrationEngine` for validation and `CsvBundleProcessorService` for conversion

#### `CsvOrchestrationEngine`

- Concurrent session management via `ConcurrentHashMap`
- Orchestrates file validation (UTF-8 encoding, file type detection, required file checks)
- Python-based CSV schema validation via `ProcessBuilder`
- VFS integration for SFTP-based file ingestion
- JOOQ-based persistence of validation results

#### `FileProcessor`

- UTF-8 encoding validation with detailed Unicode character analysis
- Detects: null bytes, surrogate characters, BOM markers, control characters
- Groups files by patient MR ID for multi-patient ZIP processing
- Blocks entire group if any file fails validation

#### `CsvBundleProcessorService`

- Processes validated CSV groups into FHIR bundles
- Handles provenance tracking and data ledger integration
- Error aggregation with detailed diagnostics
- JOOQ persistence of conversion status

### 4.7 FHIR validation engine

#### `OrchestrationEngine`

Manages FHIR validation with multiple engine types:

- **`HapiValidationEngine`** — Full HAPI FHIR validation with IG package loading, pre/post-populate support for code systems, value sets, and LOINC codes
- **`Hl7ValidationEngineEmbedded`** — Embedded HL7 validation (planned/alternate)
- **`Hl7ValidationEngineApi`** — External HL7 validation API client

Features:

- Validation engine caching per IG version + profile URL combination
- Concurrent session management
- OpenTelemetry span instrumentation throughout

#### `FhirBundleValidator`

Configuration holder for validation:

- FHIR context, validator, IG version, package path, profile URLs

#### `PrePopulateSupport`

Pre-populates validation support with code systems:

- SNOMED, ICD-10-CM, CPT, HCPCS, LOINC
- Custom value sets from JSON files
- Loaded from PSV (pipe-separated values) files in IG packages

#### `PostPopulateSupport`

Post-initialization enrichment:

- Custom LOINC observation codes for SDOH questionnaires
- Language subtags (BCP-47) for patient communication preferences

### 4.8 FHIR processing service

#### `FHIRService`

Central FHIR bundle processing orchestrator:

- JSON validation and bundle profile URL validation
- Multi-IG version validation via `OrchestrationEngine`
- Scoring engine forwarding with configurable mTLS strategies
- Disposition-based routing (accept/discard based on validation results)
- Comprehensive JOOQ-based interaction persistence
- OpenTelemetry tracing throughout
- Health check detection and bypass
- OperationOutcome generation with help URLs

#### `FhirReplayService`

Handles replay of failed FHIR submissions:

- Queries database for failed NYEC submission bundles
- Re-submits to scoring engine via `FHIRService`
- Async execution with status tracking
- Error message truncation (4000 char DB limit)

### 4.9 Protocol-specific persistence services

#### `CCDAService`

Persists CCDA processing stages:

- `saveOriginalCcdaPayload()` → State: CCDA_ACCEPT
- `saveValidation()` → State: VALIDATION_SUCCESS/FAILED
- `saveFhirConversionResult()` → State: CONVERTED_TO_FHIR / FHIR_CONVERSION_FAILED
- `saveCcdaValidation()` → State: VALIDATION_SUCCESS/FAILED

#### `HL7Service`

Persists HL7v2 processing stages:

- `saveOriginalHl7Payload()` → State: HL7_ACCEPT
- `saveValidation()` → State: VALIDATION_SUCCESS/FAILED
- `saveFhirConversionResult()` → State: CONVERTED_TO_FHIR / FHIR_CONVERSION_FAILED

### 4.10 External integrations

#### `CoreDataLedgerApiClient`

Async HTTP client for Data Ledger API:

- Sends interaction events (RECEIVED, SENT actions)
- AWS Secrets Manager integration for API key retrieval
- Diagnostic data persistence to database
- Configurable via `dataLedgerTracking` and `dataLedgerDiagnostics` flags

#### `CodeLookupService`

Database-driven code mapping:

- Fetches code, system, and display lookups from `REF_CODE_LOOKUP_CODE_VIEW`
- JSON-based code mappings parsed at runtime
- Cached in static maps on `BaseConverter`

### 4.11 Utility layer

#### `CoreFHIRUtil`

FHIR helper utilities:

- Profile URL resolution from `structureDefinitionsUrls` config
- Base FHIR URL management (singleton pattern)
- IG package version validation and matching
- HTTP request/response header builders
- Bundle ID extraction from JSON
- Cookie and header management for HTTP responses

#### `AWSUtil`

- AWS Secrets Manager value retrieval

#### `AppLogger` + `TemplateLogger`

- Factory pattern for creating versioned loggers
- Decorator pattern automatically appending thread name and TechBD version to all log messages

#### `DateUtil`

- Date parsing/conversion utilities (ISO 8601, Instant, Date)

#### `CsvConversionUtil`

- OpenCSV-based CSV string → domain object conversion
- BOM stripping, field trimming
- SHA-256 hashing for deterministic FHIR resource IDs
- OperationOutcome generation for CSV errors

#### VFS services (`VfsCoreService`, `VfsIngressConsumer`)

- Apache Commons VFS abstraction for file system operations
- ZIP extraction and file grouping for SFTP-based ingestion
- Comprehensive audit trail with sealed event types

## 5. Configuration Contract Summary

### 5.1 Key properties in `application.yml`

| Property path | Purpose |
|--------------|---------|
| `org.techbd.version` | Application version |
| `org.techbd.baseFHIRURL` | Default FHIR profile base URL |
| `org.techbd.structureDefinitionsUrls.*` | Profile URL mappings by resource type |
| `org.techbd.ig-packages.fhir-v4.shinny-packages.*` | IG package versions with paths and profile URLs |
| `org.techbd.ig-packages.fhir-v4.base-packages.*` | External base IG packages (US Core, SDOH, UV-SDC) |
| `org.techbd.defaultDatalakeApiUrl` | Scoring engine endpoint |
| `org.techbd.defaultDataLakeApiAuthn.mTlsStrategy` | Authentication strategy for scoring engine |
| `org.techbd.csv.validation.*` | Python validation script configuration |
| `org.techbd.dataLedgerApiUrl` | Data Ledger API endpoint |
| `org.techbd.dataLedgerTracking` | Enable/disable data ledger event tracking |
| `org.techbd.dataLedgerDiagnostics` | Enable/disable diagnostic persistence |
| `org.techbd.validationSeverityLevel` | Minimum severity for validation failures |
| `org.techbd.operationOutcomeHelpUrl` | Help URL for validation error messages |

### 5.2 Profile variants

| Profile | Purpose | Key overrides |
|---------|---------|---------------|
| `sandbox` | Local development | (empty — uses defaults) |
| `devl` | Development environment | (empty — uses defaults) |
| `stage` | Staging | Uses QA scoring engine endpoint, mTLS via AWS Secrets, data ledger enabled |
| `phiqa` | PHI Quality Assurance | QA scoring engine with mTLS, data ledger enabled |
| `phiprod` | PHI Production | Production scoring engine with mTLS, production data ledger |

### 5.3 Environment variables

| Variable | Purpose |
|----------|---------|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile |
| `PYTHON_SCRIPT_PATH` | Base path for CSV validation Python scripts |
| `ORG_TECHBD_PROCESSING_AGENT_FEATURE_ENABLED` | Processing agent feature flag |
| `ORG_TECHBD_PROCESSING_AGENT_TENANT_IDS` | Tenant IDs for processing agent |
| `ORG_TECHBD_PROCESSING_AGENT_VALUE` | Processing agent value (default: TXD) |
| `ORG_TECHBD_CSV_ASYNC_EXECUTOR_CORE_POOL_SIZE` | Async executor core pool size (default: 20) |
| `ORG_TECHBD_CSV_ASYNC_EXECUTOR_MAX_POOL_SIZE` | Async executor max pool size (default: 50) |
| `ORG_TECHBD_CSV_ASYNC_EXECUTOR_QUEUE_CAPACITY` | Async executor queue capacity (default: 200) |
| `ORG_TECHBD_CSV_ASYNC_EXECUTOR_AWAIT_TERMINATION_SECONDS` | Async executor shutdown wait (default: 30) |

### 5.4 IG package management

Current IG versions:

- **SHIN-NY (production):** v1.7.3 — `http://shinny.org/us/ny/hrsn`
- **SHIN-NY (test):** v1.8.0 — `http://test.shinny.org/us/ny/hrsn`
- **Base packages:** US Core STU-7.0.0, SDOH Clinical Care STU-2.2.0, UV-SDC STU-3.0.0

## 6. Data Flow Diagrams

### 6.1 FHIR bundle processing flow

```
Client → REST API → FHIRService.processBundle()
                          │
                          ├─ validateJson() → Parse JSON
                          ├─ validateBundleProfileUrl() → Check profile URL
                          ├─ OrchestrationEngine.orchestrate()
                          │     ├─ Load/cache validation engine for IG version
                          │     ├─ PrePopulateSupport.build() → Load code systems
                          │     ├─ PostPopulateSupport.update() → Add LOINC codes
                          │     └─ HapiValidationEngine.validate() → HAPI FHIR validation
                          │
                          ├─ isActionDiscard() → Check severity threshold
                          │
                          ├─ sendToScoringEngine() → Forward to NYEC data lake
                          │     ├─ mTLS / API key / stdin auth
                          │     └─ WebClient POST
                          │
                          ├─ JOOQ persistence (RegisterInteractionHttpRequest procedures)
                          │
                          └─ CoreDataLedgerApiClient.processRequest() → Async data ledger
```

### 6.2 CSV processing flow

```
ZIP Upload → CsvService.validateCsvFile()
                  │
                  ├─ State: RECEIVED
                  ├─ CsvOrchestrationEngine.orchestrate()
                  │     ├─ FileProcessor.processAndGroupFiles()
                  │     │     ├─ UTF-8 encoding validation
                  │     │     ├─ File type detection (4 CSV types)
                  │     │     └─ Group by patient MR ID
                  │     ├─ Python validation (ProcessBuilder)
                  │     └─ JOOQ persistence of validation results
                  │
                  ├─ State: PROCESSING_INPROGRESS
                  ├─ CsvBundleProcessorService.processPayload()
                  │     ├─ CsvConversionUtil.convertCsvString*() → Parse CSVs
                  │     ├─ CsvToFhirConverter.convert() → Converter chain
                  │     │     ├─ OrganizationConverter (Order 1)
                  │     │     ├─ PatientConverter (Order 2)
                  │     │     ├─ SexualOrientationObservationConverter (Order 3)
                  │     │     ├─ ConsentConverter (Order 4)
                  │     │     ├─ EncounterConverter (Order 5)
                  │     │     ├─ ScreeningResponseObservationConverter (Order 6)
                  │     │     └─ ProcedureConverter (Order 7)
                  │     ├─ FHIR validation (optional)
                  │     └─ Forward to scoring engine
                  │
                  └─ State: PROCESSING_COMPLETED / PROCESSING_FAILED
```

## 7. Test Coverage and Quality Signals

### 7.1 Existing test surface

| Test class | Scope | Status |
|-----------|-------|--------|
| `BaseConverterTest` | Code lookup, extension creation, profile URLs | Active |
| `ConsentConverterTest` | Consent resource generation from CSV | Active |
| `EncounterConverterTest` | Encounter resource generation from CSV | Active |
| `OrganizationConverterTest` | Organization resource generation from CSV | Active |
| `PatientConverterTest` | Patient resource generation from CSV (extensive) | Active |
| `ProcedureConverterTest` | Procedure resource generation from CSV | Active |
| `ScreeningResponseObservationConverterTest` | Screening observation generation | Active |
| `SexualOrientationObservationConverterTest` | Sexual orientation observation | Active |
| `CCDAServiceTest` | CCDA payload/validation persistence | Active |
| `HL7ServiceTest` | HL7 payload/validation persistence | Active |
| `DataLedgerApiClientTest` | Data Ledger API async requests | Active |
| `FileProcessorTest` | File grouping, encoding, multi-group | Active |
| `OrchestrationEngineTest` | FHIR validation orchestration, IG caching | Active |
| `IgPublicationIssuesTest` | 25+ SHIN-NY example bundle validations | Active |
| `BaseIgValidationTest` | Abstract base class (not a test) | Utility |
| `CsvTestHelper` | Test data factory (not a test) | Utility |
| `CsvBundleProcessorServiceTest` | CSV bundle processing | **COMMENTED OUT** |
| `CsvServiceTest` | CSV file validation | **COMMENTED OUT** |

### 7.2 Coverage gaps and concerns

1. **Two test classes entirely commented out** (`CsvBundleProcessorServiceTest`, `CsvServiceTest`) — the CSV processing pipeline's integration-level tests are disabled.
2. **`testFailureIgnore=true`** in surefire means CI doesn't fail on test failures.
3. **No integration tests** for database interactions (all JOOQ calls are tested with mocked DSLContext).
4. **No tests** for `FHIRService` or `FhirReplayService` — the largest and most complex classes.
5. **No tests** for `VfsCoreService` or `VfsIngressConsumer` file ingestion.
6. **No tests** for `CoreFHIRUtil` utility methods.
7. **33 test resource JSON files** exist, well used by `IgPublicationIssuesTest` and `OrchestrationEngineTest`.

## 8. Findings: Risks and Code Smells

### 8.1 High severity

1. **System-scoped JOOQ JAR.** Pre-built binary artifact (`techbd-udi-jooq-ingress.auto.jar`) in `lib/` breaks reproducible builds, couples to a specific DB schema version, and makes schema changes invisible.

2. **`testFailureIgnore=true`.** Surefire is configured to swallow test failures, meaning broken tests go undetected in CI. Combined with 2 commented-out test classes, this severely undermines code quality gates.

3. **No tests for FHIRService.** The largest and most complex class (~1700+ lines) orchestrating FHIR validation, scoring engine forwarding, and mTLS handling has zero test coverage.

4. **Static mutable state in BaseConverter.** `CODE_LOOKUP`, `SYSTEM_LOOKUP`, `DISPLAY_LOOKUP` are static mutable maps populated lazily. This is thread-unsafe in concurrent request processing and prevents proper test isolation.

5. **JAXB version mixing.** `javax.xml.bind:jaxb-api:2.3.1` (Java EE namespace) alongside `com.sun.xml.bind:jaxb-core:4.0.5` and `jaxb-impl:4.0.5` (Jakarta namespace). This can cause `ClassNotFoundException` at runtime depending on classpath ordering.

### 8.2 Medium severity

6. **AWS SDK version drift.** `nexus-core-lib` uses AWS SDK 2.17.87 while `nexus-ingestion-api` uses 2.28.0. Sharing AWS clients between modules could cause binary compatibility issues.

7. **GraalVM version mismatch.** `graal-sdk:22.3.0` and `js:24.1.2` are from different major release trains. The GraalVM usage case is unclear from the codebase.

8. **SpringContextHolder static bootstrap.** Creating a static `ApplicationContext` in a class initializer is fragile, hard to test, and conflicts with Spring Boot's lifecycle management. Used for Mirth Connect integration.

9. **Large FHIRService class (~1700 lines).** Combines JSON validation, profile URL validation, IG orchestration, mTLS management, scoring engine forwarding, database persistence, and error handling — a candidate for decomposition.

10. **Commented-out test classes.** `CsvBundleProcessorServiceTest` and `CsvServiceTest` indicate either test rot or deferred implementation.

11. **Duplicate JSON libraries.** Both Jackson (primary) and `org.json` are present. `org.json` is used in `JsonText` and metadata builders.

12. **JPA milestone dependency.** `jakarta.persistence-api:3.2.0-M2` is a pre-release artifact.

13. **36 TODO/FIXME markers** scattered across the codebase, many related to hardcoded values in converters.

### 8.3 Low severity

14. **Placeholder mainClass.** `maven-jar-plugin` configured with non-existent `com.example.app.Main`.

15. **Converter TODOs.** Most of the 36 TODOs are "remove static reference" in converters — indicating hardcoded FHIR coding values that should come from configuration/database.

16. **`joda-time` dependency.** Modern Java `java.time` APIs are used alongside joda-time. The joda-time dependency may be transitively needed but warrants review.

## 9. Recommended Refactoring Roadmap

### Phase 1: Build and test integrity

1. **Remove `testFailureIgnore=true`** from surefire — make CI fail on broken tests.
2. **Restore or delete commented-out tests** (`CsvBundleProcessorServiceTest`, `CsvServiceTest`).
3. Add unit tests for `FHIRService`, particularly `processBundle()`, `sendToScoringEngine()`, and `validateBundleProfileUrl()`.
4. Fix placeholder `mainClass` in maven-jar-plugin.
5. Replace system-scoped JOOQ JAR with a proper JOOQ code generation plugin (or at minimum, a repository-hosted artifact).

### Phase 2: Dependency hygiene

1. Align AWS SDK version with `nexus-ingestion-api` (→ 2.28.0 or newer managed BOM).
2. Remove `javax.xml.bind:jaxb-api` — use `jakarta.xml.bind-api` consistently.
3. Resolve GraalVM version mismatch — use single BOM or align versions.
4. Replace `jakarta.persistence-api:3.2.0-M2` with a stable release.
5. Evaluate and remove `joda-time` and `org.json` if unused or replaceable.
6. Remove `spring-boot-starter-jooq:3.3.1` explicit version — let parent BOM manage.

### Phase 3: Architecture cleanup

1. **Decompose `FHIRService`** into validation, forwarding, and persistence services.
2. **Eliminate static mutable state** in `BaseConverter` — inject code lookup maps via Spring-managed beans with proper caching (Caffeine/Spring Cache).
3. **Refactor `SpringContextHolder`** — document its purpose for Mirth Connect integration and consider alternatives (Spring Boot auto-configuration support JAR).
4. Resolve the 36 TODO markers, particularly the "remove static reference" items in converters.
5. Move from `java.net.http.HttpClient` in `CoreDataLedgerApiClient` to Spring's `WebClient` for consistency (already in the classpath via `spring-boot-starter-webflux`).

### Phase 4: Database and integration improvements

1. Add integration tests with embedded PostgreSQL (testcontainers) for JOOQ routines.
2. Implement proper JOOQ code generation from database schema instead of pre-built JAR.
3. Add VFS integration tests for file ingestion pipeline.

## 10. Cross-Module Coupling Snapshot

`nexus-core-lib` is a **library dependency** consumed by other modules:

### Outbound dependencies (consumed by nexus-core-lib)

- **PostgreSQL** — Primary data store via JOOQ (auto-generated classes in system-scoped JAR)
- **NYEC Scoring Engine** — FHIR bundle forwarding with mTLS/API key auth
- **Data Ledger API** — Interaction event tracking (async HTTP)
- **AWS Secrets Manager** — API key and mTLS certificate retrieval
- **AWS SQS** — (dependency present, usage unclear in this module — likely consumed by downstream modules)
- **SHIN-NY IG Packages** — FHIR validation profiles (file-based, bundled in resources)

### Inbound dependencies (modules that depend on nexus-core-lib)

Based on the repository structure, this module is consumed by:

- `hub-prime` — Web application that uses FHIR/CSV/CCDA/HL7 services and the database layer
- Mirth Connect channels — via `SpringContextHolder` static context (external integration)

### No direct coupling to

- `nexus-ingestion-api` — Independent ingestion relay (uses its own S3/SQS directly)
- `csv-service` — Has its own converters (possible duplication with nexus-core-lib converters)
- `fhir-validation-service` — Appears to be a separate validation module
- `core-lib` — Older library module (possible predecessor to nexus-core-lib)

### Potential duplication concerns

The converter packages (`org.techbd.converters.csv`) exist in **both** `nexus-core-lib` and `csv-service`. The recent `git pull` log shows changes in converters in both locations, suggesting active parallel maintenance of similar code — a consolidation candidate.
