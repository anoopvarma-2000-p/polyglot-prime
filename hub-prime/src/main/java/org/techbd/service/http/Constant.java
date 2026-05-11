package org.techbd.service.http;

import java.util.Arrays;
import java.util.List;

public class Constant {

    /**
     * Stateless URLs
     */
    public static final String[] STATELESS_API_URLS = {
        "/Bundle", "/Bundle/**",
        "/flatfile/csv/Bundle", "/flatfile/csv/Bundle/**",
        "/Hl7/v2", "/Hl7/v2/",
        "/api/expect", "/api/expect/**",
        "/Bundles/**"
    };

    /**
     * Public URLs (Authentication not required)
     */
    public static final String[] UNAUTHENTICATED_URLS = {
        "/login/**", "/oauth2/**",
        "/",
        "/metadata",
        "/docs/api/interactive/swagger-ui/**", "/support/**",
        "/docs/api/interactive/**",
        "/docs/api/openapi/**",
        "/error", "/error/**"
    };

    public static final String HOME_PAGE_URL = "/home";
    public static final String LOGIN_PAGE_URL = "/login";
    public static final String SESSIONID_COOKIE = "JSESSIONID";
    public static final String LOGOUT_PAGE_URL = "/";
    public static final String SESSION_TIMEOUT_URL = "/?timeout=true";

    public static final long HSTS_MAX_AGE = 31536000; // HSTS max age for 1 year

    public static final List<String> CORS_ALLOWED_METHODS = List.of(
            "GET", "POST", "OPTIONS");

    // Newly added custom headers must be included here in this list also.
    public static final List<String> CORS_ALLOWED_HEADERS = List.of(
            "Authorization", "Content-Type", "Accept", "Origin",
            "X-Requested-With", "X-TechBD-FHIR-Validation-Strategy",
            "X-TechBD-Tenant-ID",
            "X-SHIN-NY-IG-Version",
            "X-TechBD-Source-Type",
            "X-TechBD-Master-Interaction-ID",
            "X-TechBD-Elaboration",
            "X-TechBD-Group-Interaction-ID",
            "X-TechBD-Interaction-ID",
            "X-TechBD-Override-Request-URI",
            "X-TechBD-Data-Ledger-Tracking",
            "X-TechBD-Data-Ledger-diagnostics",
            "X-TechBD-DataLake-API-URL",
            "X-TechBD-Base-FHIR-URL",
            "X-TechBD-BL-BaseURL",
            "X-TechBD-Validation-Severity-Level",
            "X-TechBD-Request-URI",
            "X-Correlation-ID",
            "DataLake-API-Content-Type",
            "X-TechBD-HealthCheck",
            "X-TechBD-CIN",
            "X-TechBD-OrgNPI",
            "X-TechBD-OrgTIN",
            "X-TechBD-Facility-ID",
            "X-TechBD-Encounter-Type",
            "X-TechBD-Screening-Code",
            "X-TechBD-Part2",
            "X-TechBD-OMH",
            "X-TechBD-OPWDD",
            "X-TechBD-Organization-Name");

    public static final List<String> CORS_EXPOSED_HEADERS = List.of("Location");

    public static final boolean isStatelessApiUrl(String requestUrl) {
        if (requestUrl == null) {
            return false;
        }
        return Arrays.stream(STATELESS_API_URLS)
                .anyMatch(requestUrl::contains);
    }
}
