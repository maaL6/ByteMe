package org.example;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.example.platform.security.BCryptPasswordHasher;
import org.example.platform.security.LocalRsaTokenIssuer;
import org.example.shared.api.AuthenticatedUser;
import org.example.shared.api.Role;
import static org.junit.jupiter.api.Assertions.*;

class AdaptersTest {
    @Test void bcryptRoundTripAndUtf8Limit() {
        var hasher = new BCryptPasswordHasher();
        String hash = hasher.hash("Sample-Test-Password");
        assertNotEquals("Sample-Test-Password", hash);
        assertTrue(hasher.matches("Sample-Test-Password", hash));
        assertFalse(hasher.matches("Wrong-Password", hash));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash("ế".repeat(25)));
    }
    @Test void signedTokenContainsServerIdentityAndExpiresExactly() throws Exception {
        var key = new RSAKeyGenerator(2048).generate();
        Instant now = Instant.parse("2026-10-07T00:00:00Z");
        var token = new LocalRsaTokenIssuer(key, Clock.fixed(now, ZoneOffset.UTC), 5400)
                .issue(new AuthenticatedUser("customer-a", Role.CUSTOMER));
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        var timestamp = new JwtTimestampValidator(Duration.ZERO);
        timestamp.setClock(Clock.fixed(now.plusSeconds(5399), ZoneOffset.UTC));
        decoder.setJwtValidator(timestamp);
        var claims = decoder.decode(token.value());
        assertEquals("customer-a", claims.getSubject());
        assertEquals("CUSTOMER", claims.getClaimAsString("role"));
        assertEquals(now.plusSeconds(5400), claims.getExpiresAt());
        assertFalse(token.toString().contains(token.value()));
        timestamp.setClock(Clock.fixed(now.plusSeconds(5401), ZoneOffset.UTC));
        assertThrows(JwtValidationException.class, () -> decoder.decode(token.value()));
    }
}
