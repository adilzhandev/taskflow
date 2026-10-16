package com.taskflow.auth;

import java.time.Instant;

public record UserResponse(Long id, String email, Instant createdAt) {
}
