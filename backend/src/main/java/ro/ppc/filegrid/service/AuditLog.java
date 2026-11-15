package ro.ppc.filegrid.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ro.ppc.filegrid.domain.DataCategory;
import ro.ppc.filegrid.domain.StoredFile;
import ro.ppc.filegrid.web.RequestIdFilter;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class AuditLog {

    private final JdbcTemplate jdbc;
    private final EncryptionService encryption;

    public AuditLog(JdbcTemplate jdbc, EncryptionService encryption) {
        this.jdbc = jdbc;
        this.encryption = encryption;
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

    public record Entry(long id, UUID fileId, String fileName, DataCategory category, String action, String actor,
                        String clientIp, String requestId, boolean success, String detail, Instant occurredAt) {
    }

    public record Result(List<Entry> items, long total) {
    }

    public Result search(Collection<DataCategory> categories, String action, String text, int page, int size) {
        if (categories.isEmpty()) {
            return new Result(List.of(), 0);
        }
        StringBuilder where = new StringBuilder(" FROM file_access_log l JOIN stored_file f ON f.id = l.file_id WHERE l.category IN (");
        List<Object> args = new ArrayList<>();
        for (DataCategory category : categories) {
            where.append(args.isEmpty() ? "?" : ", ?");
            args.add(category.name());
        }
        where.append(")");
        if (action != null && !action.isBlank()) {
            where.append(" AND l.action = ?");
            args.add(action.trim());
        }
        if (text != null && !text.isBlank()) {
            String like = "%" + text.trim().toLowerCase(Locale.ROOT) + "%";
            where.append(" AND ((f.original_name NOT LIKE '").append(EncryptionService.TEXT_PREFIX)
                    .append("%' AND lower(f.original_name) LIKE ?) OR lower(l.actor) LIKE ? OR l.request_id LIKE ?");
            args.add(like);
            args.add(like);
            args.add(like);
            List<String> tokens = encryption.queryTokens(text);
            if (!tokens.isEmpty()) {
                where.append(" OR (").append(String.join(" AND ", Collections.nCopies(tokens.size(), "f.search_tokens LIKE ?")))
                        .append(")");
                tokens.forEach(token -> args.add("% " + token + " %"));
            }
            where.append(")");
        }
        Long total = jdbc.queryForObject("SELECT count(*)" + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<Entry> items = jdbc.query("""
                SELECT l.id, l.file_id, f.original_name, l.category, l.action, l.actor, l.client_ip, l.request_id,
                       l.success, l.detail, l.occurred_at""" + where + " ORDER BY l.occurred_at DESC, l.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new Entry(rs.getLong(1), rs.getObject(2, UUID.class), encryption.openText(rs.getString(3)),
                        DataCategory.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getBoolean(9), rs.getString(10), rs.getTimestamp(11).toInstant()),
                args.toArray());
        return new Result(items, total == null ? 0 : total);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
