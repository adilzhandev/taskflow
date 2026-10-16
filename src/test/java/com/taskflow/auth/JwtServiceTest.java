package com.taskflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskflow.config.JwtConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

class JwtServiceTest {
    private final JwtConfig config = new JwtConfig();
    private JwtEncoder encoder;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        // This public fixture is used only by tests, never as an application default.
        String testSecret = Base64.getEncoder().encodeToString(
                "test-only-signing-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8));
        SecretKey key = config.jwtSecretKey(testSecret);
        encoder = config.jwtEncoder(key);
        decoder = config.jwtDecoder(key);
    }

    @Test
    void issuedTokenContainsOnlyAuthenticationClaimsAndExpiresAfterAnHour() {
        JwtService service = new JwtService(encoder, Duration.ofHours(1));
        User user = new User("student@example.com", "password-hash");
        ReflectionTestUtils.setField(user, "id", 42L);

        Jwt token = decoder.decode(service.issueToken(user));

        assertThat(token.getSubject()).isEqualTo("42");
        assertThat(token.getClaimAsString("iss")).isEqualTo("taskflow");
        assertThat(token.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(Duration.between(token.getIssuedAt(), token.getExpiresAt())).isEqualTo(Duration.ofHours(1));
        assertThat(token.getClaims()).doesNotContainKeys("password", "passwordHash", "email");
        assertThat(service.getExpiresIn()).isEqualTo(3600L);
    }

    @Test
    void expiredTokensAreRejected() {
        String token = encode(validClaims().issuedAt(Instant.now().minusSeconds(120)).expiresAt(Instant.now().minusSeconds(1)));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokensWithoutExpirationAreRejected() {
        String token = encode(JwtClaimsSet.builder().issuer("taskflow").subject("42").issuedAt(Instant.now()));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void futureNotBeforeAndWrongIssuerAreRejected() {
        String futureToken = encode(validClaims().notBefore(Instant.now().plusSeconds(300)));
        String wrongIssuer = encode(validClaims().issuer("another-application"));

        assertThatThrownBy(() -> decoder.decode(futureToken)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(wrongIssuer)).isInstanceOf(JwtException.class);
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"abc", "0", "-1", "9223372036854775808"})
    void invalidSubjectsAreRejected(String subject) {
        String token = encode(validClaims().subject(subject));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokensWithoutSubjectAreRejected() {
        String token = encode(JwtClaimsSet.builder().issuer("taskflow")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokensSignedWithAnotherKeyAreRejected() {
        SecretKey otherKey = config.jwtSecretKey(Base64.getEncoder().encodeToString(
                "another-test-only-key-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)));
        JwtEncoder otherEncoder = config.jwtEncoder(otherKey);
        String token = otherEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), validClaims().build())).getTokenValue();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void malformedOrShortSigningSecretsAreRejectedWithoutEchoingTheValue() {
        assertThatThrownBy(() -> config.jwtSecretKey("not!base64"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.jwt.secret must be valid Base64");
        assertThatThrownBy(() -> config.jwtSecretKey(Base64.getEncoder().encodeToString(new byte[31])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.jwt.secret must contain at least 32 decoded bytes");
    }

    private JwtClaimsSet.Builder validClaims() {
        return JwtClaimsSet.builder().issuer("taskflow").subject("42")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600));
    }

    private String encode(JwtClaimsSet.Builder claims) {
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims.build())).getTokenValue();
    }
}
