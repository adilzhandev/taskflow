package com.taskflow.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final JwtEncoder jwtEncoder;
    private final Duration ttl;

    public JwtService(JwtEncoder jwtEncoder, @Value("${app.jwt.ttl:1h}") Duration ttl) {
        if (ttl.toSeconds() <= 0) {
            throw new IllegalArgumentException("app.jwt.ttl must be at least one second");
        }
        this.jwtEncoder = jwtEncoder;
        this.ttl = ttl;
    }

    public String issueToken(User user) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("taskflow")
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long getExpiresIn() {
        return ttl.toSeconds();
    }
}
