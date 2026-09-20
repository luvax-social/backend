package com.app.modules.mail.util;

import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.app.modules.mail.enums.MailTemplate;
import com.app.modules.mail.enums.ModerationMailTemplate;
import com.app.modules.mail.enums.SupportMailTemplate;

/**
 * Renders Thymeleaf email templates into HTML strings.
 *
 * <p>Stateless — each call constructs a fresh {@link Context} scoped to {@link Locale#ENGLISH}. Has
 * no knowledge of how or to whom the rendered output is sent.
 */
@Component
public class MailTemplateRenderer {

    private final TemplateEngine templateEngine;

    public MailTemplateRenderer(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /**
     * Renders the given template with the supplied variables.
     *
     * @param template the template descriptor holding the Thymeleaf path
     * @param variables key-value pairs bound as Thymeleaf context variables
     * @return rendered HTML string
     */
    public String render(MailTemplate template, Map<String, Object> variables) {
        return process(template.getTemplatePath(), variables);
    }

    /**
     * Renders a moderation notice template with the supplied variables.
     *
     * @param template the moderation template descriptor holding the Thymeleaf path
     * @param variables key-value pairs bound as Thymeleaf context variables
     * @return rendered HTML string
     */
    public String render(ModerationMailTemplate template, Map<String, Object> variables) {
        return process(template.getTemplatePath(), variables);
    }

    /**
     * Renders a support template with the supplied variables.
     *
     * @param template the support template descriptor holding the Thymeleaf path
     * @param variables key-value pairs bound as Thymeleaf context variables
     * @return rendered HTML string
     */
    public String render(SupportMailTemplate template, Map<String, Object> variables) {
        return process(template.getTemplatePath(), variables);
    }

    private String process(String templatePath, Map<String, Object> variables) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariables(variables);
        return templateEngine.process(templatePath, context);
    }
}
