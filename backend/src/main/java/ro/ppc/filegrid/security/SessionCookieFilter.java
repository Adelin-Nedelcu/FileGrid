package ro.ppc.filegrid.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

public class SessionCookieFilter extends OncePerRequestFilter {

    private static final Set<String> SKIPPED = Set.of("/api/auth/login", "/api/auth/logout", MeterKeyFilter.PATH);

    private final JwtDecoder decoder;
    private final JwtAuthenticationConverter converter;

    public SessionCookieFilter(JwtDecoder decoder, JwtAuthenticationConverter converter) {
        this.decoder = decoder;
        this.converter = converter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SKIPPED.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = SessionTokens.fromCookie(request);
        if (token != null) {
            try {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(converter.convert(decoder.decode(token)));
                SecurityContextHolder.setContext(context);
            } catch (JwtException e) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
