package ro.ppc.filegrid.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SessionTokens tokens, MeterKeys meterKeys,
                                    SecurityMetrics metrics) throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, e) ->
                writeProblem(response, HttpStatus.UNAUTHORIZED, "Neautentificat", "Autentificati-va pentru a continua.");
        AccessDeniedHandler denied = (request, response, e) ->
                writeProblem(response, HttpStatus.FORBIDDEN, "Acces interzis", "Nu aveti dreptul sa faceti aceasta operatie.");

        http
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers(MeterKeyFilter.PATH))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/logout").permitAll()
                        .requestMatchers(MeterKeyFilter.PATH).hasRole(MeterKeyFilter.ROLE)
                        .anyRequest().authenticated())
                .addFilterBefore(new SessionCookieFilter(tokens.decoder(), roleConverter()),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new MeterKeyFilter(meterKeys, metrics), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private static JwtAuthenticationConverter roleConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(SessionTokens.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static void writeProblem(HttpServletResponse response, HttpStatus status, String title, String detail)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"title\":\"" + title + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail + "\"}");
    }

    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
