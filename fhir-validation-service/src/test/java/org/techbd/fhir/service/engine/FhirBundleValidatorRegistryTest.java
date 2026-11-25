package org.techbd.fhir.service.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;

/**
 * Unit tests for {@link FhirBundleValidatorRegistry}. Uses plain strings instead of real HAPI validators (which take
 * ~14 s and ~0.7 GB each to build), a manual ticker for deterministic expiry, and a same-thread executor so removal
 * notifications are observed synchronously.
 */
class FhirBundleValidatorRegistryTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final FakeTicker ticker = new FakeTicker();
    private final List<Removal> removals = new CopyOnWriteArrayList<>();
    private final AtomicInteger builds = new AtomicInteger();
    private final Function<String, String> factory = key -> {
        builds.incrementAndGet();
        return "validator:" + key;
    };

    @BeforeEach
    void reset() {
        removals.clear();
        builds.set(0);
    }

    private FhirBundleValidatorRegistry<String, String> registry(int maxDynamic, Duration ttl) {
        return new FhirBundleValidatorRegistry<>(maxDynamic, ttl, ticker, Scheduler.disabledScheduler(),
                Runnable::run, (key, value, cause) -> removals.add(new Removal(key, cause)));
    }

    @Test
    void configuredValidatorIsBuiltOnceAndReturnedWithoutTouchingDynamicPool() {
        final var registry = registry(2, TTL);

        final String first = registry.registerConfigured("v1.9.4", factory);
        final String second = registry.registerConfigured("v1.9.4", factory);
        final String viaLookup = registry.getOrCreate("v1.9.4", factory);

        assertThat(first).isEqualTo("validator:v1.9.4").isSameAs(second).isSameAs(viaLookup);
        assertThat(builds).hasValue(1);
        assertThat(registry.isConfigured("v1.9.4")).isTrue();
        assertThat(registry.getConfigured("v1.9.4")).isSameAs(first);
        assertThat(registry.configuredCount()).isEqualTo(1);
        assertThat(registry.dynamicCount()).isZero();
        assertThat(registry.dynamicValidators()).isEmpty();
    }

    @Test
    void configuredValidatorsSurviveDynamicOverflowAndIdleTime() {
        final var registry = registry(2, TTL);
        final String configured = registry.registerConfigured("shinny-v1.9.4", factory);
        registry.registerConfigured("test-shinny-v2.0.0", factory);

        for (int i = 0; i < 10; i++) {
            registry.getOrCreate("dynamic-" + i, factory);
        }
        ticker.advance(Duration.ofDays(1));
        registry.cleanUp();

        assertThat(registry.getConfigured("shinny-v1.9.4")).isSameAs(configured);
        assertThat(registry.getConfigured("test-shinny-v2.0.0")).isNotNull();
        assertThat(registry.configuredCount()).isEqualTo(2);
        assertThat(removals).extracting(Removal::key).doesNotContain("shinny-v1.9.4", "test-shinny-v2.0.0");
    }

    @Test
    void dynamicValidatorIsCachedAndReusedWhileInUse() {
        final var registry = registry(2, TTL);

        final String first = registry.getOrCreate("v1.9.3", factory);
        ticker.advance(Duration.ofMinutes(20));
        final String second = registry.getOrCreate("v1.9.3", factory);

        assertThat(second).isSameAs(first);
        assertThat(builds).hasValue(1);
        assertThat(registry.dynamicCount()).isEqualTo(1);
        assertThat(registry.dynamicStats().hitCount()).isEqualTo(1);
        assertThat(registry.dynamicStats().missCount()).isEqualTo(1);
    }

    @Test
    void dynamicPoolIsBoundedByMaxDynamic() {
        final var registry = registry(2, TTL);

        registry.getOrCreate("v1.8.1", factory);
        registry.getOrCreate("v1.9.3", factory);
        registry.getOrCreate("v1.7.3", factory);
        registry.getOrCreate("v1.6.3", factory);
        registry.cleanUp();

        assertThat(registry.dynamicCount()).isEqualTo(2);
        assertThat(registry.dynamicValidators()).hasSize(2);
        assertThat(removals).hasSize(2).allSatisfy(r -> assertThat(r.cause()).isEqualTo(RemovalCause.SIZE));
        assertThat(registry.dynamicStats().evictionCount()).isEqualTo(2);
    }

    @Test
    void dynamicValidatorExpiresAfterIdleTtl() {
        final var registry = registry(2, TTL);
        registry.getOrCreate("v1.9.3", factory);

        ticker.advance(TTL.minusSeconds(1));
        registry.cleanUp();
        assertThat(registry.dynamicCount()).isEqualTo(1);
        assertThat(removals).isEmpty();

        ticker.advance(Duration.ofSeconds(2));
        registry.cleanUp();
        assertThat(registry.dynamicCount()).isZero();
        assertThat(registry.dynamicValidators()).isEmpty();
        assertThat(removals).containsExactly(new Removal("v1.9.3", RemovalCause.EXPIRED));
    }

    @Test
    void accessResetsTheIdleTimer() {
        final var registry = registry(2, TTL);
        registry.getOrCreate("v1.9.3", factory);

        ticker.advance(Duration.ofMinutes(25));
        registry.getOrCreate("v1.9.3", factory); // access
        ticker.advance(Duration.ofMinutes(25)); // 50 min since build, 25 min since last access
        registry.cleanUp();

        assertThat(registry.dynamicCount()).isEqualTo(1);
        assertThat(builds).hasValue(1);
    }

    @Test
    void expiredDynamicValidatorIsRebuiltOnNextRequest() {
        final var registry = registry(2, TTL);
        final String first = registry.getOrCreate("v1.9.3", factory);

        ticker.advance(TTL.plusMinutes(1));
        registry.cleanUp();
        final String rebuilt = registry.getOrCreate("v1.9.3", factory);

        assertThat(rebuilt).isEqualTo(first).isNotSameAs(first);
        assertThat(builds).hasValue(2);
    }

    @Test
    void maxDynamicZeroDisablesDynamicValidators() {
        final var registry = registry(0, TTL);
        registry.registerConfigured("v1.9.4", factory);
        builds.set(0);

        assertThat(registry.isDynamicEnabled()).isFalse();
        assertThat(registry.getOrCreate("v1.9.3", factory)).isNull();
        assertThat(registry.getOrCreate("v1.9.4", factory)).isEqualTo("validator:v1.9.4");
        assertThat(builds).hasValue(0);
        assertThat(registry.dynamicCount()).isZero();
        assertThat(registry.dynamicValidators()).isEmpty();
        assertThat(registry.dynamicStats().requestCount()).isZero();
    }

    @Test
    void registeringAKeyAsConfiguredRemovesItFromTheDynamicPool() {
        final var registry = registry(2, TTL);
        registry.getOrCreate("v1.9.4", factory);
        assertThat(registry.dynamicCount()).isEqualTo(1);

        registry.registerConfigured("v1.9.4", factory);
        registry.cleanUp();

        assertThat(registry.dynamicCount()).isZero();
        assertThat(registry.isConfigured("v1.9.4")).isTrue();
        assertThat(removals).containsExactly(new Removal("v1.9.4", RemovalCause.EXPLICIT));
    }

    @Test
    void concurrentRequestsForTheSameVersionBuildOnlyOneValidator() throws Exception {
        final var registry = registry(2, TTL);
        final CountDownLatch start = new CountDownLatch(1);
        final Function<String, String> slowFactory = key -> {
            builds.incrementAndGet();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "validator:" + key;
        };
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return registry.getOrCreate("v1.9.3", slowFactory);
                }));
            }
            start.countDown();
            final String expected = results.get(0).get(10, TimeUnit.SECONDS);
            for (final Future<String> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isSameAs(expected);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(builds).hasValue(1);
    }

    @Test
    void systemSchedulerEvictsIdleDynamicValidatorWithoutFurtherTraffic() throws Exception {
        final CountDownLatch evicted = new CountDownLatch(1);
        final var registry = new FhirBundleValidatorRegistry<String, String>(2, Duration.ofMillis(200),
                (key, value, cause) -> {
                    if (cause == RemovalCause.EXPIRED) {
                        evicted.countDown();
                    }
                });
        registry.getOrCreate("v1.9.3", factory);

        // No further calls on the registry: only the scheduler can trigger the expiry.
        assertThat(evicted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.dynamicCount()).isZero();
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> registry(-1, TTL)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDynamic");
        assertThatThrownBy(() -> registry(2, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dynamicTtl");
        assertThatThrownBy(() -> registry(2, Duration.ofSeconds(-5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry(2, null)).isInstanceOf(NullPointerException.class);
    }

    private record Removal(String key, RemovalCause cause) {
    }

    private static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
