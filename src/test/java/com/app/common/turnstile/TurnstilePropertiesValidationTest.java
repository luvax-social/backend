package com.app.common.turnstile;

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

class TurnstilePropertiesValidationTest {

    @Test
    void bind_siteKeyInsteadOfVerifyUrl_isRejected() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.turnstile.verify-url", "0x4AAAAAAABkMYinukE8nzY");

        assertThatThrownBy(() -> bind(environment)).isInstanceOf(BindException.class);
    }

    @Test
    void bind_blankVerifyUrl_isRejected() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.turnstile.verify-url", " ");

        assertThatThrownBy(() -> bind(environment)).isInstanceOf(BindException.class);
    }

    @Test
    void bind_relativeVerifyUrl_isRejected() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.turnstile.verify-url", "/turnstile/v0/siteverify");

        assertThatThrownBy(() -> bind(environment)).isInstanceOf(BindException.class);
    }

    @Test
    void bind_cloudflareVerifyUrl_succeeds() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(
                "app.turnstile.verify-url",
                "https://challenges.cloudflare.com/turnstile/v0/siteverify");

        assertThat(bind(environment).getVerifyUrl())
                .isEqualTo("https://challenges.cloudflare.com/turnstile/v0/siteverify");
    }

    @Test
    void bind_localStubVerifyUrl_succeeds() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.turnstile.verify-url", "http://localhost:8089/siteverify");

        assertThat(bind(environment).getVerifyUrl()).isEqualTo("http://localhost:8089/siteverify");
    }

    @Test
    void bind_defaults_succeed() {
        assertThat(bind(new MockEnvironment()).getVerifyUrl())
                .isEqualTo("https://challenges.cloudflare.com/turnstile/v0/siteverify");
    }

    @Test
    void turnstileProperties_isAnnotatedWithValidated() {
        // Without @Validated, Spring Boot binds the type but skips every constraint on it, so a
        // malformed verify-url would only surface at the first sign-in.
        assertThat(
                        Arrays.stream(TurnstileProperties.class.getAnnotations())
                                .map(annotation -> annotation.annotationType().getName()))
                .contains("org.springframework.validation.annotation.Validated");
    }

    private static TurnstileProperties bind(MockEnvironment environment) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return new Binder(ConfigurationPropertySources.get(environment))
                .bindOrCreate(
                        "app.turnstile",
                        Bindable.of(TurnstileProperties.class),
                        new ValidationBindHandler(validator));
    }
}
