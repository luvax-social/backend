package com.app.common.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when a required environment variable is absent and the property that needs it
 * bound the placeholder text instead of a value.
 *
 * <p>A {@code ${VAR}} placeholder with no default is not a fail-fast mechanism on its own.
 * Configuration-properties binding leaves an unresolvable placeholder as its own literal text, so a
 * variable missing from both the environment and {@code .env} yields a property whose value is the
 * string {@code "${VAR}"} and a process that starts normally. The application then serves traffic
 * against those literals: access tokens carry {@code "iss":"${JWT_ISSUER}"} and are accepted,
 * because validation compares the same wrong value it issued, and verification mail carries links
 * containing {@code ${FRONTEND_BASE_URL}} that no recipient can follow. Nothing inside the instance
 * observes the fault.
 *
 * <p>Per-consumer guards did not close this and cannot. Before this class, only {@code JWT_SECRET}
 * and {@code APP_COOKIE_SIGNING_SECRET} failed on absence, and both only because their own
 * placeholder text is shorter than a 32-character length floor written for a different purpose - an
 * accident of how long the variable name is, not a check. One central scan is deliberate: a guard
 * per variable is what left the other nineteen uncovered, and it is the variable added next that
 * would be forgotten.
 *
 * <p>Scoped to {@code prod} because the dev and test profiles resolve these differently on purpose.
 * An integration test excludes the AMQP autoconfiguration and never supplies {@code
 * SPRING_RABBITMQ_USERNAME}, leaving that placeholder legitimately unresolved, so running this
 * check there would fail the suite over a non-defect. A developer checkout boots from {@code .env}
 * and from the defaulted placeholders that {@code application-dev.yml} supplies.
 */
@Component
@Profile("prod")
public class RequiredEnvironmentGuard {

    /**
     * Matches a placeholder that names an environment variable and carries no default. Defaulted
     * placeholders are skipped because they always resolve to something. The upper-snake shape is
     * required so that a library property whose value legitimately contains other {@code ${...}}
     * text is never reported; every placeholder in this application's own configuration uses it.
     */
    private static final Pattern REQUIRED_VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");

    /**
     * Sources holding operator- or JVM-supplied text rather than this application's configuration.
     * Their values are data, so a {@code ${...}} occurring inside one is not a defect, and {@code
     * configurationProperties} only re-exposes the sources already walked here.
     */
    private static final Set<String> IGNORED_SOURCES =
            Set.of(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                    "configurationProperties");

    private static final Logger log = LoggerFactory.getLogger(RequiredEnvironmentGuard.class);

    private final ConfigurableEnvironment environment;

    public RequiredEnvironmentGuard(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void verifyEveryRequiredVariableIsSet() {
        List<String> offenders = findPropertiesBoundToPlaceholderText();
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        if (!offenders.isEmpty()) {
            throw new IllegalStateException(
                    "Required environment variables are not set, so these properties bound their"
                            + " own placeholder text instead of a value: "
                            + String.join("; ", offenders)
                            + ". Every one of them must be supplied by the deployment environment."
                            + " Refusing to start, because the application would otherwise run"
                            + " against the literal ${...} strings and report itself healthy."
                            + " Active profiles are "
                            + activeProfiles
                            + ".");
        }
        log.info("Required environment variables resolved | active profiles: {}", activeProfiles);
    }

    private List<String> findPropertiesBoundToPlaceholderText() {
        Set<String> offenders = new LinkedHashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (IGNORED_SOURCES.contains(source.getName())
                    || !(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String propertyName : enumerable.getPropertyNames()) {
                String variable =
                        unresolvedVariableFor(propertyName, enumerable.getProperty(propertyName));
                if (variable != null) {
                    offenders.add(propertyName + " needs " + variable);
                }
            }
        }
        return List.copyOf(offenders);
    }

    /**
     * Returns the variable {@code propertyName} still depends on, or null when it resolves.
     *
     * @param propertyName the property carrying the placeholder
     * @param rawValue that property's value as written in the source being walked
     * @return the unset variable's name, or null when the property has a real value
     */
    private String unresolvedVariableFor(String propertyName, Object rawValue) {
        if (!(rawValue instanceof String text)) {
            return null;
        }
        Matcher declared = REQUIRED_VARIABLE.matcher(text);
        if (!declared.find()) {
            return null;
        }
        // The raw value comes from one source, but a higher-precedence source may supply the
        // property outright. Resolving the property name rather than this text is what lets an
        // overridden placeholder - spring.rabbitmq.username under a test registry, for example -
        // read as satisfied rather than as an offender.
        String resolved;
        try {
            resolved = environment.getProperty(propertyName);
        } catch (IllegalArgumentException unresolvable) {
            return declared.group(1);
        }
        if (resolved != null && REQUIRED_VARIABLE.matcher(resolved).find()) {
            return declared.group(1);
        }
        return null;
    }
}
