# mTLS Certificate Support Documentation

This document provides comprehensive details about mutual TLS (mTLS) certificate support in the `InteractionsFilter` component of the nexus-ingestion-api service.

## Overview

The `InteractionsFilter` implements server-side mTLS certificate validation using industry-standard libraries including BouncyCastle for PEM parsing and Java's PKIX (Public Key Infrastructure X.509) framework for certificate path validation.

## Certificate Format Support

### Supported Certificate Formats

- **PEM (Privacy-Enhanced Mail)**: Primary and only supported format
  - Text-based format with Base64 encoding
  - Uses standard PEM delimiters: `-----BEGIN CERTIFICATE-----` and `-----END CERTIFICATE-----`
  - Supports both single certificates and certificate chains in a single PEM block

### Unsupported Formats

- **DER (Distinguished Encoding Rules)**: Binary format not supported
- **PKCS#12 (.p12/.pfx)**: Container formats not supported
- **JKS (Java KeyStore)**: Binary keystore format not supported
- **Other formats**: Only PEM format is accepted

## Certificate Chain Support

### Multi-Certificate Chains ✅

The system **fully supports** certificate chains containing multiple certificates:

```pem
-----BEGIN CERTIFICATE-----
[Client Certificate - End Entity]
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
[Intermediate CA Certificate]
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
[Root CA Certificate]
-----END CERTIFICATE-----
```

### Chain Processing Details

- **Parsing**: Uses BouncyCastle `PEMParser` to extract all certificates from a single PEM string
- **Validation**: Constructs a `CertPath` from the entire certificate array
- **Order**: Certificate order in the PEM is preserved during parsing
- **Length**: No explicit limit on chain length (limited by memory and PKIX validation constraints)

### Chain Structure Requirements

1. **End Entity Certificate**: Should be first in the chain (client certificate)
2. **Intermediate CAs**: Can have zero or more intermediate certificates
3. **Root CA**: May or may not be included in the client chain (typically provided separately in CA bundle)

## Certificate Types Supported

### Standard X.509 Certificates ✅

- **End Entity Certificates**: Client certificates for authentication
- **Intermediate CA Certificates**: Certificates that sign other certificates
- **Root CA Certificates**: Self-signed certificates at the top of the trust hierarchy

### Self-Signed Certificates ⚠️

**Default Behavior**: Self-signed certificates are **REJECTED** unless explicitly included in the CA bundle

**Detection Logic**:
```java
// Self-signed detection
if (clientCert.getSubjectX500Principal().equals(clientCert.getIssuerX500Principal())) {
    // Certificate is self-signed
}
```

**Support Options**:
1. **Secure Method**: Add self-signed certificates to the S3 CA bundle
2. **Development Method**: Uncomment optional code block in `verifyCertificateChain()` to accept any self-signed certificate

### Certificate Extensions Support

The system supports all standard X.509v3 extensions through the underlying Java PKIX validation:

- **Basic Constraints**: CA flag and path length constraints
- **Key Usage**: Digital signature, key encipherment, etc.
- **Extended Key Usage**: Client authentication, server authentication, etc.
- **Subject Alternative Name (SAN)**: DNS names, IP addresses, email addresses
- **Authority Key Identifier**: Links to issuing CA
- **Subject Key Identifier**: Unique key identifier

## Encoding and Transport

### Header-Based Certificate Delivery

Certificates are delivered via HTTP header: `X-TechBD-MTLS-Client-Cert`

### Encoding Support

**URL Encoding (Primary)** ✅:
- All certificate headers are expected to be URL-encoded
- System automatically URL-decodes the header value
- Special character handling prevents corruption of embedded Base64 content

**Character Protection Logic**:
```java
// Protect Base64 content from URL decoder corruption
v = v.replace("+", "%2B");    // Protect plus signs
v = v.replace(" ", "%20");    // Protect spaces
clientCertPem = java.net.URLDecoder.decode(v, StandardCharsets.UTF_8);
```

**Base64 Encoding** ❌:
- **No longer supported** (removed in recent updates)
- Legacy Base64 detection and decoding logic has been eliminated
- Only URL encoding is now accepted

### Encoding Limitations

1. **URL Decoder Corruption Prevention**: The system protects against URL decoder treating `+` as spaces, which would corrupt Base64 content within PEM certificates
2. **Character Set**: Only UTF-8 encoding is supported
3. **Size Limits**: Limited by HTTP header size constraints (typically 8KB-32KB depending on server configuration)

## Validation Process

### PKIX Certificate Path Validation

The system uses Java's standard PKIX validation framework:

```java
CertPathValidator.getInstance("PKIX").validate(certPath, params);
```

### Trust Anchor Configuration

**CA Bundle Source**: 
- Certificates loaded from S3 bucket specified by `MTLS_BUCKET_NAME` environment variable
- CA bundle file naming: `{portEntry.mtls}-bundle.pem`
- Example: If `portEntry.mtls = "api"`, looks for `api-bundle.pem`

**Trust Anchor Rules**:
- All certificates in the CA bundle become trust anchors
- Client certificate chains must ultimately trace back to one of these trust anchors
- Self-signed certificates require explicit inclusion in CA bundle (unless development mode enabled)

### Validation Features

**Enabled Validations** ✅:
- Certificate signature verification
- Certificate chain construction
- Trust anchor validation
- Certificate validity period checking
- Certificate extension processing

**Disabled Validations** ❌:
- **Revocation Checking**: Explicitly disabled (`params.setRevocationEnabled(false)`)
- **OCSP (Online Certificate Status Protocol)**: Not performed
- **CRL (Certificate Revocation List)**: Not checked

### Validation Limitations

1. **No Revocation Checking**: Certificates are not checked against CRLs or OCSP responders
2. **No Hostname Verification**: The system validates certificate chains but does not perform hostname matching
3. **No Custom Validation**: Only standard PKIX validation rules are applied

## Caching Mechanism

### CA Bundle Caching

**Implementation**: Google Guava Cache
```java
Cache<String, X509Certificate[]> caCache = CacheBuilder.newBuilder()
    .expireAfterWrite(Duration.ofMinutes(60))
    .build();
```

**Cache Characteristics**:
- **TTL**: 60 minutes
- **Key Format**: `s3://{bucket}/{key}`
- **Value**: Parsed X509Certificate array
- **Eviction**: Time-based expiration only

### Performance Benefits

- **S3 Call Reduction**: Avoids repeated S3 API calls for the same CA bundle
- **Parsing Optimization**: Cached certificates are pre-parsed
- **Concurrent Access**: Thread-safe cache implementation

## Error Handling and Diagnostics

### Certificate Parsing Errors

**Common Parsing Failures**:
- Malformed PEM format
- Invalid Base64 encoding within PEM blocks
- Missing PEM delimiters
- Corrupted certificate data

**Error Response**: Returns detailed `CertificateException` with diagnostic information

### Validation Errors

**PKIX Validation Failures**:
```java
CertPathValidatorException: reason=NO_TRUST_ANCHOR, certIndex=0, message=Path does not chain with any of the trust anchors
```

**Detailed Error Information**:
- Exception class name
- PKIX failure reason
- Certificate index in chain where failure occurred
- Descriptive error message

### Logging and Debugging

**Certificate Information Logging**:
```java
LOG.info("clientChain[{}] subject='{}' issuer='{}' serial={}", 
         i, cert.getSubjectX500Principal(), cert.getIssuerX500Principal(), cert.getSerialNumber());
```

**Logged Details**:
- Certificate subject distinguished name
- Issuer distinguished name  
- Serial number
- Certificate chain length
- CA bundle certificate count
- Cache hit/miss status

## Configuration Requirements

### Environment Variables

- `MTLS_BUCKET_NAME`: S3 bucket containing CA bundles
- Must be accessible by the service's AWS credentials

### Port Configuration

Each port requiring mTLS must have:
- `mtls` field set to a non-blank value
- Corresponding `{mtls}-bundle.pem` file in the S3 bucket

### Feature Flags

mTLS processing only occurs when:
- `FeatureEnum.DEBUG_LOG_REQUEST_HEADERS` is enabled
- Request is not to health check endpoints (`/actuator/health`)

## Security Considerations

### Trust Model

- **Explicit Trust**: Only certificates in CA bundle are trusted
- **No Implicit Trust**: System root CAs are not used
- **Self-Signed Restrictions**: Self-signed certificates require explicit configuration

### Attack Surface

**Mitigated Risks**:
- Certificate chain validation prevents invalid certificates
- URL encoding prevents header injection attacks
- Cache timeout prevents stale certificate issues

**Remaining Risks**:
- No revocation checking may allow compromised certificates
- Large certificate chains could cause memory issues
- S3 dependency creates external failure point

## Best Practices

### Certificate Management

1. **CA Bundle Maintenance**: Regularly update CA bundles in S3
2. **Certificate Rotation**: Plan for certificate expiration and renewal
3. **Chain Optimization**: Include necessary intermediate certificates in client chains

### Deployment Considerations

1. **S3 Permissions**: Ensure service has read access to MTLS bucket
2. **Cache Tuning**: Adjust cache TTL based on certificate update frequency
3. **Monitoring**: Implement alerts for certificate validation failures

### Development and Testing

1. **Use Test Program**: Utilize `MtlsTestMain` for certificate validation testing
2. **Self-Signed Support**: Enable self-signed certificate support for development environments
3. **Logging**: Enable detailed logging for troubleshooting certificate issues

## Troubleshooting Guide

### Common Issues

**"No CA certificates provided for validation"**:
- CA bundle not found in S3
- Incorrect S3 bucket or key name
- S3 access permissions issue

**"Certificate chain validation failed: CertPathValidatorException: reason=NO_TRUST_ANCHOR"**:
- Client certificate chain doesn't link to any CA in bundle
- Missing intermediate certificates
- Self-signed certificate without explicit trust

**"URL decoding failed"**:
- Certificate header not properly URL-encoded
- Special characters corrupting Base64 content

### Diagnostic Steps

1. **Check Logs**: Review detailed certificate logging output
2. **Test Standalone**: Use `MtlsTestMain` to isolate validation issues
3. **Verify CA Bundle**: Ensure CA bundle contains correct certificates
4. **Check Encoding**: Verify certificate header is properly URL-encoded

This documentation provides a comprehensive overview of the mTLS certificate support implementation, covering all aspects from supported formats to troubleshooting common issues.