package com.taskflow.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AuthRequestValidationTest {
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidation() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidation() {
        validatorFactory.close();
    }

    @Test
    void emailIsNormalizedBeforeValidation() {
        RegisterRequest request = new RegisterRequest("  STUDENT@EXAMPLE.COM  ", "correct-password");

        assertThat(request.email()).isEqualTo("student@example.com");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void registrationRejectsShortPasswordsAndMalformedEmails() {
        assertThat(validator.validate(new RegisterRequest("not-an-email", "short")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("email", "password");
    }

    @Test
    void passwordsAreLimitedByUtf8BytesAndNotOnlyByCharacters() {
        String atLimit = "я".repeat(36);
        String overLimit = "я".repeat(37);

        assertThat(validator.validate(new RegisterRequest("student@example.com", atLimit))).isEmpty();
        assertThat(validator.validate(new RegisterRequest("student@example.com", overLimit)))
                .extracting(violation -> violation.getPropertyPath().toString()).contains("password");
        assertThat(validator.validate(new LoginRequest("student@example.com", overLimit)))
                .extracting(violation -> violation.getPropertyPath().toString()).contains("password");
    }

    @Test
    void blankOrMissingCredentialsAreRejected() {
        assertThat(validator.validate(new RegisterRequest(null, null)))
                .extracting(violation -> violation.getPropertyPath().toString()).contains("email", "password");
        assertThat(validator.validate(new LoginRequest("  ", "  ")))
                .extracting(violation -> violation.getPropertyPath().toString()).contains("email", "password");
    }
}
