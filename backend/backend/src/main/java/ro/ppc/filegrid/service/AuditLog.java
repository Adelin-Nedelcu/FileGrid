package ro.ppc.filegrid.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ro.ppc.filegrid.domain.StoredFile;
import ro.ppc.filegrid.web.RequestIdFilter;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class AuditLog {

    private final JdbcTemplate jdbc;

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(StoredFile file, String action, boolean success, String detail) {
        HttpServletRequest request = currentRequest();
        String actor = request != null && request.getRemoteUser() != null ? request.getRemoteUser() : "anonim";
        String clientIp = request != null ? request.getRemoteAddr() : null;
        jdbc.update("""
                INSERT INTO file_access_log (file_id, category, action, actor, client_ip, request_id, success, detail, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                file.getId(), file.getCategory().name(), action, actor, clientIp,
                MDC.get(RequestIdFilter.MDC_KEY), success, detail, Timestamp.from(Instant.now()));
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
