package com.taskflow.auth;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyRegisteredException();
        }

        User user = new User(request.email(), passwordEncoder.encode(request.password()));
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            // Two requests can pass the precheck together; the database decides uniqueness.
            if (isDuplicateEmail(exception)) {
                throw new EmailAlreadyRegisteredException();
            }
            throw exception;
        }
        return createResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return createResponse(user);
    }

    private AuthResponse createResponse(User user) {
        UserResponse userResponse = new UserResponse(user.getId(), user.getEmail(), user.getCreatedAt());
        return new AuthResponse(jwtService.issueToken(user), "Bearer", jwtService.getExpiresIn(), userResponse);
    }

    private boolean isDuplicateEmail(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && "uk_users_email".equals(violation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
