package com.app.modules.auth.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.app.modules.auth.dto.request.RegisterRequest;
import com.app.modules.auth.dto.request.ResetPasswordRequest;

class PasswordPolicyValidatorTest {

    private static final String NO_BREAK_SPACE = "\u00A0";
    private static final String ZERO_WIDTH_SPACE = "\u200B";
    private static final String E_ACUTE = "\u00E9";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openFactory() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @Test
    void register_sevenCharacters_rejected() {
        assertRegisterRejected("Abcdef1");
    }

    @Test
    void register_eightCharactersSatisfyingAllRules_accepted() {
        assertRegisterAccepted("Abcdefg1");
    }

    @Test
    void register_sixtyFourCharactersSatisfyingAllRules_accepted() {
        String password = "A1" + "a".repeat(62);
        assertThat(password).hasSize(64);
        assertRegisterAccepted(password);
    }

    @Test
    void register_sixtyFiveCharacters_rejected() {
        String password = "A1" + "a".repeat(63);
        assertThat(password).hasSize(65);
        assertRegisterRejected(password);
    }

    @Test
    void register_sixtyFourCharactersExceedingSeventyTwoUtf8Bytes_rejected() {
        // Latin small letter e with acute encodes to two UTF-8 bytes, so 64 characters occupy 126
        String password = "A1" + E_ACUTE.repeat(62);
        assertThat(password).hasSize(64);
        assertThat(password.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);
        assertRegisterRejected(password);
    }

    @Test
    void register_oneHundredCharacters_rejected() {
        String password = "A1" + "a".repeat(98);
        assertThat(password).hasSize(100);
        assertRegisterRejected(password);
    }

    @Test
    void register_noUppercase_rejected() {
        assertRegisterRejected("lowercaseonly1!");
    }

    @Test
    void register_noDigitAndNoSpecial_rejected() {
        assertRegisterRejected("Lowercaseonly");
    }

    @Test
    void register_digitButNoSpecial_accepted() {
        assertRegisterAccepted("Lowercaseonly1");
    }

    @Test
    void register_specialButNoDigit_accepted() {
        assertRegisterAccepted("Lowercaseonly!");
    }

    @Test
    void register_embeddedAsciiSpace_rejected() {
        assertRegisterRejected("Abcde fg1");
    }

    @Test
    void register_leadingAsciiSpace_rejected() {
        assertRegisterRejected(" Abcdefg1");
    }

    @Test
    void register_trailingAsciiSpace_rejected() {
        assertRegisterRejected("Abcdefg1 ");
    }

    @Test
    void register_embeddedNoBreakSpace_rejected() {
        assertRegisterRejected("Abcd" + NO_BREAK_SPACE + "efg1");
    }

    @Test
    void register_embeddedZeroWidthSpace_rejected() {
        assertRegisterRejected("Abcd" + ZERO_WIDTH_SPACE + "efg1");
    }

    @Test
    void register_embeddedTab_rejected() {
        assertRegisterRejected("Abcd\tefg1");
    }

    @Test
    void resetPassword_sevenCharacters_rejected() {
        assertResetRejected("Abcdef1");
    }

    @Test
    void resetPassword_eightCharactersSatisfyingAllRules_accepted() {
        assertResetAccepted("Abcdefg1");
    }

    @Test
    void resetPassword_sixtyFourCharactersSatisfyingAllRules_accepted() {
        assertResetAccepted("A1" + "a".repeat(62));
    }

    @Test
    void resetPassword_sixtyFiveCharacters_rejected() {
        assertResetRejected("A1" + "a".repeat(63));
    }

    @Test
    void resetPassword_sixtyFourCharactersExceedingSeventyTwoUtf8Bytes_rejected() {
        assertResetRejected("A1" + E_ACUTE.repeat(62));
    }

    @Test
    void resetPassword_oneHundredCharacters_rejected() {
        assertResetRejected("A1" + "a".repeat(98));
    }

    @Test
    void resetPassword_noUppercase_rejected() {
        assertResetRejected("lowercaseonly1!");
    }

    @Test
    void resetPassword_noDigitAndNoSpecial_rejected() {
        assertResetRejected("Lowercaseonly");
    }

    @Test
    void resetPassword_digitButNoSpecial_accepted() {
        assertResetAccepted("Lowercaseonly1");
    }

    @Test
    void resetPassword_specialButNoDigit_accepted() {
        assertResetAccepted("Lowercaseonly!");
    }

    @Test
    void resetPassword_embeddedAsciiSpace_rejected() {
        assertResetRejected("Abcde fg1");
    }

    @Test
    void resetPassword_leadingAsciiSpace_rejected() {
        assertResetRejected(" Abcdefg1");
    }

    @Test
    void resetPassword_trailingAsciiSpace_rejected() {
        assertResetRejected("Abcdefg1 ");
    }

    @Test
    void resetPassword_embeddedNoBreakSpace_rejected() {
        assertResetRejected("Abcd" + NO_BREAK_SPACE + "efg1");
    }

    @Test
    void resetPassword_embeddedZeroWidthSpace_rejected() {
        assertResetRejected("Abcd" + ZERO_WIDTH_SPACE + "efg1");
    }

    @Test
    void resetPassword_embeddedTab_rejected() {
        assertResetRejected("Abcd\tefg1");
    }

    @Test
    void message_namesTheRuleThatFailed() {
        assertThat(registerPasswordMessages("Abcdef1"))
                .allSatisfy(m -> assertThat(m).contains("8"));
        assertThat(registerPasswordMessages("A1" + "a".repeat(63)))
                .allSatisfy(m -> assertThat(m).contains("64"));
        assertThat(registerPasswordMessages("A1" + E_ACUTE.repeat(62)))
                .allSatisfy(m -> assertThat(m).contains("72"));
        assertThat(registerPasswordMessages("lowercaseonly1!"))
                .allSatisfy(m -> assertThat(m).containsIgnoringCase("uppercase"));
        assertThat(registerPasswordMessages("Lowercaseonly"))
                .allSatisfy(m -> assertThat(m).containsIgnoringCase("digit"));
        assertThat(registerPasswordMessages("Abcd" + NO_BREAK_SPACE + "efg1"))
                .allSatisfy(m -> assertThat(m).containsIgnoringCase("whitespace"));
    }

    @Test
    void allPolicyRejectionsProduceExactlyOneMessage() {
        // GlobalExceptionHandler collects field errors into a Map keyed by field name, so a second
        // violation on the same field would silently overwrite the first
        assertThat(registerPasswordMessages("lowercase")).hasSize(1);
    }

    private static Set<String> registerPasswordMessages(String password) {
        return violationMessages(register(password), "password");
    }

    private static void assertRegisterAccepted(String password) {
        assertThat(registerPasswordMessages(password)).isEmpty();
    }

    private static void assertRegisterRejected(String password) {
        assertThat(registerPasswordMessages(password)).isNotEmpty();
    }

    private static void assertResetAccepted(String password) {
        assertThat(violationMessages(reset(password), "newPassword")).isEmpty();
    }

    private static void assertResetRejected(String password) {
        assertThat(violationMessages(reset(password), "newPassword")).isNotEmpty();
    }

    private static RegisterRequest register(String password) {
        return new RegisterRequest(
                "john_doe", "john@example.com", password, "John Doe", "turnstile-token");
    }

    private static ResetPasswordRequest reset(String password) {
        return new ResetPasswordRequest("a1b2c3d4e5f6", password, "turnstile-token");
    }

    private static <T> Set<String> violationMessages(T target, String field) {
        Set<ConstraintViolation<T>> violations = validator.validate(target);
        return violations.stream()
                .filter(v -> v.getPropertyPath().toString().equals(field))
                .map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
