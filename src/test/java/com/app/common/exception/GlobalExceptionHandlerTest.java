package com.app.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.app.common.enums.ApiErrorCode;
import com.app.common.response.ApiResponse;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void appException_propagatesHttpStatusAndErrorCode() {
        AppException ex = new AppException(ApiErrorCode.NOT_FOUND, "user not found");

        ResponseEntity<ApiResponse<?>> response = handler.handleAppException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getCode()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().getMessage()).isEqualTo("user not found");
    }

    @Test
    void appException_usesDefaultMessageWhenNoCustomMessage() {
        AppException ex = new AppException(ApiErrorCode.FORBIDDEN);

        ResponseEntity<ApiResponse<?>> response = handler.handleAppException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getMessage())
                .isEqualTo(ApiErrorCode.FORBIDDEN.getDefaultMessage());
    }

    @Test
    void validationException_returnsValidationErrorCodeWithFieldErrorsMap() {
        FieldError emailError = new FieldError("obj", "email", "must be a well-formed email");
        FieldError nameError = new FieldError("obj", "username", "must not be blank");
        BindingResult binding = mock(BindingResult.class);
        when(binding.getFieldErrors()).thenReturn(List.of(emailError, nameError));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(binding);

        ResponseEntity<ApiResponse<?>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
        @SuppressWarnings("unchecked")
        Map<String, String> data = (Map<String, String>) response.getBody().getData();
        assertThat(data).containsEntry("email", "must be a well-formed email");
        assertThat(data).containsEntry("username", "must not be blank");
    }

    @Test
    void accessDeniedException_returnsForbidden() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleAccessDenied(new AccessDeniedException("forbidden"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getCode()).isEqualTo("FORBIDDEN");
    }

    @Test
    void unknownException_returnsInternalError() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleUnknown(new RuntimeException("unexpected"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @SuppressWarnings("unchecked")
    void constraintViolationException_returnsValidationErrorWithFieldPaths() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("email");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be blank");

        ResponseEntity<ApiResponse<?>> response =
                handler.handleConstraintViolation(
                        new ConstraintViolationException(Set.of(violation)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
        Map<String, String> data = (Map<String, String>) response.getBody().getData();
        assertThat(data).containsEntry("email", "must not be blank");
    }

    @Test
    void authenticationException_returnsUnauthorized() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleAuthentication(new BadCredentialsException("bad credentials"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getCode()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void noResourceFoundException_returnsNotFound() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleNoResource(
                        new NoResourceFoundException(HttpMethod.GET, "/missing", "missing"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void httpMethodNotSupportedException_returnsMethodNotAllowed() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleMethodNotSupported(
                        new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().getCode()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().getMessage()).contains("DELETE");
    }

    @Test
    void httpMethodNotSupportedException_withKnownAlternatives_namesThemInTheAllowHeader() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleMethodNotSupported(
                        new HttpRequestMethodNotSupportedException(
                                "DELETE", java.util.List.of("GET", "PATCH")));

        // RFC 9110 makes Allow mandatory on a 405; without it a client cannot correct itself.
        assertThat(response.getHeaders().getAllow())
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.PATCH);
    }

    @Test
    void methodArgumentTypeMismatchException_returnsBadRequest() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("userId");

        ResponseEntity<ApiResponse<?>> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getCode()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void dataIntegrityViolationException_returnsConflict() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleDataIntegrityViolation(
                        new DataIntegrityViolationException(
                                "duplicate key", new RuntimeException("duplicate key value")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().getMessage()).contains("conflicts");
    }

    // A service check answers the ordinary case with a named code, but two transactions can both
    // pass it before either commits and then the constraint refuses the second. The loser used to
    // receive BAD_REQUEST alongside HTTP 409 - an envelope contradicting its own status line - and
    // staff surfaces rendered "the request conflicts with an existing resource" to a moderator.
    @ParameterizedTest
    @CsvSource({
        "uq_user_verifications_active,VERIFICATION_ALREADY_VERIFIED",
        "uq_support_tickets_one_open_support_per_user,SUPPORT_TICKET_ALREADY_OPEN",
        "uq_support_tickets_one_open_verification_per_user,SUPPORT_TICKET_ALREADY_OPEN"
    })
    void handleDataIntegrityViolation_knownConstraint_answersItsDomainCode(
            String constraint, String expectedCode) {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleDataIntegrityViolation(
                        new DataIntegrityViolationException(
                                "could not execute statement",
                                new RuntimeException(
                                        "ERROR: duplicate key value violates unique constraint \""
                                                + constraint
                                                + "\"")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo(expectedCode);
    }

    // A constraint nobody has assigned a meaning to must keep the generic conflict rather than be
    // guessed at.
    @Test
    void handleDataIntegrityViolation_unmappedConstraint_keepsTheGenericConflict() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleDataIntegrityViolation(
                        new DataIntegrityViolationException(
                                "could not execute statement",
                                new RuntimeException(
                                        "ERROR: duplicate key value violates unique constraint"
                                                + " \"uq_something_unmapped\"")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo("BAD_REQUEST");
    }

    // P7-BE-S03. The driver's message is not only the constraint name: it carries the failing
    // statement and a DETAIL line too. Matching a mapped name anywhere in that text let a different
    // constraint's violation borrow its domain code as soon as the statement mentioned it - and an
    // insert naming a column or an index in its own SQL is enough to do that.
    @Test
    void handleDataIntegrityViolation_mappedNameOnlyInTheStatementText_isNotBorrowed() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleDataIntegrityViolation(
                        new DataIntegrityViolationException(
                                "could not execute statement",
                                new RuntimeException(
                                        "ERROR: duplicate key value violates unique constraint"
                                                + " \"uq_something_else\"\n"
                                                + "  Detail: Key (id)=(1) already exists.\n"
                                                + "  Statement: INSERT INTO notes(body) VALUES"
                                                + " ('see uq_user_verifications_active for context')")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // The violated constraint is unmapped, so the generic conflict is the correct answer.
        assertThat(response.getBody().getCode()).isEqualTo("BAD_REQUEST");
    }

    // The name is matched exactly, so a longer constraint name that merely contains a mapped one
    // does not inherit its meaning either.
    @Test
    void handleDataIntegrityViolation_nameContainingAMappedName_isNotBorrowed() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleDataIntegrityViolation(
                        new DataIntegrityViolationException(
                                "could not execute statement",
                                new RuntimeException(
                                        "ERROR: duplicate key value violates unique constraint"
                                                + " \"uq_user_verifications_active_archive\"")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo("BAD_REQUEST");
    }

    // Hibernate exposes the constraint name as a field, which is the source that cannot be confused
    // by anything else in the message.
    @Test
    void handleDataIntegrityViolation_hibernateNamesTheConstraint_isReadFromTheField() {
        ResponseEntity<ApiResponse<?>> response =
                handler.handleDataIntegrityViolation(
                        new DataIntegrityViolationException(
                                "could not execute statement",
                                new org.hibernate.exception.ConstraintViolationException(
                                        "could not execute statement",
                                        new java.sql.SQLException("duplicate key"),
                                        "uq_support_tickets_one_open_support_per_user")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo("SUPPORT_TICKET_ALREADY_OPEN");
    }
}
