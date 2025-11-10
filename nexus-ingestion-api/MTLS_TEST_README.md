# mTLS Certificate Validation Test

This standalone Java program allows you to test mTLS certificate validation independently of the Spring Boot application. It mirrors the certificate validation logic from `InteractionsFilter` and provides detailed output about the validation process.

## Files Created

- `nexus-ingestion-api/src/test/java/org/techbd/ingest/controller/MtlsTestMain.java` - The main test program
- `compile-and-run-mtls-test.sh` - Bash compilation and execution script  
- `compile-and-run-mtls-test.fish` - Fish shell compilation and execution script

## Usage

### Using the Fish Script (Recommended for your shell)

```fish
./compile-and-run-mtls-test.fish <client-cert-file> <ca-bundle-file> [--allow-self-signed]
```

### Using the Bash Script

```bash
./compile-and-run-mtls-test.sh <client-cert-file> <ca-bundle-file> [--allow-self-signed]
```

### Manual Compilation and Execution

1. **Compile the project:**
   ```fish
   cd /home/anoop/workspaces/github.com/anoopvarma-2000-p/polyglot-prime
   mvn compile test-compile -f nexus-ingestion-api/pom.xml
   ```

2. **Get the classpath:**
   ```fish
   set CLASSPATH (mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout -f nexus-ingestion-api/pom.xml 2>/dev/null)
   set CLASSPATH "nexus-ingestion-api/target/classes:nexus-ingestion-api/target/test-classes:$CLASSPATH"
   ```

3. **Run the test:**
   ```fish
   java -cp "$CLASSPATH" org.techbd.ingest.controller.MtlsTestMain <client-cert-file> <ca-bundle-file> [--allow-self-signed]
   ```

## Arguments

- `<client-cert-file>`: Path to PEM file containing the client certificate chain to validate
- `<ca-bundle-file>`: Path to PEM file containing CA certificates for validation
- `--allow-self-signed`: Optional flag to accept self-signed certificates

## Examples

### Test a standard certificate chain
```fish
./compile-and-run-mtls-test.fish client.pem ca-bundle.pem
```

### Test a self-signed certificate (allowing it to pass)
```fish
./compile-and-run-mtls-test.fish self-signed.pem ca-bundle.pem --allow-self-signed
```

### Test with detailed certificate information
The program will automatically display:
- All certificates in the client chain (subject, issuer, serial, validity dates)
- All CA certificates in the bundle
- Whether any certificates are self-signed
- Detailed validation results or error messages

## Sample Output

```
=== mTLS Certificate Validation Test ===
Client Certificate File: client.pem
CA Bundle File: ca-bundle.pem
Allow Self-Signed: false

1. Loading client certificate chain...
   Loaded 1 client certificate(s)
   Client Cert [0]:
     Subject: CN=client.example.com,O=Example Corp
     Issuer:  CN=Example CA,O=Example Corp
     Serial:  123456789
     Valid:   Mon Jan 01 00:00:00 UTC 2024 to Mon Jan 01 00:00:00 UTC 2025

2. Loading CA certificate bundle...
   Loaded 2 CA certificate(s)
   CA Cert [0]:
     Subject: CN=Example CA,O=Example Corp
     Issuer:  CN=Example Root CA,O=Example Corp
     Serial:  987654321
   CA Cert [1]:
     Subject: CN=Example Root CA,O=Example Corp
     Issuer:  CN=Example Root CA,O=Example Corp
     Serial:  111222333

3. Performing certificate validation...
   ✅ VALIDATION SUCCESSFUL - Certificate chain is valid!

=== Test completed successfully ===
```

## Error Scenarios

The program will provide detailed error messages for common issues:

- **File not found**: Clear message about which file is missing
- **Invalid PEM format**: Details about parsing errors
- **No certificates found**: When PEM files don't contain valid certificates
- **Validation failures**: Detailed PKIX validation error messages including:
  - Certificate path validation errors
  - Trust anchor issues
  - Certificate chain problems
  - Self-signed certificate rejection (unless allowed)

## Integration with Your Workflow

This test program is particularly useful for:

1. **Debugging certificate issues** before deploying to the full application
2. **Testing different certificate chains** against your CA bundle
3. **Validating self-signed certificates** with the `--allow-self-signed` flag
4. **Understanding certificate validation failures** with detailed error reporting

The validation logic exactly mirrors what happens in `InteractionsFilter`, so successful validation here means the certificates should work in your Spring Boot application.