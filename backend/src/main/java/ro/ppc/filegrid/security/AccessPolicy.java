package ro.ppc.filegrid.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.CategoryPolicy;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.DataCategory;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AccessPolicy {

    private static final String ROLE_PREFIX = "ROLE_";

    private final FileGridProperties props;
    private final SecurityMetrics metrics;

    public AccessPolicy(FileGridProperties props, SecurityMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
    }

    public String currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "anonim" : authentication.getName();
    }

    public Set<String> currentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(ROLE_PREFIX))
                .map(a -> a.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toSet());
    }

    public boolean canRead(DataCategory category) {
        return allowed(currentRoles(), props.policyFor(category).readRoles());
    }

    public boolean canWrite(DataCategory category) {
        return allowed(currentRoles(), props.policyFor(category).writeRoles());
    }

    public void requireRead(DataCategory category) {
        if (!canRead(category)) {
            deny(category, "citire");
        }
    }

    public void requireWrite(DataCategory category) {
        if (!canWrite(category)) {
            deny(category, "scriere");
        }
    }

    public Set<DataCategory> readable(Collection<String> roles) {
        return categories(roles, CategoryPolicy::readRoles);
    }

    public Set<DataCategory> writable(Collection<String> roles) {
        return categories(roles, CategoryPolicy::writeRoles);
    }

    public void requireOwner(String owner) {
        if (owner == null || !owner.equals(currentUser())) {
            throw new ForbiddenException("Sesiunea de upload apartine altui utilizator.");
        }
    }

    private Set<DataCategory> categories(Collection<String> roles, Function<CategoryPolicy, Set<String>> selector) {
        Set<DataCategory> result = EnumSet.noneOf(DataCategory.class);
        props.policies().forEach((category, policy) -> {
            if (allowed(roles, selector.apply(policy))) {
                result.add(category);
            }
        });
        return result;
    }

    private void deny(DataCategory category, String operation) {
        metrics.accessDenied(category);
        throw new ForbiddenException("Contul " + currentUser() + " nu are drept de " + operation
                + " pentru categoria " + category + ".");
    }

    private static boolean allowed(Collection<String> roles, Set<String> required) {
        return roles.stream().anyMatch(required::contains);
    }
}
