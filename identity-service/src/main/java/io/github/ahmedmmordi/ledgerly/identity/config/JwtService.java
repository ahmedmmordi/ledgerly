package io.github.ahmedmmordi.ledgerly.identity.config;

import io.github.ahmedmmordi.ledgerly.identity.auth.InvalidTokenException;
import io.github.ahmedmmordi.ledgerly.identity.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class JwtService {

    @Value("${app.security.jwt.secret}")
    private String secret;

    @Value("${app.security.jwt.expiration-ms}")
    private long expirationMs;

    public String generateToken(UUID userId, String email, Role role) {
        return Jwts.builder().subject(userId.toString()).claim("email", email).claim("role", role.name())
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey()).compact();
    }

    public Claims parseToken(String token) {
        try {
            return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
        } catch (JwtException ex) {
            log.warn("Invalid JWT token: {}", ex.getMessage());
            throw new InvalidTokenException();
        }
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }

    public String extractRole(String token) {
        return parseToken(token).get("role", String.class);
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }
}
