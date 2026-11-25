package org.techbd.fhir.service.validation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.ValueSet;
import org.techbd.fhir.util.FHIRUtil;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.LookupCodeRequest;
import ca.uhn.fhir.context.support.ValidationSupportContext;

/**
 * Lookup-based terminology for the large reference code systems (SNOMED CT, ICD-10-CM, LOINC, CPT, HCPCS, BCP-47)
 * and the four ValueSets that {@link PostPopulateSupport} extends with them ({@code observation-codes},
 * {@code us-core-survey-codes}, {@code us-core-condition-code}, {@code us-core-procedure-code}).
 *
 * <p>
 * {@link PostPopulateSupport} appends about one million codes to the includes of those four ValueSets. On first
 * use, HAPI's in-memory terminology server expands them and converts them to R5, which is most of the memory a
 * validator retains. With lookup terminology enabled those enumerations are not added, and this support answers
 * the same questions with {@code Set.contains} on the shared {@link ReferenceCodeSets} code sets. It also answers
 * plain code lookups (no ValueSet) for these systems.
 *
 * <p>
 * The reference CodeSystems built by {@link PrePopulateSupport} are kept: other package and IG ValueSets (for
 * example {@code consent-category}, the SHIN-NY language and pronoun ValueSets, or SNOMED {@code is-a} filters)
 * are still evaluated against them by the in-memory terminology server. Since R3 their concept lists are shared by
 * all validators.
 *
 * <p>
 * It is placed in the chain <b>directly before</b> {@code InMemoryTerminologyServerValidationSupport} and takes
 * over that support's role for these systems only. Supports earlier in the chain keep answering what they answered
 * before; in particular {@code CommonCodeSystemsTerminologyService} still answers BCP-47 codes it knows (with
 * displays) and the {@code languages} ValueSet. The results reproduce what the in-memory server returned for the
 * enumerated data: the same OK results (no display; LOINC version {@code 2.81}) and the same error messages and
 * issues. Anything else returns {@code null}, so the rest of the chain handles it as before.
 *
 * <p>
 * One instance per validator: the HCPCS system URL depends on the IG version (see {@link FHIRUtil#isIg2OrLater}).
 * The code sets are shared and read-only.
 */
public class ReferenceTerminologyValidationSupport implements IValidationSupport {

    public static final String SNOMED_SYSTEM = "http://snomed.info/sct";
    public static final String ICD10CM_SYSTEM = "http://hl7.org/fhir/sid/icd-10-cm";
    public static final String LOINC_SYSTEM = "http://loinc.org";
    public static final String CPT_SYSTEM = "http://www.ama-assn.org/go/cpt";
    public static final String HCPCS_SYSTEM_IG1 = "urn:oid:2.16.840.1.113883.6.285";
    public static final String HCPCS_SYSTEM_IG2 = "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";
    public static final String BCP47_SYSTEM = "urn:ietf:bcp:47";
    /** ICD-10 codes under the CMS URL, used only inside {@link #VS_PROCEDURE}. No CodeSystem exists for it. */
    public static final String CMS_ICD10_SYSTEM = "http://www.cms.gov/Medicare/Coding/ICD10";

    public static final String VS_OBSERVATION_CODES = "http://hl7.org/fhir/ValueSet/observation-codes";
    public static final String VS_SURVEY = "http://hl7.org/fhir/us/core/ValueSet/us-core-survey-codes";
    public static final String VS_CONDITION = "http://hl7.org/fhir/us/core/ValueSet/us-core-condition-code";
    public static final String VS_PROCEDURE = "http://hl7.org/fhir/us/core/ValueSet/us-core-procedure-code";

    static final String LOINC_VERSION = "2.81";
    static final String LOINC_NAME = "LOINC";

    private final FhirContext fhirContext;
    private final String hcpcsSystem;

    /** Code systems answered by {@link #validateCode} without a ValueSet, keyed by system URL. */
    private final Map<String, Set<String>> codeSystems;
    /** ValueSet URL -> (system URL -> codes): the systems the enumerated terminology injected into each ValueSet. */
    private final Map<String, Map<String, Set<String>>> valueSetMembers;

    public ReferenceTerminologyValidationSupport(final FhirContext fhirContext,
            final ReferenceCodeSets referenceCodeSets, final String igVersion) {
        this.fhirContext = Objects.requireNonNull(fhirContext, "fhirContext");
        Objects.requireNonNull(referenceCodeSets, "referenceCodeSets");
        this.hcpcsSystem = FHIRUtil.isIg2OrLater(igVersion) ? HCPCS_SYSTEM_IG2 : HCPCS_SYSTEM_IG1;

        final Set<String> snomed = referenceCodeSets.getSnomedCodes();
        final Set<String> icd10 = referenceCodeSets.getIcd10Codes();
        final Set<String> loinc = referenceCodeSets.getLoincCodes();
        final Set<String> cpt = referenceCodeSets.getCptCodes();
        final Set<String> hcpcs = referenceCodeSets.getHcpcsCodes();
        final Set<String> language = referenceCodeSets.getLanguageCodes();

        final Map<String, Set<String>> systems = new LinkedHashMap<>();
        systems.put(SNOMED_SYSTEM, snomed);
        systems.put(ICD10CM_SYSTEM, icd10);
        systems.put(LOINC_SYSTEM, loinc);
        systems.put(CPT_SYSTEM, cpt);
        systems.put(hcpcsSystem, hcpcs);
        systems.put(BCP47_SYSTEM, language);
        this.codeSystems = Collections.unmodifiableMap(systems);

        // Mirrors the enumerations PostPopulateSupport adds to these ValueSets, plus the whole-system includes of the
        // package ValueSets that PrePopulateSupport's CodeSystems resolve (CPT and ICD-10-CM).
        final Map<String, Map<String, Set<String>>> members = new LinkedHashMap<>();
        members.put(VS_OBSERVATION_CODES, Map.of(LOINC_SYSTEM, loinc));
        members.put(VS_SURVEY, Map.of(LOINC_SYSTEM, loinc));
        members.put(VS_CONDITION, Map.of(SNOMED_SYSTEM, snomed, ICD10CM_SYSTEM, icd10));
        members.put(VS_PROCEDURE, Map.of(SNOMED_SYSTEM, snomed, LOINC_SYSTEM, loinc, CPT_SYSTEM, cpt,
                hcpcsSystem, hcpcs, CMS_ICD10_SYSTEM, icd10));
        this.valueSetMembers = Collections.unmodifiableMap(members);
    }

    @Override
    public FhirContext getFhirContext() {
        return fhirContext;
    }

    @Override
    public String getName() {
        return "ReferenceTerminologyValidationSupport";
    }

    /** The HCPCS system URL this instance answers for (depends on the IG version). */
    public String getHcpcsSystem() {
        return hcpcsSystem;
    }

    // ----- Code systems -----

    @Override
    public boolean isCodeSystemSupported(final ValidationSupportContext context, final String system) {
        return system != null && codeSystems.containsKey(system);
    }

    // ----- ValueSets -----

    @Override
    public boolean isValueSetSupported(final ValidationSupportContext context, final String valueSetUrl) {
        return valueSetUrl != null && valueSetMembers.containsKey(stripVersion(valueSetUrl));
    }

    // ----- Code validation -----

    @Override
    public CodeValidationResult validateCode(final ValidationSupportContext context,
            final ConceptValidationOptions options, final String system, final String code, final String display,
            final String valueSetUrl) {
        if (valueSetUrl != null) {
            return validateInValueSet(stripVersion(valueSetUrl), system, code);
        }
        if (system == null || code == null) {
            return null;
        }
        final Set<String> codes = codeSystems.get(system);
        if (codes == null) {
            return null;
        }
        if (codes.contains(code)) {
            return okResult(system, code, null);
        }
        final String message = "Unknown code '" + system + "#" + code + "'";
        return new CodeValidationResult()
                .setSeverity(IssueSeverity.ERROR)
                .setMessage(message)
                .setSourceDetails("In-memory expansion containing 0 codes")
                .addIssue(new CodeValidationIssue(message, IssueSeverity.ERROR,
                        CodeValidationIssueCode.CODE_INVALID, CodeValidationIssueCoding.INVALID_CODE));
    }

    @Override
    public CodeValidationResult validateCodeInValueSet(final ValidationSupportContext context,
            final ConceptValidationOptions options, final String system, final String code, final String display,
            final IBaseResource valueSet) {
        if (!(valueSet instanceof ValueSet vs) || !vs.hasUrl()) {
            return null;
        }
        return validateInValueSet(stripVersion(vs.getUrl()), system, code);
    }

    private CodeValidationResult validateInValueSet(final String valueSetUrl, final String system,
            final String code) {
        final Map<String, Set<String>> members = valueSetMembers.get(valueSetUrl);
        if (members == null || system == null || code == null) {
            return null;
        }
        final Set<String> codes = members.get(system);
        if (codes == null) {
            // Not a system we own for this ValueSet: the in-memory server answers from the ValueSet's own includes.
            return null;
        }
        if (codes.contains(code)) {
            return okResult(system, code, "Code was validated against in-memory expansion of ValueSet: " + valueSetUrl);
        }
        if (!codeSystems.containsKey(system)) {
            // No CodeSystem exists for this system (CMS ICD-10). The in-memory server reports the ValueSet's own
            // include as not expandable, as it did before.
            return null;
        }
        final String message = "Unknown code '" + system + "#" + code + "' for in-memory expansion of ValueSet '"
                + valueSetUrl + "'";
        return new CodeValidationResult()
                .setSeverity(IssueSeverity.ERROR)
                .setMessage(message)
                .addIssue(new CodeValidationIssue(message, IssueSeverity.ERROR,
                        CodeValidationIssueCode.NOT_FOUND, CodeValidationIssueCoding.NOT_IN_VS));
    }

    private static CodeValidationResult okResult(final String system, final String code, final String sourceDetails) {
        final CodeValidationResult result = new CodeValidationResult().setCode(code);
        if (sourceDetails != null) {
            result.setSourceDetails(sourceDetails);
        }
        if (LOINC_SYSTEM.equals(system)) {
            result.setCodeSystemVersion(LOINC_VERSION).setCodeSystemName(LOINC_NAME);
        }
        return result;
    }

    // ----- Lookup -----

    @Override
    public LookupCodeResult lookupCode(final ValidationSupportContext context, final LookupCodeRequest request) {
        if (request == null || request.getSystem() == null || request.getCode() == null) {
            return null;
        }
        final Set<String> codes = codeSystems.get(request.getSystem());
        if (codes == null) {
            return null;
        }
        final LookupCodeResult result = new LookupCodeResult()
                .setSearchedForSystem(request.getSystem())
                .setSearchedForCode(request.getCode())
                .setFound(codes.contains(request.getCode()));
        if (result.isFound() && LOINC_SYSTEM.equals(request.getSystem())) {
            result.setCodeSystemVersion(LOINC_VERSION);
            result.setCodeSystemDisplayName(LOINC_NAME);
        }
        return result;
    }

    private static String stripVersion(final String url) {
        final int pipe = url.indexOf('|');
        return pipe < 0 ? url : url.substring(0, pipe);
    }
}
