package com.app.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.testsupport.TestContainerImages;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pins the Java place name normaliser and the SQL function {@code fn_normalise_place_name} to the
 * same vectors, which the frontend runs as well.
 *
 * <p>The data migration that moved free-text locations into the places registry can only call SQL,
 * and the application can only call Java, so a place created by either must land on the same key or
 * the registry would hold two rows for one name.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PlaceNameNormalisationParityIT {

    private static final String VECTORS = "/text-match/place-name-vectors.json";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private record Vector(String raw, String name) {}

    private final JdbcClient jdbcClient;

    PlaceNameNormalisationParityIT(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void normalise_everyVector_javaMatchesTheContract() throws IOException {
        for (Vector vector : vectors()) {
            assertThat(TextMatchNormalizer.normalizeName(vector.raw()))
                    .as("java for '%s'", vector.raw())
                    .isEqualTo(vector.name());
        }
    }

    @Test
    void normalise_everyVector_sqlMatchesTheContract() throws IOException {
        for (Vector vector : vectors()) {
            String sql =
                    jdbcClient
                            .sql("SELECT fn_normalise_place_name(:raw)")
                            .param("raw", vector.raw())
                            .query(String.class)
                            .single();

            assertThat(sql).as("sql for '%s'", vector.raw()).isEqualTo(vector.name());
        }
    }

    private static List<Vector> vectors() throws IOException {
        try (InputStream stream =
                PlaceNameNormalisationParityIT.class.getResourceAsStream(VECTORS)) {
            assertThat(stream).as("vector file %s", VECTORS).isNotNull();
            return new ObjectMapper().readValue(stream, new TypeReference<List<Vector>>() {});
        }
    }
}
