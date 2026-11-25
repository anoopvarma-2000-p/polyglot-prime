package org.techbd.fhir.service.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.techbd.corelib.util.AppLogger;
import org.techbd.corelib.util.TemplateLogger;
import org.techbd.fhir.util.FHIRUtil;

import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.LookupCodeRequest;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;

/**
 * Regression guard for lookup terminology (R4): builds the real validation support chain (production IG packages,
 * production reference codes) once with enumerated terminology and once with lookup terminology, asks both the same
 * terminology questions, and requires identical answers: OK/error, severity, message, code, display, code system
 * version and name, source details and every issue.
 *
 * <p>
 * The questions cover each reference code system and the systems around it (CMS ICD-10, ICD-9, CDT, both HCPCS
 * URLs, UCUM, the custom questionnaire systems), valid and invalid codes, with and without a ValueSet, against the
 * five ValueSets the enumerated terminology extends.
 */
class TerminologyParityTest {

    private static final Map<String, String> BASE_PACKAGES = new TreeMap<>(Map.of(
            "us-core", "ig-packages/fhir-v4/us-core/stu-7.0.0",
            "sdoh", "ig-packages/fhir-v4/sdoh-clinicalcare/stu-2.3.0",
            "uv-sdc", "ig-packages/fhir-v4/uv-sdc/stu-3.0.0",
            "extensions", "ig-packages/fhir-v4/uv-extensions/5.2.0"));

    private static final List<String> VALUE_SETS = List.of(
            "http://hl7.org/fhir/ValueSet/languages",
            "http://hl7.org/fhir/ValueSet/observation-codes",
            "http://hl7.org/fhir/us/core/ValueSet/us-core-condition-code",
            "http://hl7.org/fhir/us/core/ValueSet/us-core-procedure-code",
            "http://hl7.org/fhir/us/core/ValueSet/us-core-survey-codes");

    private static OrchestrationEngine.HapiValidationEngine engine(boolean lookupTerminology) {
        final Tracer tracer = mock(Tracer.class);
        final SpanBuilder spanBuilder = mock(SpanBuilder.class);
        when(tracer.spanBuilder(any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(mock(Span.class));
        final AppLogger appLogger = mock(AppLogger.class);
        final TemplateLogger templateLogger = mock(TemplateLogger.class);
        when(appLogger.getLogger(any())).thenReturn(templateLogger);
        return new OrchestrationEngine.HapiValidationEngine.Builder()
                .withTracer(tracer)
                .withAppLogger(appLogger)
                .withTemplateLogger(templateLogger)
                .withLookupTerminology(lookupTerminology)
                .build();
    }

    private static String[][] probes(String igVersion, String profileBaseUrl) {
        final boolean ig2 = FHIRUtil.isIg2OrLater(igVersion);
        final String hcpcs = ig2 ? "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets" : "urn:oid:2.16.840.1.113883.6.285";
        final String otherHcpcs = ig2 ? "urn:oid:2.16.840.1.113883.6.285" : "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";
        return new String[][] {
                {"http://snomed.info/sct", "73211009"}, {"http://snomed.info/sct", "160245001"},
                {"http://snomed.info/sct", "71388002"}, {"http://hl7.org/fhir/sid/icd-10-cm", "E11.9"},
                {"http://loinc.org", "71802-3"}, {"http://loinc.org", "CODE"},
                {"http://www.ama-assn.org/go/cpt", "0001A"}, {"http://www.ama-assn.org/go/cpt", "CODE"},
                {hcpcs, "G0136"}, {otherHcpcs, "G0136"},
                {"https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", "G0136"},
                {"http://www.cms.gov/Medicare/Coding/ICD10", "E11.9"},
                {"urn:ietf:bcp:47", "en"}, {"urn:ietf:bcp:47", "en-US"}, {"urn:ietf:bcp:47", "aa"},
                {"urn:ietf:bcp:47", "zh-Hant"},
                {profileBaseUrl + "/CodeSystem/NYSHRSNQuestionnaire", "NYS-AHC-HRSN"},
                {profileBaseUrl + "/CodeSystem/NYS-HRSN-Questionnaire", "NYSAHCHRSN"},
                {"http://unitsofmeasure.org", "mg"}, {"http://hl7.org/fhir/sid/icd-9-cm", "250.00"},
                {"http://www.ada.org/cdt", "D0120"}};
    }

    /** Asks the chain every question and returns one formatted answer per line. */
    private static List<String> answers(ValidationSupportChain chain, String igVersion, String profileBaseUrl) {
        final ValidationSupportContext ctx = new ValidationSupportContext(chain);
        final ConceptValidationOptions options = new ConceptValidationOptions();
        final List<String> out = new ArrayList<>();
        for (final String[] probe : probes(igVersion, profileBaseUrl)) {
            final String system = probe[0];
            out.add("isCodeSystemSupported " + system + " = " + chain.isCodeSystemSupported(ctx, system));
            for (final String code : new String[] {probe[1], "ZZZ-INVALID"}) {
                for (final String display : new String[] {null, "Some Display"}) {
                    out.add("validateCode " + system + "#" + code + " display=" + display + " -> "
                            + format(chain.validateCode(ctx, options, system, code, display, null)));
                }
                for (final String valueSet : VALUE_SETS) {
                    out.add("validateCodeInValueSet " + valueSet + " " + system + "#" + code + " -> "
                            + format(chain.validateCodeInValueSet(ctx, options, system, code, null,
                                    chain.fetchValueSet(valueSet))));
                }
                out.add("lookupCode " + system + "#" + code + " -> "
                        + format(chain.lookupCode(ctx, new LookupCodeRequest(system, code))));
            }
        }
        for (final String valueSet : VALUE_SETS) {
            out.add("isValueSetSupported " + valueSet + " = " + chain.isValueSetSupported(ctx, valueSet));
        }
        return out;
    }

    private static String format(IValidationSupport.CodeValidationResult r) {
        if (r == null) {
            return "null";
        }
        final StringBuilder b = new StringBuilder()
                .append("ok=").append(r.isOk())
                .append(" severity=").append(r.getSeverity())
                .append(" code=").append(r.getCode())
                .append(" display=").append(r.getDisplay())
                .append(" csVersion=").append(r.getCodeSystemVersion())
                .append(" csName=").append(r.getCodeSystemName())
                .append(" source=").append(r.getSourceDetails())
                .append(" message=").append(r.getMessage());
        for (final IValidationSupport.CodeValidationIssue issue : r.getIssues()) {
            b.append(" | issue severity=").append(issue.getSeverity())
                    .append(" type=").append(issue.getType() == null ? null : issue.getType().getCode())
                    .append(" diagnostics=").append(issue.getDiagnostics());
            if (issue.getDetails() != null) {
                b.append(" details=").append(issue.getDetails().getText());
                issue.getDetails().getCodings().forEach(c -> b.append(" ").append(c.getSystem()).append('#')
                        .append(c.getCode()));
            }
        }
        return b.toString();
    }

    private static String format(IValidationSupport.LookupCodeResult r) {
        return r == null ? "null"
                : "found=" + r.isFound() + " display=" + r.getCodeDisplay() + " csVersion="
                        + r.getCodeSystemVersion() + " csName=" + r.getCodeSystemDisplayName() + " error="
                        + r.getErrorMessage();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.9.4", "2.0.0"})
    void lookupTerminologyAnswersExactlyLikeEnumeratedTerminology(String igVersion) {
        final boolean ig2 = FHIRUtil.isIg2OrLater(igVersion);
        final String packagePath = ig2 ? "ig-packages/shin-ny-ig/test-shinny/v2.0.0" : "ig-packages/shin-ny-ig/shinny/v1.9.4";
        final String profileBaseUrl = ig2 ? "http://test.shinny.org/us/ny/hrsn" : "http://shinny.org/us/ny/hrsn";

        // One chain at a time: the enumerated chain retains several hundred MB once its ValueSets are expanded.
        final List<String> enumerated = answers(
                engine(false).buildValidationSupportChain(packagePath, BASE_PACKAGES, profileBaseUrl, igVersion),
                igVersion, profileBaseUrl);
        final List<String> lookup = answers(
                engine(true).buildValidationSupportChain(packagePath, BASE_PACKAGES, profileBaseUrl, igVersion),
                igVersion, profileBaseUrl);

        assertThat(lookup).hasSize(enumerated.size());
        final List<String> differences = new ArrayList<>();
        for (int i = 0; i < enumerated.size(); i++) {
            if (!enumerated.get(i).equals(lookup.get(i))) {
                differences.add("enumerated: " + enumerated.get(i) + "\n    lookup: " + lookup.get(i));
            }
        }
        assertThat(differences).as("answers that differ between enumerated and lookup terminology").isEmpty();
    }
}
