package org.techbd.fhir.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.Test;
import org.techbd.fhir.util.ConceptReaderUtils;

class ReferenceCodeSetsTest {

    static final String TEST_CODES_PATH = "org/techbd/reference-codes-test/";

    private static List<String> csCodes(List<CodeSystem.ConceptDefinitionComponent> concepts) {
        return concepts.stream().map(CodeSystem.ConceptDefinitionComponent::getCode).toList();
    }

    private static List<String> vsCodes(List<ValueSet.ConceptReferenceComponent> concepts) {
        return concepts.stream().map(ValueSet.ConceptReferenceComponent::getCode).toList();
    }

    @Test
    void parsesOneCodePerDistinctLineInFileOrder() {
        final ReferenceCodeSets codes = ReferenceCodeSets.load(TEST_CODES_PATH);

        assertThat(csCodes(codes.getSnomedCodeSystemConcepts())).containsExactly("73211009", "44054006", "38341003");
        assertThat(csCodes(codes.getIcd10CodeSystemConcepts())).containsExactly("E11.9", "I10");
        assertThat(csCodes(codes.getLoincCodeSystemConcepts())).containsExactly("71802-3", "96778-6");
        // Same as the previous reader: no header skipping.
        assertThat(csCodes(codes.getCptCodeSystemConcepts())).containsExactly("CODE", "0001A", "0001F");
        assertThat(csCodes(codes.getHcpcsCodeSystemConcepts())).containsExactly("G0136", "T1015");
        assertThat(csCodes(codes.getLanguageCodeSystemConcepts())).containsExactly("en", "es");

        assertThat(vsCodes(codes.getSnomedValueSetConcepts())).containsExactly("73211009", "44054006", "38341003");
        assertThat(vsCodes(codes.getIcd10ValueSetConcepts())).containsExactly("E11.9", "I10");
        assertThat(vsCodes(codes.getLoincValueSetConcepts())).containsExactly("71802-3", "96778-6");
        assertThat(vsCodes(codes.getHcpcsValueSetConcepts())).containsExactly("G0136", "T1015");
        assertThat(vsCodes(codes.getLanguageValueSetConcepts())).containsExactly("en", "es");
        assertThat(vsCodes(codes.getCustomSystemValueSetConcepts())).containsExactly("NYS-AHC-HRSN", "NYSAHCHRSN");
    }

    @Test
    void codeSystemAndValueSetConceptsShareTheSameCodeStrings() {
        final ReferenceCodeSets codes = ReferenceCodeSets.load(TEST_CODES_PATH);

        // Each file is read once, so both representations reuse the same String instances.
        for (int i = 0; i < codes.getSnomedCodeSystemConcepts().size(); i++) {
            assertThat(codes.getSnomedValueSetConcepts().get(i).getCode())
                    .isSameAs(codes.getSnomedCodeSystemConcepts().get(i).getCode());
        }
    }

    @Test
    void exposedListsAreUnmodifiable() {
        final ReferenceCodeSets codes = ReferenceCodeSets.load(TEST_CODES_PATH);

        assertThatThrownBy(() -> codes.getSnomedCodeSystemConcepts().add(new CodeSystem.ConceptDefinitionComponent()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> codes.getLoincValueSetConcepts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> codes.getCustomSystemValueSetConcepts().remove(0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void loadAcceptsFolderWithoutTrailingSlash() {
        final ReferenceCodeSets codes = ReferenceCodeSets.load("org/techbd/reference-codes-test");

        assertThat(codes.getSourcePath()).isEqualTo(TEST_CODES_PATH);
        assertThat(codes.getIcd10CodeSystemConcepts()).hasSize(2);
    }

    @Test
    void missingFileFailsWithClearMessage() {
        assertThatThrownBy(() -> ReferenceCodeSets.load("org/techbd/does-not-exist/"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org/techbd/does-not-exist/snomed.psv");
    }

    @Test
    void sharedIsLoadedOnceAndReturnsTheSameInstanceAcrossThreads() throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final List<Callable<ReferenceCodeSets>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(ReferenceCodeSets::shared);
            }
            final ReferenceCodeSets expected = ReferenceCodeSets.shared();
            for (final Future<ReferenceCodeSets> f : pool.invokeAll(calls, 60, TimeUnit.SECONDS)) {
                assertThat(f.get()).isSameAs(expected);
            }
            assertThat(expected.getSourcePath()).isEqualTo(ReferenceCodeSets.DEFAULT_REFERENCE_CODES_PATH);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Regression guard: for every production PSV file, the shared code lists contain exactly the codes (same
     * order, same count) that the previous per-validator {@link ConceptReaderUtils} parsing produced.
     */
    @Test
    void sharedProductionCodesMatchLegacyParsing() {
        final ReferenceCodeSets shared = ReferenceCodeSets.shared();
        final String base = ReferenceCodeSets.DEFAULT_REFERENCE_CODES_PATH;

        assertThat(csCodes(shared.getSnomedCodeSystemConcepts()))
                .isEqualTo(csCodes(ConceptReaderUtils.getCodeSystemConcepts_wCode(base + "snomed.psv")))
                .hasSizeGreaterThan(300_000);
        assertThat(csCodes(shared.getIcd10CodeSystemConcepts()))
                .isEqualTo(csCodes(ConceptReaderUtils.getCodeSystemConcepts_wCode(base + "icd10cm.psv")));
        assertThat(csCodes(shared.getLoincCodeSystemConcepts()))
                .isEqualTo(csCodes(ConceptReaderUtils.getCodeSystemConcepts_wCode(base + "loinc.psv")));
        assertThat(csCodes(shared.getCptCodeSystemConcepts()))
                .isEqualTo(csCodes(ConceptReaderUtils.getCodeSystemConcepts_wCode(base + "cpt.psv")));
        assertThat(csCodes(shared.getHcpcsCodeSystemConcepts()))
                .isEqualTo(csCodes(ConceptReaderUtils.getCodeSystemConcepts_wCode(base + "hcpcs.psv")));
        assertThat(csCodes(shared.getLanguageCodeSystemConcepts()))
                .isEqualTo(csCodes(ConceptReaderUtils.getCodeSystemConcepts_wCode(base + "language-subtags.psv")));

        assertThat(vsCodes(shared.getSnomedValueSetConcepts()))
                .isEqualTo(vsCodes(ConceptReaderUtils.getValueSetConcepts_wCode(base + "snomed.psv")));
        assertThat(vsCodes(shared.getIcd10ValueSetConcepts()))
                .isEqualTo(vsCodes(ConceptReaderUtils.getValueSetConcepts_wCode(base + "icd10cm.psv")));
        assertThat(vsCodes(shared.getLoincValueSetConcepts()))
                .isEqualTo(vsCodes(ConceptReaderUtils.getValueSetConcepts_wCode(base + "loinc.psv")));
        assertThat(vsCodes(shared.getHcpcsValueSetConcepts()))
                .isEqualTo(vsCodes(ConceptReaderUtils.getValueSetConcepts_wCode(base + "hcpcs.psv")));
        assertThat(vsCodes(shared.getLanguageValueSetConcepts()))
                .isEqualTo(vsCodes(ConceptReaderUtils.getValueSetConcepts_wCode(base + "language-subtags.psv")));
        assertThat(vsCodes(shared.getCustomSystemValueSetConcepts()))
                .isEqualTo(vsCodes(ConceptReaderUtils.getValueSetConcepts_wCode(base + "custom-system-code.psv")));
    }

    @Test
    void codeSetsContainTheSameCodesAsTheConceptLists() {
        final ReferenceCodeSets codes = ReferenceCodeSets.load(TEST_CODES_PATH);

        assertThat(codes.getSnomedCodes()).containsExactlyInAnyOrderElementsOf(csCodes(codes.getSnomedCodeSystemConcepts()));
        assertThat(codes.getIcd10Codes()).containsExactlyInAnyOrder("E11.9", "I10");
        assertThat(codes.getLoincCodes()).containsExactlyInAnyOrder("71802-3", "96778-6");
        assertThat(codes.getCptCodes()).containsExactlyInAnyOrder("CODE", "0001A", "0001F");
        assertThat(codes.getHcpcsCodes()).containsExactlyInAnyOrder("G0136", "T1015");
        assertThat(codes.getLanguageCodes()).containsExactlyInAnyOrder("en", "es");
        assertThat(codes.getCustomSystemCodes()).containsExactlyInAnyOrder("NYS-AHC-HRSN", "NYSAHCHRSN");
    }

    @Test
    void codeSetsAreUnmodifiableAndBuiltOnce() {
        final ReferenceCodeSets codes = ReferenceCodeSets.load(TEST_CODES_PATH);

        assertThat(codes.getLoincCodes()).isSameAs(codes.getLoincCodes());
        assertThat(codes.getSnomedCodeSystemConcepts()).isSameAs(codes.getSnomedCodeSystemConcepts());
        assertThatThrownBy(() -> codes.getLoincCodes().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sharedProductionCodeSetsMatchTheConceptLists() {
        final ReferenceCodeSets shared = ReferenceCodeSets.shared();

        assertThat(shared.getSnomedCodes()).hasSize(shared.getSnomedCodeSystemConcepts().size());
        assertThat(shared.getIcd10Codes()).hasSize(shared.getIcd10CodeSystemConcepts().size());
        assertThat(shared.getLoincCodes()).hasSize(shared.getLoincCodeSystemConcepts().size());
        assertThat(shared.getCptCodes()).hasSize(shared.getCptCodeSystemConcepts().size());
        assertThat(shared.getHcpcsCodes()).hasSize(shared.getHcpcsCodeSystemConcepts().size());
        assertThat(shared.getLanguageCodes()).hasSize(shared.getLanguageCodeSystemConcepts().size());
        assertThat(shared.getCustomSystemCodes()).hasSize(shared.getCustomSystemValueSetConcepts().size());
        assertThat(shared.getSnomedCodes()).contains("160245001", "71388002");
    }
}
