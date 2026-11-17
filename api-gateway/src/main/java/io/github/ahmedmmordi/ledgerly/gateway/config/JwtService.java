package io.github.ahmedmmordi.ledgerly.gateway.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@NullMarked
@Service
public class JwtService {

    private final JwtParser jwtParser;

    public JwtService(@Value("${app.security.jwt.secret:ZGV2LW9ubHktc2VjcmV0LWtleS0zMi1ieXRlcy1taW4h}") String secret) {
        SecretKey signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.jwtParser = Jwts.parser().verifyWith(signingKey).build();
    }

    public Claims parseToken(String token) {
        return jwtParser.parseSignedClaims(token).getPayload();
    }
}
