package org.techbd.fhir.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.techbd.fhir.service.validation.ReferenceTerminologyValidationSupport.BCP47_SYSTEM;
import static org.techbd.fhir.service.validation.ReferenceTerminologyValidationSupport.CMS_ICD10_SYSTEM;
import static org.techbd.fhir.service.validation.ReferenceTerminologyValidationSupport.CPT_SYSTEM;
import static org.techbd.fhir.service.validation.ReferenceTerminologyValidationSupport.HCPCS_SYSTEM_IG1;
import static org.techbd.fhir.service.validation.ReferenceTerminologyValidationSupport.HCPCS_SYSTEM_IG2;
import static org.techbd.fhir.service.validation.ReferenceTerminologyValidationSupport.ICD10CM_SYSTEM;
import static org.techbd.fhir.service.validation.ReferenceTerminologyValidationSupport.LOINC_SYSTEM;
import static org.techbd.fhir.service.validation.ReferenceTerminologyValidationSupport.SNOMED_SYSTEM;
import static org.techbd.fhir.service.validation.ReferenceTerminologyValidationSupport.VS_CONDITION;
import static org.techbd.fhir.service.validation.ReferenceTerminologyValidationSupport.VS_OBSERVATION_CODES;
import static org.techbd.fhir.service.validation.ReferenceTerminologyValidationSupport.VS_PROCEDURE;
import static org.techbd.fhir.service.validation.ReferenceTerminologyValidationSupport.VS_SURVEY;

import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.IValidationSupport.CodeValidationIssueCode;
import ca.uhn.fhir.context.support.IValidationSupport.CodeValidationIssueCoding;
import ca.uhn.fhir.context.support.IValidationSupport.CodeValidationResult;
import ca.uhn.fhir.context.support.IValidationSupport.IssueSeverity;
import ca.uhn.fhir.context.support.LookupCodeRequest;
import ca.uhn.fhir.context.support.ValidationSupportContext;

/**
 * Unit tests for {@link ReferenceTerminologyValidationSupport} with the small fixture code sets. The expected result
 * shapes (messages, issues, source details, LOINC version) are the ones HAPI's in-memory terminology server
 * returned for the enumerated terminology; {@code TerminologyParityTest} checks them against the real chain.
 */
class ReferenceTerminologyValidationSupportTest {

    private static final FhirContext CTX = FhirContext.forR4Cached();
    private static final ReferenceCodeSets CODES = ReferenceCodeSets.load(ReferenceCodeSetsTest.TEST_CODES_PATH);
    private static final ConceptValidationOptions OPTIONS = new ConceptValidationOptions();

    private final ReferenceTerminologyValidationSupport ig1 = new ReferenceTerminologyValidationSupport(CTX, CODES, "1.9.4");
    private final ReferenceTerminologyValidationSupport ig2 = new ReferenceTerminologyValidationSupport(CTX, CODES, "2.0.0");
    private final ValidationSupportContext context = new ValidationSupportContext(ig1);

    private static ValueSet valueSet(String url) {
        final ValueSet vs = new ValueSet();
        vs.setUrl(url);
        return vs;
    }

    // ----- code systems -----

    @Test
    void supportsTheReferenceCodeSystemsOnly() {
        for (final String system : new String[] {SNOMED_SYSTEM, ICD10CM_SYSTEM, LOINC_SYSTEM, CPT_SYSTEM,
                HCPCS_SYSTEM_IG1, BCP47_SYSTEM}) {
            assertThat(ig1.isCodeSystemSupported(context, system)).as(system).isTrue();
        }
        assertThat(ig1.isCodeSystemSupported(context, HCPCS_SYSTEM_IG2)).isFalse();
        assertThat(ig1.isCodeSystemSupported(context, CMS_ICD10_SYSTEM)).isFalse();
        assertThat(ig1.isCodeSystemSupported(context, "http://hl7.org/fhir/sid/icd-9-cm")).isFalse();
        assertThat(ig1.isCodeSystemSupported(context, "http://unitsofmeasure.org")).isFalse();
        assertThat(ig1.isCodeSystemSupported(context, null)).isFalse();
    }

    @Test
    void hcpcsSystemDependsOnIgVersion() {
        assertThat(ig1.getHcpcsSystem()).isEqualTo(HCPCS_SYSTEM_IG1);
        assertThat(ig2.getHcpcsSystem()).isEqualTo(HCPCS_SYSTEM_IG2);
        assertThat(ig2.isCodeSystemSupported(context, HCPCS_SYSTEM_IG2)).isTrue();
        assertThat(ig2.isCodeSystemSupported(context, HCPCS_SYSTEM_IG1)).isFalse();
        assertThat(ig2.validateCode(context, OPTIONS, HCPCS_SYSTEM_IG2, "G0136", null, null).isOk()).isTrue();
        assertThat(ig2.validateCode(context, OPTIONS, HCPCS_SYSTEM_IG1, "G0136", null, null)).isNull();
    }

    @Test
    void doesNotProvideCodeSystemResources() {
        // PrePopulateSupport still provides the CodeSystems (other ValueSets are expanded against them).
        assertThat(ig1.fetchCodeSystem(SNOMED_SYSTEM)).isNull();
        assertThat(ig1.fetchValueSet(VS_CONDITION)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "http://snomed.info/sct, 73211009",
            "http://hl7.org/fhir/sid/icd-10-cm, E11.9",
            "http://www.ama-assn.org/go/cpt, 0001A",
            "urn:oid:2.16.840.1.113883.6.285, T1015",
            "urn:ietf:bcp:47, es" })
    void knownCodeIsValidWithoutDisplay(String system, String code) {
        final CodeValidationResult result = ig1.validateCode(context, OPTIONS, system, code, "Some Display", null);

        assertThat(result.isOk()).isTrue();
        assertThat(result.getCode()).isEqualTo(code);
        assertThat(result.getDisplay()).isNull();
        assertThat(result.getSeverity()).isNull();
        assertThat(result.getCodeSystemVersion()).isNull();
        assertThat(result.getSourceDetails()).isNull();
        assertThat(result.getIssues()).isEmpty();
    }

    @Test
    void knownLoincCodeCarriesLoincVersionAndName() {
        final CodeValidationResult result = ig1.validateCode(context, OPTIONS, LOINC_SYSTEM, "71802-3", null, null);

        assertThat(result.isOk()).isTrue();
        assertThat(result.getCodeSystemVersion()).isEqualTo("2.81");
        assertThat(result.getCodeSystemName()).isEqualTo("LOINC");
    }

    @Test
    void unknownCodeMatchesInMemoryServerError() {
        final CodeValidationResult result =
                ig1.validateCode(context, OPTIONS, SNOMED_SYSTEM, "ZZZ-INVALID", null, null);

        final String message = "Unknown code 'http://snomed.info/sct#ZZZ-INVALID'";
        assertThat(result.isOk()).isFalse();
        assertThat(result.getSeverity()).isEqualTo(IssueSeverity.ERROR);
        assertThat(result.getMessage()).isEqualTo(message);
        assertThat(result.getCode()).isNull();
        assertThat(result.getSourceDetails()).isEqualTo("In-memory expansion containing 0 codes");
        assertThat(result.getIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.getSeverity()).isEqualTo(IssueSeverity.ERROR);
            assertThat(issue.getType()).isEqualTo(CodeValidationIssueCode.CODE_INVALID);
            assertThat(issue.getCoding()).isEqualTo(CodeValidationIssueCoding.INVALID_CODE);
            assertThat(issue.getDiagnostics()).isEqualTo(message);
        });
    }

    @Test
    void headerLineOfPsvIsACodeLikeBefore() {
        // cpt.psv starts with a "CODE" header line, which the enumerated terminology also loaded as a code.
        assertThat(ig1.validateCode(context, OPTIONS, CPT_SYSTEM, "CODE", null, null).isOk()).isTrue();
    }

    @Test
    void otherSystemsAreLeftToTheRestOfTheChain() {
        assertThat(ig1.validateCode(context, OPTIONS, "http://unitsofmeasure.org", "mg", null, null)).isNull();
        assertThat(ig1.validateCode(context, OPTIONS, CMS_ICD10_SYSTEM, "E11.9", null, null)).isNull();
        assertThat(ig1.validateCode(context, OPTIONS, null, "E11.9", null, null)).isNull();
        assertThat(ig1.validateCode(context, OPTIONS, SNOMED_SYSTEM, null, null, null)).isNull();
    }

    // ----- ValueSets -----

    @Test
    void supportsTheFourEnumeratedValueSetsWithOrWithoutVersion() {
        for (final String url : new String[] {VS_OBSERVATION_CODES, VS_SURVEY, VS_CONDITION, VS_PROCEDURE}) {
            assertThat(ig1.isValueSetSupported(context, url)).as(url).isTrue();
        }
        assertThat(ig1.isValueSetSupported(context, VS_CONDITION + "|7.0.0")).isTrue();
        assertThat(ig1.isValueSetSupported(context, VS_OBSERVATION_CODES + "|4.0.1")).isTrue();
        // languages is answered by HAPI's common code systems service and the in-memory server.
        assertThat(ig1.isValueSetSupported(context, "http://hl7.org/fhir/ValueSet/languages")).isFalse();
        assertThat(ig1.isValueSetSupported(context, "http://hl7.org/fhir/ValueSet/consent-category")).isFalse();
        assertThat(ig1.isValueSetSupported(context, null)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            // ValueSet, system, code
            "http://hl7.org/fhir/ValueSet/observation-codes, http://loinc.org, 71802-3",
            "http://hl7.org/fhir/us/core/ValueSet/us-core-survey-codes, http://loinc.org, 96778-6",
            "http://hl7.org/fhir/us/core/ValueSet/us-core-condition-code, http://snomed.info/sct, 73211009",
            "http://hl7.org/fhir/us/core/ValueSet/us-core-condition-code, http://hl7.org/fhir/sid/icd-10-cm, I10",
            "http://hl7.org/fhir/us/core/ValueSet/us-core-procedure-code, http://snomed.info/sct, 38341003",
            "http://hl7.org/fhir/us/core/ValueSet/us-core-procedure-code, http://loinc.org, 71802-3",
            "http://hl7.org/fhir/us/core/ValueSet/us-core-procedure-code, http://www.ama-assn.org/go/cpt, 0001F",
            "http://hl7.org/fhir/us/core/ValueSet/us-core-procedure-code, urn:oid:2.16.840.1.113883.6.285, G0136",
            "http://hl7.org/fhir/us/core/ValueSet/us-core-procedure-code, http://www.cms.gov/Medicare/Coding/ICD10, E11.9" })
    void memberCodeIsValidAgainstInMemoryExpansion(String valueSetUrl, String system, String code) {
        final CodeValidationResult result =
                ig1.validateCodeInValueSet(context, OPTIONS, system, code, null, valueSet(valueSetUrl + "|1.0"));

        assertThat(result.isOk()).isTrue();
        assertThat(result.getCode()).isEqualTo(code);
        assertThat(result.getDisplay()).isNull();
        assertThat(result.getSourceDetails())
                .isEqualTo("Code was validated against in-memory expansion of ValueSet: " + valueSetUrl);
        assertThat(result.getCodeSystemVersion()).isEqualTo(LOINC_SYSTEM.equals(system) ? "2.81" : null);
    }

    @Test
    void nonMemberCodeMatchesInMemoryServerError() {
        final CodeValidationResult result = ig1.validateCodeInValueSet(context, OPTIONS, SNOMED_SYSTEM,
                "ZZZ-INVALID", null, valueSet(VS_CONDITION));

        final String message = "Unknown code 'http://snomed.info/sct#ZZZ-INVALID' for in-memory expansion of "
                + "ValueSet 'http://hl7.org/fhir/us/core/ValueSet/us-core-condition-code'";
        assertThat(result.isOk()).isFalse();
        assertThat(result.getSeverity()).isEqualTo(IssueSeverity.ERROR);
        assertThat(result.getMessage()).isEqualTo(message);
        assertThat(result.getSourceDetails()).isNull();
        assertThat(result.getIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.getType()).isEqualTo(CodeValidationIssueCode.NOT_FOUND);
            assertThat(issue.getCoding()).isEqualTo(CodeValidationIssueCoding.NOT_IN_VS);
            assertThat(issue.getDiagnostics()).isEqualTo(message);
        });
    }

    @Test
    void validateCodeWithValueSetUrlUsesTheSameMembership() {
        assertThat(ig1.validateCode(context, OPTIONS, LOINC_SYSTEM, "71802-3", null, VS_SURVEY + "|7.0.0").isOk())
                .isTrue();
        assertThat(ig1.validateCode(context, OPTIONS, LOINC_SYSTEM, "ZZZ", null, VS_SURVEY).getMessage())
                .isEqualTo("Unknown code 'http://loinc.org#ZZZ' for in-memory expansion of ValueSet '" + VS_SURVEY + "'");
    }

    @Test
    void systemsNotEnumeratedIntoTheValueSetAreLeftToTheChain() {
        // The in-memory server answers these from the ValueSet's own includes, exactly as before.
        assertThat(ig1.validateCodeInValueSet(context, OPTIONS, LOINC_SYSTEM, "71802-3", null, valueSet(VS_CONDITION)))
                .isNull();
        assertThat(ig1.validateCodeInValueSet(context, OPTIONS, "http://hl7.org/fhir/sid/icd-9-cm", "250.00", null,
                valueSet(VS_CONDITION))).isNull();
        assertThat(ig1.validateCodeInValueSet(context, OPTIONS, "http://unitsofmeasure.org", "mg", null,
                valueSet(VS_OBSERVATION_CODES))).isNull();
        assertThat(ig1.validateCodeInValueSet(context, OPTIONS,
                "http://shinny.org/us/ny/hrsn/CodeSystem/NYSHRSNQuestionnaire", "NYSAHCHRSN", null,
                valueSet(VS_SURVEY))).isNull();
        assertThat(ig1.validateCodeInValueSet(context, OPTIONS, HCPCS_SYSTEM_IG2, "G0136", null,
                valueSet(VS_PROCEDURE))).isNull();
    }

    @Test
    void unknownCmsIcd10CodeIsLeftToTheChain() {
        // No CodeSystem exists for the CMS ICD-10 URL; the in-memory server reports the include as not expandable.
        assertThat(ig1.validateCodeInValueSet(context, OPTIONS, CMS_ICD10_SYSTEM, "ZZZ", null, valueSet(VS_PROCEDURE)))
                .isNull();
    }

    @Test
    void otherValueSetsAreLeftToTheChain() {
        assertThat(ig1.validateCodeInValueSet(context, OPTIONS, LOINC_SYSTEM, "71802-3", null,
                valueSet("http://hl7.org/fhir/ValueSet/consent-category"))).isNull();
        assertThat(ig1.validateCodeInValueSet(context, OPTIONS, LOINC_SYSTEM, "71802-3", null, new ValueSet()))
                .isNull();
        assertThat(ig1.validateCodeInValueSet(context, OPTIONS, LOINC_SYSTEM, "71802-3", null, null)).isNull();
    }

    // ----- lookup -----

    @Test
    void lookupCodeReportsMembership() {
        final var found = ig1.lookupCode(context, new LookupCodeRequest(LOINC_SYSTEM, "71802-3"));
        assertThat(found.isFound()).isTrue();
        assertThat(found.getSearchedForCode()).isEqualTo("71802-3");
        assertThat(found.getSearchedForSystem()).isEqualTo(LOINC_SYSTEM);
        assertThat(found.getCodeSystemVersion()).isEqualTo("2.81");
        assertThat(found.getCodeDisplay()).isNull();

        assertThat(ig1.lookupCode(context, new LookupCodeRequest(SNOMED_SYSTEM, "ZZZ")).isFound()).isFalse();
        assertThat(ig1.lookupCode(context, new LookupCodeRequest("http://unitsofmeasure.org", "mg"))).isNull();
    }
}
