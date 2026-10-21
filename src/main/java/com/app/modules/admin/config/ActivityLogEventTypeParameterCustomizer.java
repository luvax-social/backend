package com.app.modules.admin.config;

import java.util.ArrayList;
import java.util.List;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import com.app.modules.admin.controller.AdminUserEventController;
import com.app.modules.recommendation.enums.UserEventType;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;

/**
 * Removes the private preference signals from the activity log's {@code eventType} filter in the
 * generated OpenAPI document.
 *
 * <p>The parameter is typed as {@link UserEventType}, so the document would otherwise enumerate
 * every value of the enum, including the seven that the log refuses. Deriving the list from the
 * enum keeps a value added later offered without an edit here.
 */
@Component
public class ActivityLogEventTypeParameterCustomizer implements OperationCustomizer {

    private static final String PARAMETER_NAME = "eventType";

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (!AdminUserEventController.class.equals(handlerMethod.getBeanType())
                || operation.getParameters() == null) {
            return operation;
        }
        for (Parameter parameter : operation.getParameters()) {
            Schema schema = parameter.getSchema();
            if (PARAMETER_NAME.equals(parameter.getName())
                    && schema != null
                    && schema.getEnum() != null) {
                List<Object> offered = new ArrayList<>();
                for (UserEventType type : UserEventType.values()) {
                    if (!type.isPrivatePreferenceSignal()) {
                        offered.add(type.toJson());
                    }
                }
                schema.setEnum(offered);
            }
        }
        return operation;
    }
}
