package org.techbd.fhir.service.engine;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * Holds the FHIR bundle validators of a {@code HapiValidationEngine} in two separate pools.
 *
 * <ul>
 * <li><b>Configured</b> validators (the SHIN-NY packages listed in {@code application.yml}) are built at
 * startup and pinned for the life of the process. They are never evicted, so a burst of requests for other IG
 * versions can no longer push them out.</li>
 * <li><b>Dynamic</b> validators (built on demand for an IG version requested through the request header) live in
 * a small Caffeine cache, bounded by {@code maxDynamic} entries and expired {@code dynamicTtl} after their last
 * access. A scheduler drives expiry, so idle validators are released even when no further request arrives to
 * trigger Caffeine's maintenance.</li>
 * </ul>
 *
 * Setting {@code maxDynamic} to {@code 0} disables dynamic validators: {@link #getOrCreate} then returns
 * {@code null} for any key that is not configured, and the caller falls back to profile-URL matching.
 *
 * @param <K> validator key
 * @param <V> cached validator value
 */
final class FhirBundleValidatorRegistry<K, V> {

    static final int DEFAULT_MAX_DYNAMIC_VALIDATORS = 2;
    static final Duration DEFAULT_DYNAMIC_VALIDATOR_TTL = Duration.ofMinutes(30);

    private final Map<K, V> configuredValidators = new ConcurrentHashMap<>();
    private final Cache<K, V> dynamicValidators;
    private final int maxDynamic;
    private final Duration dynamicTtl;

    /**
     * Production constructor: system ticker, system scheduler (expiry while idle), and removal notifications on
     * Caffeine's default executor.
     */
    FhirBundleValidatorRegistry(int maxDynamic, Duration dynamicTtl, RemovalListener<K, V> removalListener) {
        this(maxDynamic, dynamicTtl, Ticker.systemTicker(), Scheduler.systemScheduler(), null, removalListener);
    }

    /**
     * Constructor that lets tests control time, scheduling and the removal-listener executor.
     *
     * @param executor executor for removal notifications, or {@code null} for Caffeine's default
     */
    FhirBundleValidatorRegistry(
            int maxDynamic,
            Duration dynamicTtl,
            Ticker ticker,
            Scheduler scheduler,
            Executor executor,
            RemovalListener<K, V> removalListener) {
        if (maxDynamic < 0) {
            throw new IllegalArgumentException("maxDynamic must be >= 0 but was " + maxDynamic);
        }
        Objects.requireNonNull(dynamicTtl, "dynamicTtl");
        if (dynamicTtl.isNegative() || dynamicTtl.isZero()) {
            throw new IllegalArgumentException("dynamicTtl must be positive but was " + dynamicTtl);
        }
        this.maxDynamic = maxDynamic;
        this.dynamicTtl = dynamicTtl;
        if (maxDynamic == 0) {
            this.dynamicValidators = null;
            return;
        }
        final Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(maxDynamic)
                .expireAfterAccess(dynamicTtl)
                .ticker(Objects.requireNonNull(ticker, "ticker"))
                .scheduler(Objects.requireNonNull(scheduler, "scheduler"))
                .recordStats();
        if (executor != null) {
            builder.executor(executor);
        }
        this.dynamicValidators = removalListener == null
                ? builder.build()
                : builder.removalListener(removalListener).build();
    }

    /**
     * Builds (once) and pins a configured validator. Configured validators are never evicted.
     */
    V registerConfigured(K key, Function<? super K, ? extends V> factory) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        final V value = configuredValidators.computeIfAbsent(key, factory);
        if (dynamicValidators != null) {
            // A key that has become configured must not also occupy a dynamic slot.
            dynamicValidators.invalidate(key);
        }
        return value;
    }

    /** Returns the configured validator for {@code key}, or {@code null}. */
    V getConfigured(K key) {
        return key == null ? null : configuredValidators.get(key);
    }

    boolean isConfigured(K key) {
        return key != null && configuredValidators.containsKey(key);
    }

    /**
     * Returns the configured validator for {@code key} if there is one. Otherwise it returns the cached dynamic
     * validator, building it with {@code factory} if needed. Returns {@code null} when the key is not configured
     * and dynamic validators are disabled ({@code maxDynamic == 0}).
     */
    V getOrCreate(K key, Function<? super K, ? extends V> factory) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        final V configured = configuredValidators.get(key);
        if (configured != null) {
            return configured;
        }
        if (dynamicValidators == null) {
            return null;
        }
        return dynamicValidators.get(key, factory);
    }

    /** Snapshot of the dynamic validators currently cached (empty if dynamic validators are disabled). */
    Collection<V> dynamicValidators() {
        return dynamicValidators == null ? List.of() : List.copyOf(dynamicValidators.asMap().values());
    }

    int configuredCount() {
        return configuredValidators.size();
    }

    long dynamicCount() {
        return dynamicValidators == null ? 0 : dynamicValidators.estimatedSize();
    }

    CacheStats dynamicStats() {
        return dynamicValidators == null ? CacheStats.empty() : dynamicValidators.stats();
    }

    boolean isDynamicEnabled() {
        return dynamicValidators != null;
    }

    int maxDynamic() {
        return maxDynamic;
    }

    Duration dynamicTtl() {
        return dynamicTtl;
    }

    /** Runs pending Caffeine maintenance (expiry/eviction) now. Mainly for tests. */
    void cleanUp() {
        if (dynamicValidators != null) {
            dynamicValidators.cleanUp();
        }
    }
}
