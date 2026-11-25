package org.techbd.fhir.service.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techbd.corelib.util.AppLogger;
import org.techbd.corelib.util.TemplateLogger;
import org.techbd.fhir.config.AppConfig;
import org.techbd.fhir.config.AppConfig.FhirV4Config;
import org.techbd.fhir.exceptions.ErrorCode;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;

/**
 * Wiring tests for the dynamic-validator settings of {@link OrchestrationEngine.HapiValidationEngine}. No
 * SHIN-NY package is configured and no dynamic validator is built, so these tests run in milliseconds.
 */
class HapiValidationEngineValidatorCacheTest {

    /** Old SHIN-NY version that is bundled on the classpath but not configured. */
    private static final String BUNDLED_UNCONFIGURED_IG_VERSION = "1.9.3";
    private static final String PAYLOAD = """
            { "resourceType": "Bundle", "id": "example",
              "meta": { "profile": ["http://shinny.org/us/ny/hrsn/StructureDefinition/SHINNYBundleProfile"] } }
            """;

    private Tracer tracer;
    private AppLogger appLogger;
    private TemplateLogger templateLogger;

    @BeforeEach
    void setUp() {
        tracer = mock(Tracer.class);
        final SpanBuilder spanBuilder = mock(SpanBuilder.class);
        when(tracer.spanBuilder(any())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(mock(Span.class));
        templateLogger = mock(TemplateLogger.class);
        appLogger = mock(AppLogger.class);
        when(appLogger.getLogger(any())).thenReturn(templateLogger);
    }

    private static Map<String, FhirV4Config> igPackagesWithoutConfiguredShinny() {
        final FhirV4Config fhirV4Config = new FhirV4Config();
        fhirV4Config.setShinnyPackages(new HashMap<>());
        fhirV4Config.setBasePackages(Map.of("us-core", "ig-packages/fhir-v4/us-core/stu-7.0.0"));
        return Map.of("fhir-v4", fhirV4Config);
    }

    private OrchestrationEngine.HapiValidationEngine.Builder engineBuilder() {
        return new OrchestrationEngine.HapiValidationEngine.Builder()
                .withIgPackages(igPackagesWithoutConfiguredShinny())
                .withTracer(tracer)
                .withAppLogger(appLogger)
                .withTemplateLogger(templateLogger);
    }

    @Test
    void builderDefaultsBoundDynamicValidators() {
        final var engine = engineBuilder().build();

        assertThat(engine.getValidatorRegistry().maxDynamic())
                .isEqualTo(FhirBundleValidatorRegistry.DEFAULT_MAX_DYNAMIC_VALIDATORS)
                .isEqualTo(2);
        assertThat(engine.getValidatorRegistry().dynamicTtl())
                .isEqualTo(FhirBundleValidatorRegistry.DEFAULT_DYNAMIC_VALIDATOR_TTL)
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(engine.getConfiguredValidatorCount()).isZero();
        assertThat(engine.getDynamicValidatorCount()).isZero();
    }

    @Test
    void builderAppliesConfiguredLimits() {
        final var engine = engineBuilder()
                .withMaxDynamicValidators(5)
                .withDynamicValidatorTtl(Duration.ofMinutes(10))
                .build();

        assertThat(engine.getValidatorRegistry().maxDynamic()).isEqualTo(5);
        assertThat(engine.getValidatorRegistry().dynamicTtl()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void nullTtlKeepsDefault() {
        final var engine = engineBuilder().withDynamicValidatorTtl(null).build();

        assertThat(engine.getValidatorRegistry().dynamicTtl())
                .isEqualTo(FhirBundleValidatorRegistry.DEFAULT_DYNAMIC_VALIDATOR_TTL);
    }

    @Test
    void negativeMaxDynamicFailsFast() {
        assertThatThrownBy(() -> engineBuilder().withMaxDynamicValidators(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void killSwitchDoesNotBuildValidatorForRequestedIgVersionAndFallsBackToProfileUrl() {
        final var engine = engineBuilder().withMaxDynamicValidators(0).build();

        final var result = engine.validate(PAYLOAD, "interaction-1", BUNDLED_UNCONFIGURED_IG_VERSION);

        // No validator was built for 1.9.3; the fallback profile-URL match finds no configured validator either.
        assertThat(engine.getValidatorInitializationCount().get()).isZero();
        assertThat(engine.getDynamicValidatorCount()).isZero();
        assertThat(result.isValid()).isFalse();
        assertThat(result.getOperationOutcome()).contains(ErrorCode.INVALID_BUNDLE_PROFILE.getMessage());
    }

    @Test
    void orchestrationEngineUsesDefaultsWhenValidatorSettingsAreNotBound() {
        final AppConfig appConfig = mock(AppConfig.class); // getFhir() returns null
        when(appConfig.getIgPackages()).thenReturn(igPackagesWithoutConfiguredShinny());

        final var engine = hapiEngineOf(new OrchestrationEngine(appConfig, appLogger));

        assertThat(engine.getValidatorRegistry().maxDynamic())
                .isEqualTo(FhirBundleValidatorRegistry.DEFAULT_MAX_DYNAMIC_VALIDATORS);
        assertThat(engine.getValidatorRegistry().dynamicTtl())
                .isEqualTo(FhirBundleValidatorRegistry.DEFAULT_DYNAMIC_VALIDATOR_TTL);
    }

    @Test
    void orchestrationEnginePassesBoundValidatorSettings() {
        final AppConfig.ValidatorSettings validatorSettings = new AppConfig.ValidatorSettings();
        validatorSettings.setMaxDynamic(0);
        validatorSettings.setDynamicTtl(Duration.ofMinutes(5));
        final AppConfig.FhirSettings fhirSettings = new AppConfig.FhirSettings();
        fhirSettings.setValidator(validatorSettings);
        final AppConfig appConfig = mock(AppConfig.class);
        when(appConfig.getIgPackages()).thenReturn(igPackagesWithoutConfiguredShinny());
        when(appConfig.getFhir()).thenReturn(fhirSettings);

        final var engine = hapiEngineOf(new OrchestrationEngine(appConfig, appLogger));

        assertThat(engine.getValidatorRegistry().maxDynamic()).isZero();
        assertThat(engine.getValidatorRegistry().isDynamicEnabled()).isFalse();
        assertThat(engine.getValidatorRegistry().dynamicTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void appConfigValidatorSettingsHaveSafeDefaults() {
        final AppConfig.ValidatorSettings settings = new AppConfig().getFhir().getValidator();

        assertThat(settings.getMaxDynamic()).isEqualTo(2);
        assertThat(settings.getDynamicTtl()).isEqualTo(Duration.ofMinutes(30));
    }

    private static OrchestrationEngine.HapiValidationEngine hapiEngineOf(OrchestrationEngine orchestrationEngine) {
        return (OrchestrationEngine.HapiValidationEngine) orchestrationEngine
                .getValidationEngine(OrchestrationEngine.ValidationEngineIdentifier.HAPI);
    }
}
