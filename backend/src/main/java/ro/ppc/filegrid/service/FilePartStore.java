package ro.ppc.filegrid.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class FilePartStore {

    public record Part(int number, long sizeBytes, String sha256) {
    }

    private final JdbcTemplate jdbc;

    public FilePartStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(UUID fileId, int partNumber, long sizeBytes, String sha256) {
        jdbc.update("""
                INSERT INTO stored_file_part (file_id, part_number, size_bytes, sha256, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (file_id, part_number) DO UPDATE
                   SET size_bytes = EXCLUDED.size_bytes, sha256 = EXCLUDED.sha256, created_at = EXCLUDED.created_at""",
                fileId, partNumber, sizeBytes, sha256, Timestamp.from(Instant.now()));
    }

    public List<Part> list(UUID fileId) {
        return jdbc.query("""
                SELECT part_number, size_bytes, sha256 FROM stored_file_part
                WHERE file_id = ? ORDER BY part_number""",
                (rs, i) -> new Part(rs.getInt(1), rs.getLong(2), rs.getString(3)), fileId);
    }

    public void delete(UUID fileId, int partNumber) {
        jdbc.update("DELETE FROM stored_file_part WHERE file_id = ? AND part_number = ?", fileId, partNumber);
    }

    public void deleteAll(UUID fileId) {
        jdbc.update("DELETE FROM stored_file_part WHERE file_id = ?", fileId);
    }
}
