package com.app.common.config.openapi;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

/**
 * Declares the 405 response on every operation in the generated document.
 *
 * <p>Unlike the statuses declared by composed annotations such as {@code
 * MalformedBodyErrorResponses}, this one is not a property of any single operation: every route can
 * be addressed with a method it does not support, so declaring it per operation would mean
 * repeating it on all of them. It is declared centrally for that reason, carrying the {@code Allow}
 * header that RFC 9110 requires on a 405 and that the handler sets.
 */
@Configuration
public class MethodNotAllowedResponseConfig {

    private static final String METHOD_NOT_ALLOWED = "405";

    @Bean
    public OperationCustomizer methodNotAllowedResponseCustomizer() {
        return (operation, handlerMethod) -> {
            ApiResponses responses = operation.getResponses();
            if (responses == null || responses.containsKey(METHOD_NOT_ALLOWED)) {
                return operation;
            }
            responses.addApiResponse(METHOD_NOT_ALLOWED, methodNotAllowedResponse());
            return operation;
        };
    }

    private static ApiResponse methodNotAllowedResponse() {
        return new ApiResponse()
                .description("The route exists but does not support this HTTP method")
                .addHeaderObject(
                        HttpHeaders.ALLOW,
                        new Header()
                                .description("The methods this route does support")
                                .schema(new Schema<String>().type("string")))
                .content(
                        new Content()
                                .addMediaType(
                                        MediaType.APPLICATION_JSON_VALUE,
                                        new io.swagger.v3.oas.models.media.MediaType()
                                                .schema(
                                                        new Schema<>()
                                                                .$ref(
                                                                        "#/components/schemas/ApiResponse"))));
    }
}
