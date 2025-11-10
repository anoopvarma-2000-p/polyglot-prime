package org.techbd.ingest.controller;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMParser;

/**
 * Standalone Java application to test mTLS certificate validation.
 * 
 * Usage:
 *   java MtlsTestMain <client-cert-file> <ca-bundle-file> [--allow-self-signed]
 * 
 * Arguments:
 *   client-cert-file: Path to PEM file containing client certificate chain
 *   ca-bundle-file: Path to PEM file containing CA certificates for validation
 *   --allow-self-signed: Optional flag to accept self-signed certificates
 * 
 * Examples:
 *   java MtlsTestMain client.pem ca-bundle.pem
 *   java MtlsTestMain self-signed.pem ca-bundle.pem --allow-self-signed
 */
public class MtlsTestMain {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java MtlsTestMain <client-cert-file> <ca-bundle-file> [--allow-self-signed]");
            System.err.println();
            System.err.println("Arguments:");
            System.err.println("  client-cert-file    : Path to PEM file containing client certificate chain");
            System.err.println("  ca-bundle-file      : Path to PEM file containing CA certificates");
            System.err.println("  --allow-self-signed : Optional flag to accept self-signed certificates");
            System.err.println();
            System.err.println("Examples:");
            System.err.println("  java MtlsTestMain client.pem ca-bundle.pem");
            System.err.println("  java MtlsTestMain self-signed.pem ca-bundle.pem --allow-self-signed");
            System.exit(1);
        }

        String clientCertFile = args[0];
        String caBundleFile = args[1];
        boolean allowSelfSigned = args.length > 2 && "--allow-self-signed".equals(args[2]);

        System.out.println("=== mTLS Certificate Validation Test ===");
        System.out.println("Client Certificate File: " + clientCertFile);
        System.out.println("CA Bundle File: " + caBundleFile);
        System.out.println("Allow Self-Signed: " + allowSelfSigned);
        System.out.println();

        try {
            // Load and parse client certificate chain
            System.out.println("1. Loading client certificate chain...");
            String clientPem = readFileContent(clientCertFile);
            X509Certificate[] clientChain = parsePemChain(clientPem);
            System.out.println("   Loaded " + clientChain.length + " client certificate(s)");
            
            for (int i = 0; i < clientChain.length; i++) {
                X509Certificate cert = clientChain[i];
                System.out.println("   Client Cert [" + i + "]:");
                System.out.println("     Subject: " + cert.getSubjectX500Principal());
                System.out.println("     Issuer:  " + cert.getIssuerX500Principal());
                System.out.println("     Serial:  " + cert.getSerialNumber());
                System.out.println("     Valid:   " + cert.getNotBefore() + " to " + cert.getNotAfter());
                
                // Check if self-signed
                boolean isSelfSigned = cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
                if (isSelfSigned) {
                    System.out.println("     >>> SELF-SIGNED CERTIFICATE DETECTED <<<");
                }
            }
            System.out.println();

            // Load and parse CA bundle
            System.out.println("2. Loading CA certificate bundle...");
            String caPem = readFileContent(caBundleFile);
            X509Certificate[] caCerts = parsePemChain(caPem);
            System.out.println("   Loaded " + caCerts.length + " CA certificate(s)");
            
            for (int i = 0; i < caCerts.length; i++) {
                X509Certificate cert = caCerts[i];
                System.out.println("   CA Cert [" + i + "]:");
                System.out.println("     Subject: " + cert.getSubjectX500Principal());
                System.out.println("     Issuer:  " + cert.getIssuerX500Principal());
                System.out.println("     Serial:  " + cert.getSerialNumber());
            }
            System.out.println();

            // Perform certificate validation
            System.out.println("3. Performing certificate validation...");
            try {
                verifyCertificateChain(clientChain, caCerts, allowSelfSigned);
                System.out.println("   ✅ VALIDATION SUCCESSFUL - Certificate chain is valid!");
            } catch (CertificateException e) {
                System.out.println("   ❌ VALIDATION FAILED - " + e.getMessage());
                if (e.getCause() != null) {
                    System.out.println("   Root cause: " + e.getCause().getMessage());
                }
                System.exit(1);
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println();
        System.out.println("=== Test completed successfully ===");
    }

    /**
     * Read entire file content as string
     */
    private static String readFileContent(String filePath) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }

    /**
     * Parse PEM formatted certificate chain
     */
    private static X509Certificate[] parsePemChain(String pem) throws IOException, CertificateException {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            List<X509Certificate> certs = new ArrayList<>();
            Object obj;
            while ((obj = parser.readObject()) != null) {
                if (obj instanceof X509CertificateHolder) {
                    certs.add(new JcaX509CertificateConverter().getCertificate((X509CertificateHolder) obj));
                }
            }
            if (certs.isEmpty()) {
                throw new CertificateException("No certificates found in PEM content");
            }
            return certs.toArray(new X509Certificate[0]);
        }
    }

    /**
     * Verify certificate chain against provided CA certs.
     * This mirrors the logic from InteractionsFilter.
     */
    private static void verifyCertificateChain(X509Certificate[] clientChain, X509Certificate[] caCerts, boolean allowSelfSigned)
            throws CertificateException {
        
        if (clientChain == null || clientChain.length == 0) {
            throw new CertificateException("No valid client certificates provided");
        }
        if (caCerts == null || caCerts.length == 0) {
            throw new CertificateException("No CA certificates provided for validation");
        }

        try {
            CertPath certPath = CertificateFactory.getInstance("X.509").generateCertPath(Arrays.asList(clientChain));

            // Build TrustAnchor set from all CA certs in the bundle
            java.util.Set<TrustAnchor> trustAnchors = new java.util.HashSet<>();
            for (X509Certificate ca : caCerts) {
                trustAnchors.add(new TrustAnchor(ca, null));
            }

            // Support self-signed certificates if requested
            if (allowSelfSigned) {
                X509Certificate clientCert = clientChain[0];
                if (clientCert.getSubjectX500Principal().equals(clientCert.getIssuerX500Principal())) {
                    System.out.println("   Adding self-signed certificate as trust anchor");
                    trustAnchors.add(new TrustAnchor(clientCert, null));
                }
            }

            PKIXParameters params = new PKIXParameters(trustAnchors);
            params.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(certPath, params);

        } catch (CertPathValidatorException | java.security.NoSuchAlgorithmException | java.security.InvalidAlgorithmParameterException e) {
            // Build a more detailed diagnostic message
            StringBuilder details = new StringBuilder();
            details.append(e.getClass().getSimpleName());
            if (e instanceof CertPathValidatorException cpve) {
                details.append(": reason=").append(cpve.getReason());
                try {
                    int idx = cpve.getIndex();
                    details.append(", certIndex=").append(idx);
                } catch (Throwable ignored) {
                }
            }
            if (e.getMessage() != null) {
                details.append(", message=").append(e.getMessage());
            }
            throw new CertificateException("Certificate chain validation failed: " + details.toString(), e);
        }
    }
}