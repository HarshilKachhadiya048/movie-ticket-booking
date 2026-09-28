package com.harshil.movieticketbooking.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The PostgreSQL instance every integration test runs against.
 *
 * <h2>Why not H2</h2>
 * Almost nothing this system relies on exists in H2's PostgreSQL compatibility
 * mode, and the parts that are missing are exactly the parts worth testing:
 * {@code SELECT ... FOR UPDATE} blocking semantics, {@code SKIP LOCKED},
 * partial and {@code NULLS NOT DISTINCT} unique indexes, {@code TIMESTAMPTZ}
 * behaviour. A green suite on H2 would prove that the code runs, not that the
 * concurrency guarantee holds. The concurrency test in particular is
 * meaningless without a real database, so all of them use one.
 *
 * <h2>One container for the whole run</h2>
 * Started once in a static initialiser and left running for the JVM's
 * lifetime, rather than per class. Ryuk removes it when the JVM exits.
 * Starting a container per test class would add tens of seconds per class for
 * no isolation benefit - {@link DatabaseCleaner} already truncates between
 * tests, and schema migrations run once.
 *
 * <p>The image tag is pinned to the same major version as
 * {@code docker-compose.yml}, because the schema uses PostgreSQL 15+ features
 * ({@code UNIQUE NULLS NOT DISTINCT}) and testing against a different major
 * version would not test what is deployed.
 */
public abstract class PostgresContainerSupport {

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("movie_ticket_booking_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
