package ro.ppc.filegrid.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class MeterKeyFilter extends OncePerRequestFilter {

    public static final String PATH = "/api/meter-readings";
    public static final String ID_HEADER = "X-Meter-Id";
    public static final String KEY_HEADER = "X-Meter-Key";
    public static final String ROLE = "METER";
    public static final String PRINCIPAL_PREFIX = "contor:";

    private final MeterKeys keys;
    private final SecurityMetrics metrics;

    public MeterKeyFilter(MeterKeys keys, SecurityMetrics metrics) {
        this.keys = keys;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String meterId = request.getHeader(ID_HEADER);
        String key = request.getHeader(KEY_HEADER);
        if (keys.matches(meterId, key)) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    PRINCIPAL_PREFIX + meterId, null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
            SecurityContextHolder.setContext(context);
        } else if (key != null) {
            metrics.authenticationFailed("meter");
        }
        chain.doFilter(request, response);
    }
}
