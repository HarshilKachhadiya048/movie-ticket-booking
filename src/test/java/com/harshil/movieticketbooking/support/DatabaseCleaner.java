package com.harshil.movieticketbooking.support;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Truncates every application table between tests.
 * <p>
 * Rolling each test back in a transaction would be cheaper, but it cannot work
 * here: the concurrency and payment tests need <em>committed</em> state,
 * because the behaviour under test is what one transaction sees of another's
 * commits. A test-managed transaction would also hide the real transaction
 * boundaries, which for this system is precisely what is being verified.
 * <p>
 * The table list is read from the catalogue rather than hard-coded, so a new
 * migration cannot leave a stale table leaking rows between tests.
 * {@code CASCADE} lets one statement handle the foreign key graph.
 */
@TestComponent
@RequiredArgsConstructor
public class DatabaseCleaner {

    private final JdbcTemplate jdbcTemplate;

    public void clean() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT tablename
                FROM pg_tables
                WHERE schemaname = 'public'
                  AND tablename <> 'flyway_schema_history'
                """, String.class);
        if (tables.isEmpty()) {
            return;
        }
        jdbcTemplate.execute("TRUNCATE TABLE %s RESTART IDENTITY CASCADE"
                .formatted(String.join(", ", tables)));
    }
}
