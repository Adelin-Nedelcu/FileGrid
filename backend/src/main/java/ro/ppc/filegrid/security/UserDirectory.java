package ro.ppc.filegrid.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.FileGridProperties;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Component
public class UserDirectory {

    public record Account(String username, String displayName, Set<String> roles) {
    }

    private record Entry(Account account, String passwordHash) {
    }

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<String, Entry> entries = new HashMap<>();
    private final String decoyHash;

    public UserDirectory(FileGridProperties props) {
        props.security().users().forEach((username, user) -> {
            String name = username.toLowerCase(Locale.ROOT);
            String displayName = user.displayName() == null || user.displayName().isBlank() ? name : user.displayName();
            Account account = new Account(name, displayName, Set.copyOf(new TreeSet<>(user.roles())));
            entries.put(name, new Entry(account, encoder.encode(user.password())));
        });
        this.decoyHash = encoder.encode(props.security().jwtSecret());
    }

    public Optional<Account> authenticate(String username, String password) {
        Entry entry = username == null ? null : entries.get(username.trim().toLowerCase(Locale.ROOT));
        if (entry == null) {
            encoder.matches(password == null ? "" : password, decoyHash);
            return Optional.empty();
        }
        return encoder.matches(password == null ? "" : password, entry.passwordHash())
                ? Optional.of(entry.account())
                : Optional.empty();
    }

    public Optional<Account> find(String username) {
        Entry entry = username == null ? null : entries.get(username.toLowerCase(Locale.ROOT));
        return Optional.ofNullable(entry).map(Entry::account);
    }
}
