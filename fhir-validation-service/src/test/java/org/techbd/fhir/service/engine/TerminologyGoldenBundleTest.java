package org.techbd.fhir.service.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.techbd.corelib.util.AppLogger;
import org.techbd.corelib.util.TemplateLogger;

import ca.uhn.fhir.validation.FhirValidator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;

/**
 * Golden-output check for lookup terminology (R4): validates every IG example bundle with enumerated terminology
 * and with lookup terminology and requires identical validation messages (severity, location, message id and
 * text).
 *
 * <p>
 * Takes about two minutes and ~1.5 GB of heap, so it only runs on request:
 *
 * <pre>
 * mvn test -pl fhir-validation-service -am -DargLine="-Xmx3g -Dtechbd.terminology.golden=true" \
 *     -Dtest=TerminologyGoldenBundleTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfSystemProperty(named = "techbd.terminology.golden", matches = "true")
class TerminologyGoldenBundleTest {

    private static final Map<String, String> BASE_PACKAGES = new TreeMap<>(Map.of(
            "us-core", "ig-packages/fhir-v4/us-core/stu-7.0.0",
            "sdoh", "ig-packages/fhir-v4/sdoh-clinicalcare/stu-2.3.0",
            "uv-sdc", "ig-packages/fhir-v4/uv-sdc/stu-3.0.0",
            "extensions", "ig-packages/fhir-v4/uv-extensions/5.2.0"));

    private static FhirValidator validator(boolean lookupTerminology, String packagePath, String profileBaseUrl,
            String igVersion) {
        final Tracer tracer = mock(Tracer.class);
        final SpanBuilder spanBuilder = mock(SpanBuilder.class);
        when(tracer.spanBuilder(any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(mock(Span.class));
        final AppLogger appLogger = mock(AppLogger.class);
        final TemplateLogger templateLogger = mock(TemplateLogger.class);
        when(appLogger.getLogger(any())).thenReturn(templateLogger);
        final var engine = new OrchestrationEngine.HapiValidationEngine.Builder()
                .withTracer(tracer)
                .withAppLogger(appLogger)
                .withTemplateLogger(templateLogger)
                .withLookupTerminology(lookupTerminology)
                .build();
        return engine.initializeFhirValidator(packagePath, BASE_PACKAGES, profileBaseUrl, igVersion);
    }

    private static List<String> messages(FhirValidator validator, Path examples) throws IOException {
        final List<String> lines = new ArrayList<>();
        try (Stream<Path> files = Files.list(examples)) {
            for (final Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                validator.validateWithResult(Files.readString(file)).getMessages().stream()
                        .map(m -> file.getFileName() + " | " + m.getSeverity() + " | " + m.getLocationString()
                                + " | " + m.getMessageId() + " | " + m.getMessage())
                        .sorted()
                        .forEach(lines::add);
            }
        }
        return lines;
    }

    @ParameterizedTest
    @CsvSource({
            "shinny-examples, ig-packages/shin-ny-ig/shinny/v1.9.4, http://shinny.org/us/ny/hrsn, 1.9.4",
            "test-shinny-examples, ig-packages/shin-ny-ig/test-shinny/v2.0.0, http://test.shinny.org/us/ny/hrsn, 2.0.0" })
    void exampleBundlesValidateIdentically(String examplesFolder, String packagePath, String profileBaseUrl,
            String igVersion) throws IOException {
        final Path examples = Path.of("src/test/resources/org/techbd/ig-examples", examplesFolder);

        final List<String> enumerated =
                messages(validator(false, packagePath, profileBaseUrl, igVersion), examples);
        final List<String> lookup = messages(validator(true, packagePath, profileBaseUrl, igVersion), examples);

        assertThat(enumerated).isNotEmpty();
        assertThat(lookup).containsExactlyElementsOf(enumerated);
    }
}
