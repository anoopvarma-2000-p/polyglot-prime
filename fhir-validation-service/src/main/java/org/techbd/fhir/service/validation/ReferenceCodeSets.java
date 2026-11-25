package org.techbd.fhir.service.validation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.ValueSet;

/**
 * The reference terminology codes (SNOMED, ICD-10-CM, LOINC, CPT, HCPCS, BCP-47 language subtags and the custom
 * SHIN-NY questionnaire codes) parsed <b>once per JVM</b> and shared by every FHIR validator.
 *
 * <p>
 * Before this class existed, each validator re-read and re-parsed every PSV file twice: once in
 * {@link PrePopulateSupport} (as {@code CodeSystem} concepts) and once in {@link PostPopulateSupport} (as
 * {@code ValueSet} concepts). That is about one million HAPI model objects per validator. Now each file is read
 * once, and every validator shares the same element instances. Each validator still copies the <em>lists</em>
 * (cheap: one array of references) into its own CodeSystems and ValueSets, as before.
 *
 * <p>
 * Parsing matches the previous {@code ConceptReaderUtils} behaviour exactly: one code per distinct line, in file
 * order, with no trimming or header skipping.
 *
 * <p>
 * The exposed lists are unmodifiable. The element objects are shared, so they must be treated as read-only.
 * Within one validator they were already shared across several ValueSet includes and concurrent validations.
 */
public final class ReferenceCodeSets {

    /** Classpath folder that holds the production PSV files. */
    public static final String DEFAULT_REFERENCE_CODES_PATH = "ig-packages/reference/";

    static final String SNOMED_FILE = "snomed.psv";
    static final String ICD10CM_FILE = "icd10cm.psv";
    static final String LOINC_FILE = "loinc.psv";
    static final String CPT_FILE = "cpt.psv";
    static final String HCPCS_FILE = "hcpcs.psv";
    static final String LANGUAGE_SUBTAGS_FILE = "language-subtags.psv";
    static final String CUSTOM_SYSTEM_CODE_FILE = "custom-system-code.psv";

    private final String sourcePath;

    // CodeSystem concepts (used by PrePopulateSupport)
    private final List<CodeSystem.ConceptDefinitionComponent> snomedCodeSystemConcepts;
    private final List<CodeSystem.ConceptDefinitionComponent> icd10CodeSystemConcepts;
    private final List<CodeSystem.ConceptDefinitionComponent> loincCodeSystemConcepts;
    private final List<CodeSystem.ConceptDefinitionComponent> cptCodeSystemConcepts;
    private final List<CodeSystem.ConceptDefinitionComponent> hcpcsCodeSystemConcepts;
    private final List<CodeSystem.ConceptDefinitionComponent> languageCodeSystemConcepts;

    // ValueSet concepts (used by PostPopulateSupport)
    private final List<ValueSet.ConceptReferenceComponent> snomedValueSetConcepts;
    private final List<ValueSet.ConceptReferenceComponent> icd10ValueSetConcepts;
    private final List<ValueSet.ConceptReferenceComponent> loincValueSetConcepts;
    private final List<ValueSet.ConceptReferenceComponent> hcpcsValueSetConcepts;
    private final List<ValueSet.ConceptReferenceComponent> languageValueSetConcepts;
    private final List<ValueSet.ConceptReferenceComponent> customSystemValueSetConcepts;

    private ReferenceCodeSets(final String sourcePath) {
        this.sourcePath = sourcePath;

        final List<String> snomed = readDistinctLines(sourcePath + SNOMED_FILE);
        final List<String> icd10 = readDistinctLines(sourcePath + ICD10CM_FILE);
        final List<String> loinc = readDistinctLines(sourcePath + LOINC_FILE);
        final List<String> cpt = readDistinctLines(sourcePath + CPT_FILE);
        final List<String> hcpcs = readDistinctLines(sourcePath + HCPCS_FILE);
        final List<String> language = readDistinctLines(sourcePath + LANGUAGE_SUBTAGS_FILE);
        final List<String> custom = readDistinctLines(sourcePath + CUSTOM_SYSTEM_CODE_FILE);

        this.snomedCodeSystemConcepts = codeSystemConcepts(snomed);
        this.icd10CodeSystemConcepts = codeSystemConcepts(icd10);
        this.loincCodeSystemConcepts = codeSystemConcepts(loinc);
        this.cptCodeSystemConcepts = codeSystemConcepts(cpt);
        this.hcpcsCodeSystemConcepts = codeSystemConcepts(hcpcs);
        this.languageCodeSystemConcepts = codeSystemConcepts(language);

        this.snomedValueSetConcepts = valueSetConcepts(snomed);
        this.icd10ValueSetConcepts = valueSetConcepts(icd10);
        this.loincValueSetConcepts = valueSetConcepts(loinc);
        this.hcpcsValueSetConcepts = valueSetConcepts(hcpcs);
        this.languageValueSetConcepts = valueSetConcepts(language);
        this.customSystemValueSetConcepts = valueSetConcepts(custom);
    }

    /**
     * The JVM-wide instance, loaded from {@link #DEFAULT_REFERENCE_CODES_PATH} on first use (thread-safe, loaded at
     * most once).
     */
    public static ReferenceCodeSets shared() {
        return SharedHolder.INSTANCE;
    }

    /**
     * Loads a separate instance from another classpath folder (must end with {@code /}). Intended for tests;
     * production code uses {@link #shared()}.
     */
    public static ReferenceCodeSets load(final String classpathFolder) {
        Objects.requireNonNull(classpathFolder, "classpathFolder");
        return new ReferenceCodeSets(classpathFolder.endsWith("/") ? classpathFolder : classpathFolder + "/");
    }

    private static final class SharedHolder {
        private static final ReferenceCodeSets INSTANCE = new ReferenceCodeSets(DEFAULT_REFERENCE_CODES_PATH);
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public List<CodeSystem.ConceptDefinitionComponent> getSnomedCodeSystemConcepts() {
        return snomedCodeSystemConcepts;
    }

    public List<CodeSystem.ConceptDefinitionComponent> getIcd10CodeSystemConcepts() {
        return icd10CodeSystemConcepts;
    }

    public List<CodeSystem.ConceptDefinitionComponent> getLoincCodeSystemConcepts() {
        return loincCodeSystemConcepts;
    }

    public List<CodeSystem.ConceptDefinitionComponent> getCptCodeSystemConcepts() {
        return cptCodeSystemConcepts;
    }

    public List<CodeSystem.ConceptDefinitionComponent> getHcpcsCodeSystemConcepts() {
        return hcpcsCodeSystemConcepts;
    }

    public List<CodeSystem.ConceptDefinitionComponent> getLanguageCodeSystemConcepts() {
        return languageCodeSystemConcepts;
    }

    public List<ValueSet.ConceptReferenceComponent> getSnomedValueSetConcepts() {
        return snomedValueSetConcepts;
    }

    public List<ValueSet.ConceptReferenceComponent> getIcd10ValueSetConcepts() {
        return icd10ValueSetConcepts;
    }

    public List<ValueSet.ConceptReferenceComponent> getLoincValueSetConcepts() {
        return loincValueSetConcepts;
    }

    public List<ValueSet.ConceptReferenceComponent> getHcpcsValueSetConcepts() {
        return hcpcsValueSetConcepts;
    }

    public List<ValueSet.ConceptReferenceComponent> getLanguageValueSetConcepts() {
        return languageValueSetConcepts;
    }

    public List<ValueSet.ConceptReferenceComponent> getCustomSystemValueSetConcepts() {
        return customSystemValueSetConcepts;
    }

    private static List<CodeSystem.ConceptDefinitionComponent> codeSystemConcepts(final List<String> codes) {
        return toUnmodifiable(codes, code -> new CodeSystem.ConceptDefinitionComponent().setCode(code));
    }

    private static List<ValueSet.ConceptReferenceComponent> valueSetConcepts(final List<String> codes) {
        return toUnmodifiable(codes, code -> new ValueSet.ConceptReferenceComponent().setCode(code));
    }

    private static <T> List<T> toUnmodifiable(final List<String> codes, final Function<String, T> factory) {
        final List<T> concepts = new ArrayList<>(codes.size());
        for (final String code : codes) {
            concepts.add(factory.apply(code));
        }
        return Collections.unmodifiableList(concepts);
    }

    /** Distinct lines in file order, matching {@code FileUtils.readFile(file).stream().distinct()}. */
    static List<String> readDistinctLines(final String classpathResource) {
        final InputStream in = ReferenceCodeSets.class.getClassLoader().getResourceAsStream(classpathResource);
        if (in == null) {
            throw new IllegalStateException("Reference code file not found on classpath: " + classpathResource);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            final LinkedHashSet<String> distinct = new LinkedHashSet<>();
            String line;
            while ((line = reader.readLine()) != null) {
                distinct.add(line);
            }
            return List.copyOf(distinct);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read reference code file: " + classpathResource, e);
        }
    }
}
