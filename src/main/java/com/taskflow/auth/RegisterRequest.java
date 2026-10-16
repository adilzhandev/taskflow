package com.taskflow.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) @BcryptLength String password) {
    public RegisterRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
