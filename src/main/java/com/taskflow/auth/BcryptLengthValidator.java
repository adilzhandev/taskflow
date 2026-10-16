package com.taskflow.auth;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public class BcryptLengthValidator implements ConstraintValidator<BcryptLength, String> {
    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        // BCrypt's limit is measured in bytes, so a character limit alone is insufficient.
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
