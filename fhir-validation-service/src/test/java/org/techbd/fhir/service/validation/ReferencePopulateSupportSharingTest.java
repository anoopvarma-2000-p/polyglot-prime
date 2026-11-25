package org.techbd.fhir.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techbd.corelib.util.AppLogger;
import org.techbd.corelib.util.TemplateLogger;

import ca.uhn.fhir.context.FhirContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;

/**
 * Verifies that {@link PrePopulateSupport} and {@link PostPopulateSupport} take their reference codes from a
 * shared {@link ReferenceCodeSets}: validators built from the same code sets reference the same concept elements,
 * while each validator keeps its own CodeSystems, ValueSets and concept lists.
 */
class ReferencePopulateSupportSharingTest {

    private static final String SNOMED = "http://snomed.info/sct";
    private static final String ICD10 = "http://hl7.org/fhir/sid/icd-10-cm";
    private static final String LOINC = "http://loinc.org";
    private static final String CPT = "http://www.ama-assn.org/go/cpt";
    private static final String HCPCS_IG1 = "urn:oid:2.16.840.1.113883.6.285";
    private static final String HCPCS_IG2 = "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";
    private static final String BCP47 = "urn:ietf:bcp:47";

    private static final String VS_OBSERVATION = "http://hl7.org/fhir/ValueSet/observation-codes";
    private static final String VS_SURVEY = "http://hl7.org/fhir/us/core/ValueSet/us-core-survey-codes";
    private static final String VS_CONDITION = "http://hl7.org/fhir/us/core/ValueSet/us-core-condition-code";
    private static final String VS_PROCEDURE = "http://hl7.org/fhir/us/core/ValueSet/us-core-procedure-code";
    private static final String VS_LANGUAGES = "http://hl7.org/fhir/ValueSet/languages";

    private static final String PROFILE_BASE_URL = "http://shinny.org/us/ny/hrsn";

    private final FhirContext fhirContext = FhirContext.forR4Cached();
    private final ReferenceCodeSets codeSets = ReferenceCodeSets.load(ReferenceCodeSetsTest.TEST_CODES_PATH);
    private Tracer tracer;
    private AppLogger appLogger;

    @BeforeEach
    void setUp() {
        tracer = mock(Tracer.class);
        final SpanBuilder spanBuilder = mock(SpanBuilder.class);
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(mock(Span.class));
        appLogger = mock(AppLogger.class);
        when(appLogger.getLogger(any())).thenReturn(mock(TemplateLogger.class));
    }

    /** Builds the reference CodeSystems the way {@code initializeFhirValidator} does, with no package stubs. */
    private PrePopulatedValidationSupport prePopulate(String igVersion) {
        final var pre = new PrePopulateSupport(tracer, appLogger, codeSets);
        final var support = new PrePopulatedValidationSupport(fhirContext);
        pre.addCodeSystems(new ValidationSupportChain(), support, igVersion);
        return support;
    }

    private static CodeSystem codeSystem(PrePopulatedValidationSupport support, String url) {
        return (CodeSystem) support.fetchCodeSystem(url);
    }

    @Test
    void preCreatesReferenceCodeSystemsFromSharedCodes() {
        final var support = prePopulate("1.9.4");

        assertThat(codeSystem(support, SNOMED).getConcept()).hasSize(3);
        assertThat(codeSystem(support, ICD10).getConcept()).hasSize(2);
        assertThat(codeSystem(support, LOINC).getConcept()).hasSize(2);
        assertThat(codeSystem(support, CPT).getConcept()).hasSize(3);
        assertThat(codeSystem(support, HCPCS_IG1).getConcept()).hasSize(2);
        assertThat(codeSystem(support, BCP47).getConcept()).hasSize(2);
        assertThat(codeSystem(support, SNOMED).getContent()).isEqualTo(CodeSystem.CodeSystemContentMode.COMPLETE);
        assertThat(codeSystem(support, HCPCS_IG2)).isNull();
    }

    @Test
    void preUsesIg2HcpcsSystemForIg2() {
        final var support = prePopulate("2.0.0");

        assertThat(codeSystem(support, HCPCS_IG2).getConcept()).hasSize(2);
        assertThat(codeSystem(support, HCPCS_IG1)).isNull();
    }

    @Test
    void twoValidatorsShareConceptElementsButNotLists() {
        final var first = prePopulate("1.9.4");
        final var second = prePopulate("2.0.0");

        for (final String url : List.of(SNOMED, ICD10, LOINC, CPT, BCP47)) {
            final List<CodeSystem.ConceptDefinitionComponent> a = codeSystem(first, url).getConcept();
            final List<CodeSystem.ConceptDefinitionComponent> b = codeSystem(second, url).getConcept();
            assertThat(a).as(url).isNotSameAs(b);
            assertThat(codeSystem(first, url)).as(url).isNotSameAs(codeSystem(second, url));
            for (int i = 0; i < a.size(); i++) {
                assertThat(a.get(i)).as(url + "[" + i + "]").isSameAs(b.get(i));
            }
        }
        assertThat(codeSystem(first, SNOMED).getConcept().get(0))
                .isSameAs(codeSets.getSnomedCodeSystemConcepts().get(0));
    }

    @Test
    void preFillsEmptyPackageStubWithoutTouchingSharedList() {
        final CodeSystem stub = new CodeSystem();
        stub.setUrl(LOINC);
        stub.setContent(CodeSystem.CodeSystemContentMode.NOTPRESENT);
        final var packageSupport = new PrePopulatedValidationSupport(fhirContext);
        packageSupport.addCodeSystem(stub);
        final var chain = new ValidationSupportChain(packageSupport);

        new PrePopulateSupport(tracer, appLogger, codeSets)
                .addCodeSystems(chain, new PrePopulatedValidationSupport(fhirContext), "1.9.4");

        assertThat(stub.getContent()).isEqualTo(CodeSystem.CodeSystemContentMode.COMPLETE);
        assertThat(stub.getConcept()).extracting(CodeSystem.ConceptDefinitionComponent::getCode)
                .containsExactly("71802-3", "96778-6");
        assertThat(stub.getConcept()).isNotSameAs(codeSets.getLoincCodeSystemConcepts());
        // The validator-owned list can grow; the shared one is unaffected.
        stub.addConcept().setCode("extra");
        assertThat(codeSets.getLoincCodeSystemConcepts()).hasSize(2);
    }

    // ----- PostPopulateSupport -----

    private ValidationSupportChain chainWithEmptyValueSets() {
        final var support = new PrePopulatedValidationSupport(fhirContext);
        for (final String url : List.of(VS_OBSERVATION, VS_SURVEY, VS_CONDITION, VS_PROCEDURE, VS_LANGUAGES)) {
            final ValueSet vs = new ValueSet();
            vs.setUrl(url);
            support.addValueSet(vs);
        }
        return new ValidationSupportChain(support);
    }

    private static ValueSet valueSet(ValidationSupportChain chain, String url) {
        return (ValueSet) chain.fetchValueSet(url);
    }

    private static ValueSet.ConceptSetComponent include(ValueSet vs, String system) {
        return vs.getCompose().getInclude().stream()
                .filter(inc -> system.equals(inc.getSystem()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No include for " + system + " in " + vs.getUrl()));
    }

    private static List<String> codes(ValueSet.ConceptSetComponent include) {
        return include.getConcept().stream().map(ValueSet.ConceptReferenceComponent::getCode).toList();
    }

    @Test
    void postAppendsSharedCodesToTheFiveValueSets() {
        final var chain = chainWithEmptyValueSets();

        new PostPopulateSupport(tracer, appLogger, codeSets).update(chain, PROFILE_BASE_URL, "1.9.4");

        final ValueSet observation = valueSet(chain, VS_OBSERVATION);
        assertThat(codes(include(observation, LOINC))).containsExactly("71802-3", "96778-6");
        assertThat(codes(include(observation, PROFILE_BASE_URL + "/CodeSystem/NYSHRSNQuestionnaire")))
                .containsExactly("NYS-AHC-HRSN", "NYSAHCHRSN");
        assertThat(codes(include(observation, PROFILE_BASE_URL + "/CodeSystem/NYS-HRSN-Questionnaire")))
                .containsExactly("NYS-AHC-HRSN", "NYSAHCHRSN");

        final ValueSet survey = valueSet(chain, VS_SURVEY);
        assertThat(survey.getCompose().getInclude()).hasSize(3);
        assertThat(codes(include(survey, LOINC))).hasSize(2);

        assertThat(codes(include(valueSet(chain, VS_CONDITION), SNOMED))).hasSize(3);

        final ValueSet procedure = valueSet(chain, VS_PROCEDURE);
        assertThat(codes(include(procedure, HCPCS_IG1))).containsExactly("G0136", "T1015");
        assertThat(codes(include(procedure, "http://www.cms.gov/Medicare/Coding/ICD10"))).hasSize(2);
        assertThat(codes(include(procedure, SNOMED))).hasSize(3);
        assertThat(codes(include(procedure, LOINC))).hasSize(2);

        assertThat(codes(include(valueSet(chain, VS_LANGUAGES), BCP47))).containsExactly("en", "es");
    }

    @Test
    void postUsesIg2HcpcsSystemForIg2() {
        final var chain = chainWithEmptyValueSets();

        new PostPopulateSupport(tracer, appLogger, codeSets).update(chain, PROFILE_BASE_URL, "2.0.0");

        final ValueSet procedure = valueSet(chain, VS_PROCEDURE);
        assertThat(codes(include(procedure, HCPCS_IG2))).hasSize(2);
        assertThat(procedure.getCompose().getInclude()).noneMatch(inc -> HCPCS_IG1.equals(inc.getSystem()));
    }

    @Test
    void postValidatorsShareConceptElementsButNotIncludeLists() {
        final var firstChain = chainWithEmptyValueSets();
        final var secondChain = chainWithEmptyValueSets();
        new PostPopulateSupport(tracer, appLogger, codeSets).update(firstChain, PROFILE_BASE_URL, "1.9.4");
        new PostPopulateSupport(tracer, appLogger, codeSets).update(secondChain, "http://test.shinny.org/us/ny/hrsn",
                "2.0.0");

        final var a = include(valueSet(firstChain, VS_CONDITION), SNOMED).getConcept();
        final var b = include(valueSet(secondChain, VS_CONDITION), SNOMED).getConcept();
        assertThat(a).isNotSameAs(b).isNotSameAs(codeSets.getSnomedValueSetConcepts());
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i)).isSameAs(b.get(i)).isSameAs(codeSets.getSnomedValueSetConcepts().get(i));
        }
        assertThat(codeSets.getSnomedValueSetConcepts()).hasSize(3);
    }

    @Test
    void postSkipsValueSetsThatAreNotInTheChain() {
        final var chain = new ValidationSupportChain(new PrePopulatedValidationSupport(fhirContext));

        new PostPopulateSupport(tracer, appLogger, codeSets).update(chain, PROFILE_BASE_URL, "1.9.4");

        assertThat(chain.fetchValueSet(VS_OBSERVATION)).isNull();
    }

    // ----- lookup terminology mode -----

    private static List<String> systems(ValueSet vs) {
        return vs.getCompose().getInclude().stream().map(ValueSet.ConceptSetComponent::getSystem).toList();
    }

    @Test
    void lookupModeAddsOnlyCustomQuestionnaireCodesAndLanguageSubtags() {
        final var chain = chainWithEmptyValueSets();

        new PostPopulateSupport(tracer, appLogger, codeSets, false).update(chain, PROFILE_BASE_URL, "1.9.4");

        assertThat(systems(valueSet(chain, VS_OBSERVATION))).containsExactly(
                PROFILE_BASE_URL + "/CodeSystem/NYSHRSNQuestionnaire",
                PROFILE_BASE_URL + "/CodeSystem/NYS-HRSN-Questionnaire");
        assertThat(systems(valueSet(chain, VS_SURVEY))).containsExactly(
                PROFILE_BASE_URL + "/CodeSystem/NYSHRSNQuestionnaire",
                PROFILE_BASE_URL + "/CodeSystem/NYS-HRSN-Questionnaire");
        assertThat(codes(include(valueSet(chain, VS_SURVEY), PROFILE_BASE_URL + "/CodeSystem/NYSHRSNQuestionnaire")))
                .containsExactly("NYS-AHC-HRSN", "NYSAHCHRSN");
        assertThat(valueSet(chain, VS_CONDITION).getCompose().getInclude()).isEmpty();
        assertThat(valueSet(chain, VS_PROCEDURE).getCompose().getInclude()).isEmpty();
        assertThat(codes(include(valueSet(chain, VS_LANGUAGES), BCP47))).containsExactly("en", "es");
    }

    @Test
    void lookupModeKeepsTheSameOrderOfIncludesAsTheFullUpdate() {
        final var full = chainWithEmptyValueSets();
        final var lookup = chainWithEmptyValueSets();
        new PostPopulateSupport(tracer, appLogger, codeSets).update(full, PROFILE_BASE_URL, "1.9.4");
        new PostPopulateSupport(tracer, appLogger, codeSets, false).update(lookup, PROFILE_BASE_URL, "1.9.4");

        // Lookup mode = full update minus the LOINC/SNOMED/ICD-10/HCPCS enumerations, in the same order.
        assertThat(systems(valueSet(lookup, VS_OBSERVATION)))
                .isEqualTo(systems(valueSet(full, VS_OBSERVATION)).stream().filter(s -> !LOINC.equals(s)).toList());
        assertThat(systems(valueSet(lookup, VS_LANGUAGES))).isEqualTo(systems(valueSet(full, VS_LANGUAGES)));
    }
}
