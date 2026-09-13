package com.app.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class RequiredEnvironmentGuardTest {

    @Test
    void verifyEveryRequiredVariableIsSet_variableAbsent_throwsNamingPropertyAndVariable() {
        StandardEnvironment environment =
                environmentWith(Map.of("app.jwt.issuer", "${JWT_ISSUER}"));

        assertThatThrownBy(() -> guardFor(environment).verifyEveryRequiredVariableIsSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.jwt.issuer")
                .hasMessageContaining("JWT_ISSUER");
    }

    @Test
    void verifyEveryRequiredVariableIsSet_severalVariablesAbsent_namesEveryOffender() {
        StandardEnvironment environment =
                environmentWith(
                        Map.of(
                                "app.jwt.issuer", "${JWT_ISSUER}",
                                "app.cors.allowed-origins", "${CORS_ALLOWED_ORIGINS}"));

        assertThatThrownBy(() -> guardFor(environment).verifyEveryRequiredVariableIsSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_ISSUER")
                .hasMessageContaining("CORS_ALLOWED_ORIGINS");
    }

    @Test
    void verifyEveryRequiredVariableIsSet_variableSupplied_returnsNormally() {
        StandardEnvironment environment =
                environmentWith(Map.of("app.jwt.issuer", "${JWT_ISSUER}"));
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "variables", Map.of("JWT_ISSUER", "https://issuer.test")));

        assertThatCode(() -> guardFor(environment).verifyEveryRequiredVariableIsSet())
                .doesNotThrowAnyException();
    }

    @Test
    void
            verifyEveryRequiredVariableIsSet_propertyOverriddenByHigherPrecedenceSource_returnsNormally() {
        // The AMQP placeholders are written in application.yaml but supplied outright by a test
        // registry, which is the shape that must not be reported as an offender.
        StandardEnvironment environment =
                environmentWith(Map.of("spring.rabbitmq.username", "${SPRING_RABBITMQ_USERNAME}"));
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "registry", Map.of("spring.rabbitmq.username", "guest")));

        assertThatCode(() -> guardFor(environment).verifyEveryRequiredVariableIsSet())
                .doesNotThrowAnyException();
    }

    @Test
    void verifyEveryRequiredVariableIsSet_placeholderCarriesDefault_returnsNormally() {
        StandardEnvironment environment =
                environmentWith(Map.of("app.mail.transport", "${APP_MAIL_TRANSPORT:resend}"));

        assertThatCode(() -> guardFor(environment).verifyEveryRequiredVariableIsSet())
                .doesNotThrowAnyException();
    }

    @Test
    void verifyEveryRequiredVariableIsSet_placeholderIsNotAnEnvironmentVariable_returnsNormally() {
        // Lowercase placeholders belong to library-supplied values such as log patterns. They are
        // outside this guard's contract, and resolving one would raise an unrelated failure.
        StandardEnvironment environment =
                environmentWith(Map.of("logging.pattern.console", "${some.library.pattern}"));

        assertThatCode(() -> guardFor(environment).verifyEveryRequiredVariableIsSet())
                .doesNotThrowAnyException();
    }

    private static RequiredEnvironmentGuard guardFor(StandardEnvironment environment) {
        return new RequiredEnvironmentGuard(environment);
    }

    private static StandardEnvironment environmentWith(Map<String, String> properties) {
        Map<String, Object> source = new HashMap<>(properties);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("configuration", source));
        return environment;
    }
}
