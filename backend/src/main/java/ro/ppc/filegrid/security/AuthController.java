package ro.ppc.filegrid.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.ppc.filegrid.config.StatsProperties;
import ro.ppc.filegrid.domain.DataCategory;

import java.util.Set;
import java.util.TreeSet;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserDirectory users;
    private final SessionTokens tokens;
    private final AccessPolicy access;
    private final SecurityMetrics metrics;

    private final StatsProperties stats;

    public AuthController(UserDirectory users, SessionTokens tokens, AccessPolicy access, SecurityMetrics metrics,
                          StatsProperties stats) {
        this.stats = stats;
        this.users = users;
        this.tokens = tokens;
        this.access = access;
        this.metrics = metrics;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record SessionResponse(String username, String displayName, Set<String> roles,
                                  Set<DataCategory> readableCategories, Set<DataCategory> writableCategories,
                                  boolean canViewStats, boolean canRunBenchmark) {
    }

    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request) {
        UserDirectory.Account account = users.authenticate(request.username(), request.password()).orElseThrow(() -> {
            metrics.authenticationFailed("user");
            log.warn("Autentificare esuata pentru utilizatorul '{}'", request.username());
            return new LoginFailedException();
        });
        log.info("Utilizatorul {} s-a autentificat", account.username());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, tokens.issue(account).toString())
                .body(session(account));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, tokens.expired().toString())
                .build();
    }

    @GetMapping("/session")
    public SessionResponse session(Authentication authentication) {
        return users.find(authentication.getName())
                .map(this::session)
                .orElseThrow(LoginFailedException::new);
    }

    private SessionResponse session(UserDirectory.Account account) {
        return new SessionResponse(account.username(), account.displayName(), new TreeSet<>(account.roles()),
                new TreeSet<>(access.readable(account.roles())), new TreeSet<>(access.writable(account.roles())),
                account.roles().stream().anyMatch(stats.viewRoles()::contains),
                account.roles().stream().anyMatch(stats.benchmarkRoles()::contains));
    }
}
