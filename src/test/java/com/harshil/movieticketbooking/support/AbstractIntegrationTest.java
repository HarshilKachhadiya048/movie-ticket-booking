package com.harshil.movieticketbooking.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for every integration test.
 * <p>
 * All subclasses share one Spring context and one PostgreSQL container, so the
 * suite pays the startup cost once rather than per class. Isolation comes from
 * truncating between tests instead of from a rollback - see
 * {@link DatabaseCleaner} for why that distinction matters here.
 * <p>
 * Note what is <em>not</em> present: no {@code @Transactional} on this class.
 * Wrapping tests in a transaction would make the seat-hold and payment flows
 * untestable, because the behaviour being verified is what separate
 * transactions see of each other's commits.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
@Import({ TestClockConfiguration.class, TestDataFactory.class, DatabaseCleaner.class })
public abstract class AbstractIntegrationTest extends PostgresContainerSupport {

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected TestDataFactory testData;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    /**
     * Resets both halves of the shared state: the database and the clock.
     * Forgetting the clock would let a test that advanced time past a hold
     * expiry leak that into the next one.
     */
    @BeforeEach
    void resetSharedState() {
        databaseCleaner.clean();
        clock.setInstant(TestClockConfiguration.FIXED_NOW);
    }
}
