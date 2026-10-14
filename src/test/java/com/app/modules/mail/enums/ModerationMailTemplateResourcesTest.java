package com.app.modules.mail.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;

class ModerationMailTemplateResourcesTest {

    @ParameterizedTest
    @EnumSource(ModerationMailTemplate.class)
    void templatePath_everyModerationNotice_resolvesToAnHtmlTemplate(
            ModerationMailTemplate template) {
        assertThat(
                        new ClassPathResource("templates/" + template.getTemplatePath() + ".html")
                                .exists())
                .as("template for %s", template)
                .isTrue();
    }
}
