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
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

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
 * Three read-only views are offered, each built on first use: {@code CodeSystem} concepts and {@code ValueSet}
 * concepts for the enumerated (legacy) terminology, and {@code Set<String>} code sets for the lookup-based
 * {@link ReferenceTerminologyValidationSupport}.
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

    // Distinct codes per file, in file order. Every view below is built from these, so all views share the
    // same String instances.
    private final List<String> snomed;
    private final List<String> icd10;
    private final List<String> loinc;
    private final List<String> cpt;
    private final List<String> hcpcs;
    private final List<String> language;
    private final List<String> custom;

    // Views are built on first use, so only the representation a validator actually uses costs memory:
    // HAPI model lists for the enumerated (legacy) terminology, or Set<String>s for lookup terminology.
    private final Lazy<List<CodeSystem.ConceptDefinitionComponent>> snomedCodeSystemConcepts;
    private final Lazy<List<CodeSystem.ConceptDefinitionComponent>> icd10CodeSystemConcepts;
    private final Lazy<List<CodeSystem.ConceptDefinitionComponent>> loincCodeSystemConcepts;
    private final Lazy<List<CodeSystem.ConceptDefinitionComponent>> cptCodeSystemConcepts;
    private final Lazy<List<CodeSystem.ConceptDefinitionComponent>> hcpcsCodeSystemConcepts;
    private final Lazy<List<CodeSystem.ConceptDefinitionComponent>> languageCodeSystemConcepts;

    private final Lazy<List<ValueSet.ConceptReferenceComponent>> snomedValueSetConcepts;
    private final Lazy<List<ValueSet.ConceptReferenceComponent>> icd10ValueSetConcepts;
    private final Lazy<List<ValueSet.ConceptReferenceComponent>> loincValueSetConcepts;
    private final Lazy<List<ValueSet.ConceptReferenceComponent>> hcpcsValueSetConcepts;
    private final Lazy<List<ValueSet.ConceptReferenceComponent>> languageValueSetConcepts;
    private final Lazy<List<ValueSet.ConceptReferenceComponent>> customSystemValueSetConcepts;

    private final Lazy<Set<String>> snomedCodes;
    private final Lazy<Set<String>> icd10Codes;
    private final Lazy<Set<String>> loincCodes;
    private final Lazy<Set<String>> cptCodes;
    private final Lazy<Set<String>> hcpcsCodes;
    private final Lazy<Set<String>> languageCodes;
    private final Lazy<Set<String>> customSystemCodes;

    private ReferenceCodeSets(final String sourcePath) {
        this.sourcePath = sourcePath;

        this.snomed = readDistinctLines(sourcePath + SNOMED_FILE);
        this.icd10 = readDistinctLines(sourcePath + ICD10CM_FILE);
        this.loinc = readDistinctLines(sourcePath + LOINC_FILE);
        this.cpt = readDistinctLines(sourcePath + CPT_FILE);
        this.hcpcs = readDistinctLines(sourcePath + HCPCS_FILE);
        this.language = readDistinctLines(sourcePath + LANGUAGE_SUBTAGS_FILE);
        this.custom = readDistinctLines(sourcePath + CUSTOM_SYSTEM_CODE_FILE);

        this.snomedCodeSystemConcepts = new Lazy<>(() -> codeSystemConcepts(snomed));
        this.icd10CodeSystemConcepts = new Lazy<>(() -> codeSystemConcepts(icd10));
        this.loincCodeSystemConcepts = new Lazy<>(() -> codeSystemConcepts(loinc));
        this.cptCodeSystemConcepts = new Lazy<>(() -> codeSystemConcepts(cpt));
        this.hcpcsCodeSystemConcepts = new Lazy<>(() -> codeSystemConcepts(hcpcs));
        this.languageCodeSystemConcepts = new Lazy<>(() -> codeSystemConcepts(language));

        this.snomedValueSetConcepts = new Lazy<>(() -> valueSetConcepts(snomed));
        this.icd10ValueSetConcepts = new Lazy<>(() -> valueSetConcepts(icd10));
        this.loincValueSetConcepts = new Lazy<>(() -> valueSetConcepts(loinc));
        this.hcpcsValueSetConcepts = new Lazy<>(() -> valueSetConcepts(hcpcs));
        this.languageValueSetConcepts = new Lazy<>(() -> valueSetConcepts(language));
        this.customSystemValueSetConcepts = new Lazy<>(() -> valueSetConcepts(custom));

        this.snomedCodes = new Lazy<>(() -> Set.copyOf(snomed));
        this.icd10Codes = new Lazy<>(() -> Set.copyOf(icd10));
        this.loincCodes = new Lazy<>(() -> Set.copyOf(loinc));
        this.cptCodes = new Lazy<>(() -> Set.copyOf(cpt));
        this.hcpcsCodes = new Lazy<>(() -> Set.copyOf(hcpcs));
        this.languageCodes = new Lazy<>(() -> Set.copyOf(language));
        this.customSystemCodes = new Lazy<>(() -> Set.copyOf(custom));
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

    // ----- CodeSystem concepts (enumerated terminology, PrePopulateSupport) -----

    public List<CodeSystem.ConceptDefinitionComponent> getSnomedCodeSystemConcepts() {
        return snomedCodeSystemConcepts.get();
    }

    public List<CodeSystem.ConceptDefinitionComponent> getIcd10CodeSystemConcepts() {
        return icd10CodeSystemConcepts.get();
    }

    public List<CodeSystem.ConceptDefinitionComponent> getLoincCodeSystemConcepts() {
        return loincCodeSystemConcepts.get();
    }

    public List<CodeSystem.ConceptDefinitionComponent> getCptCodeSystemConcepts() {
        return cptCodeSystemConcepts.get();
    }

    public List<CodeSystem.ConceptDefinitionComponent> getHcpcsCodeSystemConcepts() {
        return hcpcsCodeSystemConcepts.get();
    }

    public List<CodeSystem.ConceptDefinitionComponent> getLanguageCodeSystemConcepts() {
        return languageCodeSystemConcepts.get();
    }

    // ----- ValueSet concepts (enumerated terminology, PostPopulateSupport) -----

    public List<ValueSet.ConceptReferenceComponent> getSnomedValueSetConcepts() {
        return snomedValueSetConcepts.get();
    }

    public List<ValueSet.ConceptReferenceComponent> getIcd10ValueSetConcepts() {
        return icd10ValueSetConcepts.get();
    }

    public List<ValueSet.ConceptReferenceComponent> getLoincValueSetConcepts() {
        return loincValueSetConcepts.get();
    }

    public List<ValueSet.ConceptReferenceComponent> getHcpcsValueSetConcepts() {
        return hcpcsValueSetConcepts.get();
    }

    public List<ValueSet.ConceptReferenceComponent> getLanguageValueSetConcepts() {
        return languageValueSetConcepts.get();
    }

    public List<ValueSet.ConceptReferenceComponent> getCustomSystemValueSetConcepts() {
        return customSystemValueSetConcepts.get();
    }

    // ----- Code sets (lookup terminology, ReferenceTerminologyValidationSupport) -----

    public Set<String> getSnomedCodes() {
        return snomedCodes.get();
    }

    public Set<String> getIcd10Codes() {
        return icd10Codes.get();
    }

    public Set<String> getLoincCodes() {
        return loincCodes.get();
    }

    public Set<String> getCptCodes() {
        return cptCodes.get();
    }

    public Set<String> getHcpcsCodes() {
        return hcpcsCodes.get();
    }

    public Set<String> getLanguageCodes() {
        return languageCodes.get();
    }

    public Set<String> getCustomSystemCodes() {
        return customSystemCodes.get();
    }

    /** Thread-safe memoizing supplier (computes at most once). */
    private static final class Lazy<T> {
        private final Supplier<T> supplier;
        private volatile T value;

        Lazy(final Supplier<T> supplier) {
            this.supplier = supplier;
        }

        T get() {
            T result = value;
            if (result == null) {
                synchronized (this) {
                    result = value;
                    if (result == null) {
                        result = supplier.get();
                        value = result;
                    }
                }
            }
            return result;
        }
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
