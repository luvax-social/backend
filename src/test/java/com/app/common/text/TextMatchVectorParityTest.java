package com.app.common.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Keeps the text match vectors the backend tests run identical to the copy the frontend tests run,
 * so both implementations of the normalisation contract are held to the same cases.
 */
class TextMatchVectorParityTest {

    private static final Path BACKEND_COPY =
            Path.of("src", "test", "resources", "text-match", "text-match-vectors.json");
    private static final Path FRONTEND_COPY =
            Path.of("..", "frontend", "tests", "unit", "fixtures", "text-match-vectors.json");

    @Test
    void vectors_backendCopy_isByteIdenticalToTheFrontendCopy() throws IOException {
        assumeTrue(Files.exists(FRONTEND_COPY), "frontend checkout not present beside backend");

        assertThat(Files.readAllBytes(BACKEND_COPY)).isEqualTo(Files.readAllBytes(FRONTEND_COPY));
    }
}
