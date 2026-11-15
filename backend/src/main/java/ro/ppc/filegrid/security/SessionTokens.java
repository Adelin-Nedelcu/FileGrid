package ro.ppc.filegrid.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.FileGridProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Component
public class SessionTokens {

    public static final String COOKIE = "FILEGRID_SESSION";
    public static final String ROLES_CLAIM = "roles";
    private static final String COOKIE_PATH = "/api";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration lifetime;
    private final boolean secureCookie;

    public SessionTokens(FileGridProperties props) {
        byte[] secret = Base64.getDecoder().decode(props.security().jwtSecret().trim());
        if (secret.length < 32) {
            throw new IllegalStateException("FILEGRID_JWT_SECRET trebuie sa aiba cel putin 32 bytes (base64).");
        }
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.lifetime = props.security().sessionDuration();
        this.secureCookie = props.security().secureCookie();
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    public ResponseCookie issue(UserDirectory.Account account) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("filegrid")
                .subject(account.username())
                .issuedAt(now)
                .expiresAt(now.plus(lifetime))
                .claim(ROLES_CLAIM, List.copyOf(account.roles()))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return cookie(token, lifetime);
    }

    public ResponseCookie expired() {
        return cookie("", Duration.ZERO);
    }

    public static String fromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
