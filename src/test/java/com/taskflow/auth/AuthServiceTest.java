package com.taskflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
    }

    @Test
    void registrationNormalizesEmailHashesPasswordAndReturnsTheSavedUser() {
        when(passwordEncoder.encode("correct-password")).thenReturn("encoded-password");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 42L);
            return user;
        });
        when(jwtService.issueToken(any(User.class))).thenReturn("access-token");
        when(jwtService.getExpiresIn()).thenReturn(3600L);

        AuthResponse response = authService.register(new RegisterRequest("  STUDENT@EXAMPLE.COM  ", "correct-password"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).existsByEmail("student@example.com");
        verify(userRepository).saveAndFlush(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("student@example.com");
        assertThat(savedUser.getPasswordHash()).isEqualTo("encoded-password").isNotEqualTo("correct-password");
        assertThat(savedUser.getCreatedAt()).isNotNull();
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600L);
        assertThat(response.user()).isEqualTo(new UserResponse(42L, "student@example.com", savedUser.getCreatedAt()));
    }

    @Test
    void existingEmailIsRejectedWithoutEncodingOrIssuingAToken() {
        when(userRepository.existsByEmail("student@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("Student@Example.COM", "correct-password")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(passwordEncoder, jwtService);
    }

    @Test
    void databaseUniquenessConflictIsHandledWhenAnotherRegistrationWinsTheRace() {
        when(passwordEncoder.encode("correct-password")).thenReturn("encoded-password");
        ConstraintViolationException constraint = new ConstraintViolationException(
                "duplicate value", new SQLException("duplicate value", "23505"), "uk_users_email");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed", constraint));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("student@example.com", "correct-password")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verifyNoInteractions(jwtService);
    }

    @Test
    void unrelatedDatabaseFailureIsNotReportedAsADuplicateEmail() {
        when(passwordEncoder.encode("correct-password")).thenReturn("encoded-password");
        DataIntegrityViolationException failure = new DataIntegrityViolationException("another constraint failed");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(failure);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("student@example.com", "correct-password")))
                .isSameAs(failure);

        verifyNoInteractions(jwtService);
    }

    @Test
    void loginNormalizesEmailAndIssuesATokenAfterThePasswordMatches() {
        User user = savedUser();
        when(userRepository.findByEmail("student@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "encoded-password")).thenReturn(true);
        when(jwtService.issueToken(user)).thenReturn("access-token");
        when(jwtService.getExpiresIn()).thenReturn(3600L);

        AuthResponse response = authService.login(new LoginRequest(" STUDENT@example.com ", "correct-password"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.user().id()).isEqualTo(42L);
        assertThat(response.user().email()).isEqualTo("student@example.com");
        verify(passwordEncoder).matches("correct-password", "encoded-password");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void wrongPasswordNeverIssuesAToken() {
        when(userRepository.findByEmail("student@example.com")).thenReturn(Optional.of(savedUser()));

        assertThatThrownBy(() -> authService.login(new LoginRequest("student@example.com", "wrong-password")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");

        verifyNoInteractions(jwtService);
    }

    @Test
    void unknownEmailReturnsTheSameAuthenticationError() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("unknown@example.com", "wrong-password")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");

        verifyNoInteractions(jwtService);
    }

    private User savedUser() {
        User user = new User("student@example.com", "encoded-password");
        ReflectionTestUtils.setField(user, "id", 42L);
        return user;
    }
}
