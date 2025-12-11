package org.techbd.corelib.config;

import java.sql.Connection;
import java.util.List;

import javax.sql.DataSource;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.techbd.corelib.config.Configuration;

/**
 * Dual DataSource Configuration for jOOQ.
 * 
 * Provides separate read and write DataSources, connection providers, and DSLContext beans.
 * Use @Qualifier("writeDsl") for write operations and @Qualifier("readDsl") for read operations.
 * 
 * Configuration properties:
 * - org.techbd.udi.prime.jdbc.write.* (primary write database)
 * - org.techbd.udi.prime.jdbc.read.* (read replica database)
 * 
 * Environment variables follow the pattern: {PROFILE}_TECHBD_UDI_DS_PRIME_JDBC_{READ|WRITE}_URL
 */
@org.springframework.context.annotation.Configuration
@EnableTransactionManagement
public class CoreUdiDualDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(CoreUdiDualDataSourceConfig.class);

    @Autowired
    private Environment environment;

    // ============================================================================
    // WRITE DATABASE BEANS
    // ============================================================================

    /**
     * Primary (Write) DataSource.
     * Configured from org.techbd.udi.prime.jdbc.write.* properties.
     */
    @Bean("writeDataSource")
    @Primary
    @Lazy
    @ConditionalOnProperty(name = "org.techbd.udi.prime.jdbc.write.url")
    @ConfigurationProperties(prefix = "org.techbd.udi.prime.jdbc.write")
    public DataSource writeDataSource() {
        String jdbcUrl = environment.getProperty("org.techbd.udi.prime.jdbc.write.url");
        String username = environment.getProperty("org.techbd.udi.prime.jdbc.write.username");
        String driver = environment.getProperty("org.techbd.udi.prime.jdbc.write.driverClassName");

        log.info("Initializing Write (Primary) DataSource...");
        log.info("JDBC URL      : {}", jdbcUrl != null ? jdbcUrl : "MISSING");
        log.info("Username      : {}", username != null ? username : "MISSING");
        log.info("Driver        : {}", driver != null ? driver : "MISSING");

        String activeProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        String jdbcEnvVarName = (activeProfile != null ? activeProfile.toUpperCase() : "DEFAULT")
                + "_TECHBD_UDI_DS_PRIME_JDBC_WRITE_URL";
        String jdbcUrl1 = System.getenv(jdbcEnvVarName);

        log.info("Checking environment variable: {}", jdbcEnvVarName);
        log.info("Resolved JDBC URL from ENV: {}", jdbcUrl1 != null ? jdbcUrl1 : "NOT SET");

        DataSource ds = DataSourceBuilder.create().build();
        log.info("Write DataSource created: {}", ds.getClass().getName());

        return ds;
    }

    /**
     * Read Replica DataSource.
     * Configured from org.techbd.udi.prime.jdbc.read.* properties.
     */
    @Bean("readDataSource")
    @Lazy
    @ConditionalOnProperty(name = "org.techbd.udi.prime.jdbc.read.url")
    @ConfigurationProperties(prefix = "org.techbd.udi.prime.jdbc.read")
    public DataSource readDataSource() {
        String jdbcUrl = environment.getProperty("org.techbd.udi.prime.jdbc.read.url");
        String username = environment.getProperty("org.techbd.udi.prime.jdbc.read.username");
        String driver = environment.getProperty("org.techbd.udi.prime.jdbc.read.driverClassName");

        log.info("Initializing Read (Replica) DataSource...");
        log.info("JDBC URL      : {}", jdbcUrl != null ? jdbcUrl : "MISSING");
        log.info("Username      : {}", username != null ? username : "MISSING");
        log.info("Driver        : {}", driver != null ? driver : "MISSING");

        String activeProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        String jdbcEnvVarName = (activeProfile != null ? activeProfile.toUpperCase() : "DEFAULT")
                + "_TECHBD_UDI_DS_PRIME_JDBC_READ_URL";
        String jdbcUrl1 = System.getenv(jdbcEnvVarName);

        log.info("Checking environment variable: {}", jdbcEnvVarName);
        log.info("Resolved JDBC URL from ENV: {}", jdbcUrl1 != null ? jdbcUrl1 : "NOT SET");

        DataSource ds = DataSourceBuilder.create().build();
        log.info("Read DataSource created: {}", ds.getClass().getName());

        return ds;
    }

    // ============================================================================
    // CONNECTION PROVIDERS
    // ============================================================================

    /**
     * Write DataSource Connection Provider for jOOQ.
     */
    @Bean("writeConnectionProvider")
    @Primary
    public DataSourceConnectionProvider writeConnectionProvider() {
        return new DataSourceConnectionProvider(
                new TransactionAwareDataSourceProxy(writeDataSource()));
    }

    /**
     * Read DataSource Connection Provider for jOOQ.
     */
    @Bean("readConnectionProvider")
    public DataSourceConnectionProvider readConnectionProvider() {
        return new DataSourceConnectionProvider(
                new TransactionAwareDataSourceProxy(readDataSource()));
    }

    // ============================================================================
    // JOOQ CONFIGURATIONS
    // ============================================================================

    /**
     * Write jOOQ Configuration.
     */
    @Bean("writeJooqConfiguration")
    @Primary
    public org.jooq.Configuration writeJooqConfiguration() {
        log.info("Setting up Write jOOQ Configuration...");
        final var jooqConfiguration = new DefaultConfiguration();
        jooqConfiguration.set(writeConnectionProvider());
        jooqConfiguration.setSQLDialect(SQLDialect.POSTGRES);
        log.info("Write jOOQ Configuration set with dialect: {} and connectionProvider: {}",
                SQLDialect.POSTGRES, writeConnectionProvider().getClass().getSimpleName());
        return jooqConfiguration;
    }

    /**
     * Read jOOQ Configuration.
     */
    @Bean("readJooqConfiguration")
    public org.jooq.Configuration readJooqConfiguration() {
        log.info("Setting up Read jOOQ Configuration...");
        final var jooqConfiguration = new DefaultConfiguration();
        jooqConfiguration.set(readConnectionProvider());
        jooqConfiguration.setSQLDialect(SQLDialect.POSTGRES);
        log.info("Read jOOQ Configuration set with dialect: {} and connectionProvider: {}",
                SQLDialect.POSTGRES, readConnectionProvider().getClass().getSimpleName());
        return jooqConfiguration;
    }

    // ============================================================================
    // DSL CONTEXTS
    // ============================================================================

    /**
     * Write DSLContext for performing INSERT, UPDATE, DELETE operations.
     * Use @Autowired @Qualifier("writeDsl") in your services/repositories.
     */
    @Bean("writeDsl")
    @Primary
    public DSLContext writeDsl() {
        log.info("Initializing Write DSLContext...");
        DSLContext context = new DefaultDSLContext(writeJooqConfiguration());
        log.info("Write DSLContext created successfully with SQL dialect: {}", SQLDialect.POSTGRES);
        return context;
    }

    /**
     * Read DSLContext for performing SELECT operations.
     * Use @Autowired @Qualifier("readDsl") in your services/repositories.
     */
    @Bean("readDsl")
    public DSLContext readDsl() {
        log.info("Initializing Read DSLContext...");
        DSLContext context = new DefaultDSLContext(readJooqConfiguration());
        log.info("Read DSLContext created successfully with SQL dialect: {}", SQLDialect.POSTGRES);
        return context;
    }

    // ============================================================================
    // TRANSACTION MANAGERS
    // ============================================================================

    /**
     * Transaction Manager for Write operations.
     * Use @Transactional("writeTransactionManager") on methods requiring write transactions.
     */
    @Bean("writeTransactionManager")
    @Primary
    public DataSourceTransactionManager writeTransactionManager() {
        log.info("Initializing Write TransactionManager...");
        DataSourceTransactionManager tm = new DataSourceTransactionManager(writeDataSource());
        log.info("Write TransactionManager created");
        return tm;
    }

    /**
     * Transaction Manager for Read operations (optional; typically read-only).
     * Provided for completeness if read-only transactions need explicit isolation levels.
     */
    @Bean("readTransactionManager")
    public DataSourceTransactionManager readTransactionManager() {
        log.info("Initializing Read TransactionManager...");
        DataSourceTransactionManager tm = new DataSourceTransactionManager(readDataSource());
        log.info("Read TransactionManager created");
        return tm;
    }

    // ============================================================================
    // HEALTH CHECKS
    // ============================================================================

    public record DataSourceHealthCheckResult(DataSource dataSrc, Exception error, Environment environment,
            String... expected) {
        public boolean isAlive() {
            return error == null;
        }

        public List<String> expectedConf() {
            return Configuration.checkProperties(environment, expected);
        }
    }

    /**
     * Health check for Write DataSource.
     */
    public DataSourceHealthCheckResult writeDataSourceHealth() {
        log.info("Running health check for Write DataSource");

        String activeProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        if (activeProfile == null || activeProfile.isBlank()) {
            log.warn("SPRING_PROFILES_ACTIVE is not set as an environment variable!");
        } else {
            log.info("Active Profile (from ENV): {}", activeProfile);
        }

        String jdbcEnvVarName = (activeProfile != null ? activeProfile.toUpperCase() : "DEFAULT")
                + "_TECHBD_UDI_DS_PRIME_JDBC_READ_URL";
        String jdbcUrl = System.getenv(jdbcEnvVarName);

        log.info("Checking environment variable: {}", jdbcEnvVarName);
        log.info("Resolved JDBC URL from ENV: {}", jdbcUrl != null ? jdbcUrl : "MISSING");

        final var ds = writeDataSource();
        try (Connection connection = ds.getConnection()) {
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();
            log.info("Write DataSource connection successful (Catalog: {}, Schema: {}).", catalog, schema);

            return new DataSourceHealthCheckResult(ds, null, environment, jdbcEnvVarName);
        } catch (Exception e) {
            log.error("Write DataSource connection failed!");
            log.error("Reason: {}", e.getMessage(), e);
            log.error("Environment variable used: {}", jdbcEnvVarName);
            return new DataSourceHealthCheckResult(null, e, environment, jdbcEnvVarName);
        }
    }

    /**
     * Health check for Read DataSource.
     */
    public DataSourceHealthCheckResult readDataSourceHealth() {
        log.info("Running health check for Read DataSource");

        String activeProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        if (activeProfile == null || activeProfile.isBlank()) {
            log.warn("SPRING_PROFILES_ACTIVE is not set as an environment variable!");
        } else {
            log.info("Active Profile (from ENV): {}", activeProfile);
        }

        String jdbcEnvVarName = (activeProfile != null ? activeProfile.toUpperCase() : "DEFAULT")
                + "_TECHBD_UDI_DS_PRIME_JDBC_READ_URL";
        String jdbcUrl = System.getenv(jdbcEnvVarName);

        log.info("Checking environment variable: {}", jdbcEnvVarName);
        log.info("Resolved JDBC URL from ENV: {}", jdbcUrl != null ? jdbcUrl : "MISSING");

        final var ds = readDataSource();
        try (Connection connection = ds.getConnection()) {
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();
            log.info("Read DataSource connection successful (Catalog: {}, Schema: {}).", catalog, schema);

            return new DataSourceHealthCheckResult(ds, null, environment, jdbcEnvVarName);
        } catch (Exception e) {
            log.error("Read DataSource connection failed!");
            log.error("Reason: {}", e.getMessage(), e);
            log.error("Environment variable used: {}", jdbcEnvVarName);
            return new DataSourceHealthCheckResult(null, e, environment, jdbcEnvVarName);
        }
    }
}
