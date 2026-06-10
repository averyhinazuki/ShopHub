package com.example.shophub;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * Boots the JPA slice against the real MySQL datasource: Flyway applies the
 * migrations (or baselines a pre-Flyway database), then Hibernate's
 * ddl-auto: validate checks every entity mapping against the resulting schema.
 * Context startup succeeding IS the assertion — schema drift fails this test.
 *
 * Requires MySQL only (no Redis/Kafka/MongoDB), so it runs locally and in CI.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaMigrationTest {

    @Test
    void flywayMigrationsMatchEntityMappings() {
        // intentionally empty — see class javadoc
    }
}
