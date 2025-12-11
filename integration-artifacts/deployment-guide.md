## Infra Setup

1. In the BridgeLink CDK, update the `Target Group protocol` from `HTTPS` to `HTTP` for all channels.
2. For the EC2 instance IAM role, add the `AWSSecretsManagerReadOnlyAccess` policy.

## Deployment Guide for the Bridgeink artifacts

This section outlines the step-by-step process for deploying the channels, lookup manager data etc to the BridgeLink.

### Prerequisites

* Access to the **BridgeLink instance** with correct user credentials and permissions
* The deployment files (XML/JSON) from the repository. Refer the below tables for the links.
* AWS related Jar files are readily available in the `aws-custom-jars` folder. (Settings -> Resources -> aws-custom-jars)

### Step 1: Import Lookup Manager Data

As the channels use lookup tables for dynamic configuration, this data must be imported first.

1.  **Access Lookup Table Manager:** Navigate to the **Lookup Table Manager** section.
2.  **Import Data:**
    * Click the **Import JSON** button.
    * Select **File** option, 
    * Browse and select the committed lookup file (e.g., `lookup_group_schemafiles_export.json`), click **Import**.
    * Follow the prompts to merge or overwrite the existing lookup data as required by the deployment.
3.  **Verify:** After importing, check a few key-value pairs to ensure the data has loaded correctly.

#### Lookup manager data list

| # | Export file name                   | Repository Path                              |
|---|------------------------|------------------------------------------|
| 1 | lookup_group_schemafiles_export.json | [link](https://github.com/tech-by-design/polyglot-prime/blob/main/integration-artifacts/lookup-manager/nexus/lookup_group_schemafiles_export.json) |
| 2 | <lookup_group_env_export.json> |  To be added |

### Step 2: Import and Deploy Channels

This step involves loading the channel configurations into the BridgeLink.

1.  Navigate to the main **Channels** from the dashboard.
2.  **Import Channels:**
    * Click the **Import Channel**.
    * Select the committed channel configuration file.
    * The channel will now appear in the main screen. Now select **Save** and then **Deploy Channel**.
    * Repeat this for all channels, and the list is given below.
3.  **Review Configuration:** Select the newly imported channels and perform a quick visual review to ensure all settings (Source, Destinations, Scripts) look correct.
4.  **Confirm Status:** The channel status should change from **Undeployed** to **Deployed** (often shown in green).

#### Channel list

| # | Channel file name      | Description                              |
|---|------------------------|------------------------------------------|
| 1 | FHIR Bundle Validate channel  | [FhirBundle.xml](https://github.com/tech-by-design/polyglot-prime/blob/main/integration-artifacts/fhir/fhir-techbd-channel-files/nexus/FhirBundle.xml) |
| 2 | FHIR Bundle channel           | [FhirBundleValidate.xml](https://github.com/tech-by-design/polyglot-prime/blob/main/integration-artifacts/fhir/fhir-techbd-channel-files/nexus/FhirBundleValidate.xml) |
| 3 | CCDA Bundle and Bundle Validate Channel | [TechBD CCD Workflow.xml](https://github.com/tech-by-design/polyglot-prime/blob/main/integration-artifacts/ccda/ccda-techbd-channel-files/nexus-sandbox/TechBD%20CCD%20Workflow.xml) |
| 4 |CSV Bundle Validate Channel | [FlatFileCsvBundleValidate.xml](https://github.com/tech-by-design/polyglot-prime/blob/main/integration-artifacts/flatfile/flatfile-techbd-channel-files/nexus-sandbox/) |
| 5 |CSV Bundle Channel | [FlatFileCsvBundle.xml](https://github.com/tech-by-design/polyglot-prime/blob/main/integration-artifacts/flatfile/flatfile-techbd-channel-files/nexus-sandbox/FlatFileCsvBundle.xml) |
| 6 |Hl7 Bundle and Bundle validate Channel | [TechBD HL7 Workflow.xml](https://github.com/tech-by-design/polyglot-prime/blob/main/integration-artifacts/hl7v2/hl7-techbd-channel-files/nexus-sandbox/TechBD%20HL7%20Workflow.xml) |

### Step 3: Verification of Secret Manager in the AWS

The secret manager is to be updated with the proper key-values which are referred in the lookup manager.

| # | Variable | Secret Manger Key | Description |
|---|----------|-------------------|-------------|
|1|MC_FHIR_BUNDLE_SUBMISSION_API_URL||FHIR channel endpoint. Other channels (CSV, HL7, CCDA) will call this API for submitting FHIR Bundles to the NyeC Ingestion API.|
|2|MC_CCDA_SCHEMA_FOLDER||Folder containing XSLT files used for CCDA schema validation. These XSLTs are applied to validate incoming CCDA XML payloads before conversion.|
|3|HL7_XSLT_PATH||Folder containing XSLT files used for HL7 schema validation. These files define schema rules for HL7 v2 messages to ensure structure compliance.|
|4|BASE_FHIR_URL||Base FHIR IG URL used for generating Bundles from HL7 and CCDA inputs.|
|5|PROFILE_URL_BUNDLE||Base FHIR IG URL used for generating Bundles from HL7 and CCDA inputs.|
|6|PROFILE_URL_PATIENT||Base FHIR IG URL used for generating Bundles from HL7 and CCDA inputs.|
|7|PROFILE_URL_ENCOUNTER||Base FHIR IG URL used for generating Bundles from HL7 and CCDA inputs.|
|8|PROFILE_URL_CONSENT||Base FHIR IG URL used for generating Bundles from HL7 and CCDA inputs.|
|9|PROFILE_URL_ORGANIZATION||Base FHIR IG URL used for generating Bundles from HL7 and CCDA inputs.|
|10|PROFILE_URL_OBSERVATION||Base FHIR IG URL used for generating Bundles from HL7 and CCDA inputs.|
|11|PROFILE_URL_SEXUAL_ORIENTATION||Base FHIR IG URL used for generating Bundles from HL7 and CCDA inputs.|
|12|PROFILE_URL_QUESTIONNAIRE||Base FHIR IG URL used for generating Bundles from HL7 and CCDA inputs.|
|13|PROFILE_URL_QUESTIONNAIRE_RESPONSE||Base FHIR IG URL used for generating Bundles from HL7 and CCDA inputs.|
|14|PROFILE_URL_PRACTITIONER||Base FHIR IG URL used for generating Bundles from HL7 and CCDA inputs.|
|15|PROFILE_URL_PROCEDURE||Base FHIR IG URL used for generating Bundles from HL7 and CCDA inputs.|
|16|MC_VALID_FHIR_URLS||Valid FHIR IG base URLs accepted|
|17|TECHBD_DEFAULT_DATALAKE_API_URL||API endpoint for submitting validated FHIR Bundles to NYeC Data Lake.|
|18|TECHBD_NYEC_DATALAKE_API_KEY||API key used for authentication with the NYeC Data Lake.|
|19|BL_FHIR_BUNDLE_VALIDATION_API_URL||URL of the FHIR Validation Service - HAPI FHIR Validator endpoint. The FHIR channel calls this to validate Bundles against Implementation Guides.|
|20|TECHBD_CSV_SERVICE_API_URL||CSV Service API endpoint — used by the CSV channel to convert incoming data into FHIR Bundles before forwarding them to the FHIR channel.
|21|MC_JDBC_URL||PostgreSQL connection details for the Hub database.|
|22|MC_JDBC_USERNAME||PostgreSQL connection details for the Hub database.|
|23|MC_JDBC_PASSWORD||PostgreSQL connection details for the Hub database.|
|24|DATA_LEDGER_API_URL||API endpoint for sending data to the Data Ledger service.|
|25|DATA_LEDGER_TRACKING||Enable or disable Data Ledger tracking. If true, incoming Bundle details will be forwarded to the Data Ledger.|
|26|TECHBD_NYEC_DATALEDGER_API_KEY||API key for authenticating requests to the Data Ledger service.|

### Step 4: Verification and Testing

After deployment, verify that the channels are functioning as expected.

1.  **Check Server Logs:** Review the server logs for any **deployment errors** or warnings.
2.  **Execute Test Messages:** Send a representative message through each newly deployed channel, by callig the corresponding APIs. `curl`, `httpyac` etc can be used.
3.  **Monitor Statistics:** Check the **Channel Statistics** or **Message Browser** to confirm:
    * The message was **received** by the channel.
    * The message was **processed** successfully.
    * The message was **sent** to the intended destination without errors.
    * Ensure any reliance on the new **Global Script** functions or **Lookup Tables** is working correctly.

