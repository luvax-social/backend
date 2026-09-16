package com.app.common.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class SecurityPropertiesValidationTest {

    @Test
    void bind_cookieSigningSecretShorterThan32Chars_isRejected() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.security.cookie-signing-secret", "too-short");

        assertThatThrownBy(() -> bind(environment)).isInstanceOf(BindException.class);
    }

    @Test
    void bind_cookieSigningSecretAtLeast32Chars_succeeds() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(
                "app.security.cookie-signing-secret", "a-secret-that-is-long-enough-32ch");

        SecurityProperties properties = bind(environment);

        assertThat(properties.cookieSigningSecret()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(properties.trustedProxyCidrs()).isEmpty();
        assertThat(properties.maxLoginBodyBytes()).isEqualTo(4096);
    }

    @Test
    void securityProperties_isAnnotatedWithValidated() {
        // Without @Validated, Spring Boot binds the type but skips every JSR-303 constraint on it,
        // so the 32-character floor on the HMAC signing secret would never be enforced at startup.
        assertThat(
                        Arrays.stream(SecurityProperties.class.getAnnotations())
                                .map(annotation -> annotation.annotationType().getName()))
                .contains("org.springframework.validation.annotation.Validated");
    }

    private static SecurityProperties bind(MockEnvironment environment) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return new Binder(ConfigurationPropertySources.get(environment))
                .bindOrCreate(
                        "app.security",
                        Bindable.of(SecurityProperties.class),
                        new ValidationBindHandler(validator));
    }
}
