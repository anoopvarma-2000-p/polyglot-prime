package org.techbd.fhir.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties(prefix = "org.techbd")
@Getter
@Setter
@Primary
public class AppConfig {
    private String version;
    private String defaultDatalakeApiUrl;
    private String operationOutcomeHelpUrl;
    private Map<String, String> structureDefinitionsUrls;
    private String baseFHIRURL;
    private String fhirVersion;
    private String igVersion;
    private CsvValidation csv;
    private Map<String, FhirV4Config> igPackages;
    private String dataLedgerApiUrl;
    private String validationSeverityLevel;
    private String dataLedgerApiKeySecretName;
    private FhirSettings fhir = new FhirSettings();

    /** {@code org.techbd.fhir.*} */
    @Getter
    @Setter
    public static class FhirSettings {
        private ValidatorSettings validator = new ValidatorSettings();
    }

    /**
     * {@code org.techbd.fhir.validator.*}: bounds for validators built on demand for an IG version requested in
     * the request header (validators for the configured SHIN-NY packages are always kept).
     */
    @Getter
    @Setter
    public static class ValidatorSettings {
        /** Maximum number of dynamic (header-requested IG version) validators kept at once. 0 disables them. */
        private int maxDynamic = 2;
        /** A dynamic validator is evicted after it has not been used for this long. */
        private Duration dynamicTtl = Duration.ofMinutes(30);
    }

    @Getter
    @Setter
    public static class FhirV4Config {
        //private Map<String, Map<String, String>> shinnyPackages;
        private Map<String, ShinnyPackageConfig> shinnyPackages;
        private Map<String, String> basePackages;
    }

    @Getter
    @Setter
    public static class ShinnyPackageConfig {
        private String profileBaseUrl;
        private String packagePath;
        private String igVersion;
        private Map<String, String> basePackages;
    }

    public record CsvValidation(Validation validation) {
    public record Validation(String pythonScriptPath,String pythonExecutable,String packagePath,String outputPath,String inboundPath,String ingressHomePath) {
    }
    }
    
}