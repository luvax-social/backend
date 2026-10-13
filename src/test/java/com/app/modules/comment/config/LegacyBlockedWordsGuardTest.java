package com.app.modules.comment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LegacyBlockedWordsGuardTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(LegacyBlockedWordsGuard.class);

    @Test
    void startup_indexedBlockedWords_failsAndPointsAtTheAdminHiddenWordsRegistry() {
        runner.withPropertyValues("app.comment.blocked-words[0]=x")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .rootCause()
                                    .hasMessageContaining("app.comment.blocked-words")
                                    .hasMessageContaining("Hidden Words")
                                    .hasMessageContaining("/api/v1/admin/hidden-words");
                        });
    }

    @Test
    void startup_commaSeparatedBlockedWords_fails() {
        runner.withPropertyValues("app.comment.blocked-words=alpha,beta")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startup_noBlockedWordsConfigured_starts() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startup_emptyBlockedWordsValue_starts() {
        runner.withPropertyValues("app.comment.blocked-words=")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
