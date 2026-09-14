package com.app.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.validation.ConstraintViolationException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.app.common.enums.ApiErrorCode;
import com.app.common.response.ApiResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Translates framework-level and generic runtime exceptions into a uniform {@link ApiResponse}
 * envelope. Domain-specific exceptions must be converted to {@link AppException} at the service
 * layer before they reach this handler.
 *
 * <p>One class of malformed request never reaches here: a request line or header exceeding Tomcat's
 * {@code max-http-request-header-size} is rejected by the connector itself before Spring MVC
 * dispatch begins, so the client receives Tomcat's own HTML error page instead of this envelope.
 * This is accepted rather than worked around, since matching JSON output for it would require a
 * connector-level valve outside the servlet exception-handling path this class covers.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<?>> handleAppException(AppException ex) {
        // DEBUG, not WARN: a domain rejection is an expected outcome, it is already fully
        // described by the code in the response, and an unauthenticated caller can generate
        // these at will. The code alone is what a diagnostic needs; the details map can carry
        // caller-supplied content and is deliberately left out.
        log.debug("Domain rejection | code: {}", ex.getErrorCode());
        return ResponseEntity.status(ex.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ex.getErrorCode(), ex.getMessage(), ex.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, String> violatedConstraints = new LinkedHashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(
                        fe -> {
                            errors.put(fe.getField(), fe.getDefaultMessage());
                            violatedConstraints.put(fe.getField(), fe.getCode());
                        });
        // Field names and the constraint each one violated, never the submitted value. This
        // replaces the diagnostic that logback-spring.xml now silences on
        // ExceptionHandlerExceptionResolver, which rendered the BindingResult in full and wrote
        // the rejected password to the log in cleartext.
        log.warn("Request validation failed | violated constraints: {}", violatedConstraints);
        return ResponseEntity.status(ApiErrorCode.VALIDATION_ERROR.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ApiErrorCode.VALIDATION_ERROR, null, errors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<?>> handleHandlerMethodValidation(
            HandlerMethodValidationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getParameterValidationResults()
                .forEach(
                        result ->
                                result.getResolvableErrors()
                                        .forEach(
                                                error ->
                                                        errors.put(
                                                                result.getMethodParameter()
                                                                        .getParameterName(),
                                                                error.getDefaultMessage())));
        // Parameter names only, for the same reason as handleValidation above: the resolved
        // value is exactly what must not reach the log.
        log.warn("Request parameter validation failed | parameters: {}", errors.keySet());
        return ResponseEntity.status(ApiErrorCode.VALIDATION_ERROR.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ApiErrorCode.VALIDATION_ERROR, null, errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleConstraintViolation(
            ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations()
                .forEach(
                        cv -> {
                            String path = cv.getPropertyPath().toString();
                            errors.put(path, cv.getMessage());
                        });
        // Property paths only. ConstraintViolation.toString() carries the invalid value, so the
        // violation object itself is never handed to the logger.
        log.warn("Constraint violation | properties: {}", errors.keySet());
        return ResponseEntity.status(ApiErrorCode.VALIDATION_ERROR.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ApiErrorCode.VALIDATION_ERROR, null, errors));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<?>> handleAccessDenied(AccessDeniedException ex) {
        // An authorization denial is worth a retained record; it carries no caller payload.
        log.warn("Access denied on an authenticated request");
        return ResponseEntity.status(ApiErrorCode.FORBIDDEN.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ApiErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<?>> handleAuthentication(AuthenticationException ex) {
        // DEBUG: every expired access token produces one of these, so WARN would bury the
        // authorization denials above under routine session expiry.
        log.debug("Authentication failed on a protected route");
        return ResponseEntity.status(ApiErrorCode.UNAUTHORIZED.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ApiErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNoResource(NoResourceFoundException ex) {
        // DEBUG: scanners and browser prefetches generate these continuously against any public
        // deployment, and the path is caller-controlled.
        log.debug("No handler for the requested path");
        return ResponseEntity.status(ApiErrorCode.NOT_FOUND.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ApiErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<?>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        // Client input error, not a server fault: WARN without a stack trace. The method is one of
        // a fixed set, so echoing it back leaks nothing.
        log.warn("HTTP method {} is not supported for this route", ex.getMethod());
        String message = "HTTP method not supported: " + ex.getMethod();
        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(ApiErrorCode.METHOD_NOT_ALLOWED.getHttpStatus())
                        .contentType(MediaType.APPLICATION_JSON);
        // RFC 9110 requires a 405 to name the methods the route does support, and a client cannot
        // correct itself without it. Absent only when the dispatcher could not determine them.
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            response.allow(supported.toArray(new HttpMethod[0]));
        }
        return response.body(ApiResponse.failure(ApiErrorCode.METHOD_NOT_ALLOWED, message, null));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<?>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        // Client input error, not a server fault: WARN without a stack trace keeps an
        // unauthenticated caller from flooding the ERROR log, and the offending value is never
        // echoed back.
        log.warn("Type mismatch on request parameter '{}'", ex.getName());
        return ResponseEntity.status(ApiErrorCode.BAD_REQUEST.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ApiErrorCode.BAD_REQUEST));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<?>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex) {
        // Client input error, not a server fault: WARN without a stack trace.
        log.warn("Unsupported Content-Type: {}", ex.getContentType());
        return ResponseEntity.status(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<?>> handleNotAcceptable(
            HttpMediaTypeNotAcceptableException ex) {
        // The Content-Type is set explicitly rather than negotiated: the caller's Accept header
        // is exactly what this handler exists to reject, so honouring it here would recreate the
        // failure the handler is supposed to resolve. Writing JSON regardless is what lets this
        // response reach the caller through the normal advice mechanism instead of falling
        // through to Spring's response.sendError(...), which re-enters the security filter chain
        // on the /error ERROR dispatch and answers 401 instead of 406.
        log.warn("No acceptable media type for Accept header");
        return ResponseEntity.status(ApiErrorCode.NOT_ACCEPTABLE.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ApiErrorCode.NOT_ACCEPTABLE));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<?>> handleMissingParameter(
            MissingServletRequestParameterException ex) {
        // Client input error, not a server fault: WARN without a stack trace.
        log.warn("Missing required request parameter '{}'", ex.getParameterName());
        return ResponseEntity.status(ApiErrorCode.MISSING_REQUIRED_PARAMETER.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ApiErrorCode.MISSING_REQUIRED_PARAMETER));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleMalformedRequestBody(
            HttpMessageNotReadableException ex) {
        // Every cause this handler sees - an unrecognised property, invalid JSON syntax, a
        // missing body, a wrong root type, or an invalid enum value - reaches Spring MVC as this
        // one exception type, so one handler covers all of them. Client input error, not a server
        // fault: WARN without a stack trace, and never echo the cause's message, which names the
        // rejected property and the fully qualified target DTO and would otherwise hand an
        // unauthenticated caller a schema-enumeration oracle over every request DTO.
        Throwable cause = ex.getCause();
        log.warn(
                "Malformed request body, cause: {}",
                cause == null ? "none" : cause.getClass().getSimpleName());
        return ResponseEntity.status(ApiErrorCode.MALFORMED_REQUEST_BODY.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ApiErrorCode.MALFORMED_REQUEST_BODY));
    }

    /**
     * Domain codes for the unique constraints that guard a race the service cannot pre-empt.
     *
     * <p>A service-layer check answers the ordinary case with a named code, but two transactions
     * can both pass that check before either commits, and then the constraint is what refuses the
     * second. Without this mapping the loser received {@code BAD_REQUEST} alongside HTTP 409 - an
     * envelope whose own code contradicted its status line - and staff surfaces that render the
     * message showed "the request conflicts with an existing resource" instead of saying what
     * actually happened.
     *
     * <p>Keyed on constraint name rather than caught per service, because catching it at each call
     * site is how the generic answer spread in the first place.
     */
    // PostgreSQL names the constraint in its own message as: violates unique constraint "name".
    // Anchored to that wording so the name is read from where it is, not found anywhere in the
    // statement text the message also carries.
    private static final Pattern CONSTRAINT_NAME =
            Pattern.compile("violates [a-z ]*constraint \"([^\"]+)\"");

    private static final Map<String, ApiErrorCode> CONSTRAINT_ERROR_CODES =
            Map.of(
                    "uq_user_verifications_active", ApiErrorCode.VERIFICATION_ALREADY_VERIFIED,
                    "uq_support_tickets_one_open_support_per_user",
                            ApiErrorCode.SUPPORT_TICKET_ALREADY_OPEN,
                    "uq_support_tickets_one_open_verification_per_user",
                            ApiErrorCode.SUPPORT_TICKET_ALREADY_OPEN);

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleDataIntegrityViolation(
            DataIntegrityViolationException ex) {
        String cause = ex.getMostSpecificCause().getMessage();
        log.warn("Database constraint violation: {}", cause);

        ApiErrorCode mapped = mappedConstraintCode(constraintNameOf(ex, cause));
        if (mapped != null) {
            return ResponseEntity.status(mapped.getHttpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResponse.failure(mapped));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        ApiResponse.failure(
                                ApiErrorCode.BAD_REQUEST,
                                "The request conflicts with an existing resource",
                                null));
    }

    /**
     * The name of the constraint that was violated.
     *
     * <p>Taken from Hibernate's structured field where there is one, because the driver's message
     * is not only the constraint name: it carries the failing statement and a {@code DETAIL} line
     * as well. Matching a mapped name anywhere in that text maps a different constraint's violation
     * to the wrong domain code as soon as the statement text happens to mention one - an insert
     * naming a column or an index in its own SQL is enough.
     *
     * <p>Falls back to reading the quoted name out of PostgreSQL's own {@code violates ...
     * constraint "name"} wording, which is still a parse but is anchored to where the name is
     * rather than scanning the whole message. The driver is a runtime dependency, so its exception
     * type cannot be named here at compile time; this is the same information without the coupling.
     *
     * @param ex the violation as Spring translated it
     * @param causeMessage the most specific cause's message, may be null
     * @return the constraint name, or null when neither source yields one
     */
    private static String constraintNameOf(
            DataIntegrityViolationException ex, String causeMessage) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }
        if (causeMessage == null) {
            return null;
        }
        Matcher matcher = CONSTRAINT_NAME.matcher(causeMessage);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Finds the domain code for the named constraint, if it has one.
     *
     * <p>Matched by exact name rather than by substring, so a constraint whose name merely contains
     * a mapped one cannot borrow its meaning. A name that is not mapped falls through to the
     * generic conflict, which is the correct answer for a constraint nobody has assigned a meaning
     * to.
     *
     * @param constraintName the violated constraint's name, may be null
     * @return the mapped code, or null when the constraint is unknown
     */
    private static ApiErrorCode mappedConstraintCode(String constraintName) {
        if (constraintName == null) {
            return null;
        }
        for (Map.Entry<String, ApiErrorCode> entry : CONSTRAINT_ERROR_CODES.entrySet()) {
            if (constraintName.equals(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleUnknown(Exception ex) {
        log.error("Unhandled exception reached global handler", ex);
        return ResponseEntity.status(ApiErrorCode.INTERNAL_ERROR.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ApiErrorCode.INTERNAL_ERROR));
    }
}
