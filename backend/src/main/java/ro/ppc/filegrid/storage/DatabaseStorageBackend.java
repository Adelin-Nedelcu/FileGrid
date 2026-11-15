package ro.ppc.filegrid.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.StorageType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DatabaseStorageBackend implements StorageBackend {

    private static final Logger log = LoggerFactory.getLogger(DatabaseStorageBackend.class);

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS blob_object (
                storage_key  varchar(512) PRIMARY KEY,
                content      bytea        NOT NULL,
                size_bytes   bigint       NOT NULL,
                content_type varchar(255),
                created_at   timestamptz  NOT NULL DEFAULT now()
            )""";

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final long maxObjectSize;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public DatabaseStorageBackend(FileGridProperties props) {
        FileGridProperties.Database cfg = props.storage().database();
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("storage-db");
        hikari.setJdbcUrl(cfg.url());
        hikari.setUsername(cfg.username());
        hikari.setPassword(cfg.password());
        hikari.setMaximumPoolSize(cfg.maxPoolSize());
        hikari.setConnectionTimeout(cfg.connectionTimeout().toMillis());
        hikari.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(hikari);
        this.jdbc = new JdbcTemplate(dataSource);
        this.maxObjectSize = cfg.maxObjectSize().toBytes();
    }

    @Override
    public StorageType type() {
        return StorageType.DATABASE;
    }

    public long maxObjectSize() {
        return maxObjectSize;
    }

    @Override
    public void write(String key, InputStream content, long length, String contentType) {
        byte[] bytes;
        try {
            bytes = content.readNBytes((int) Math.min(maxObjectSize + 1, Integer.MAX_VALUE - 8));
        } catch (IOException e) {
            throw new StorageUnavailableException(type(), "nu pot citi continutul primit: " + e.getMessage(), e);
        }
        if (bytes.length > maxObjectSize) {
            throw new IllegalArgumentException("Obiect prea mare pentru storage-ul DATABASE (max " + maxObjectSize + " bytes)");
        }
        try {
            ensureSchema();
            jdbc.update("""
                    INSERT INTO blob_object (storage_key, content, size_bytes, content_type)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (storage_key) DO UPDATE
                       SET content = EXCLUDED.content, size_bytes = EXCLUDED.size_bytes,
                           content_type = EXCLUDED.content_type, created_at = now()""",
                    key, bytes, bytes.length, contentType);
        } catch (DataAccessException e) {
            throw new StorageUnavailableException(type(), "scrierea a esuat: " + e.getMostSpecificCause().getMessage(), e);
        }
    }

    @Override
    public InputStream read(String key) {
        try {
            ensureSchema();
            List<byte[]> rows = jdbc.query("SELECT content FROM blob_object WHERE storage_key = ?",
                    (rs, i) -> rs.getBytes(1), key);
            if (rows.isEmpty()) {
                throw new ObjectNotFoundException("Obiectul " + key + " nu exista in storage-ul DATABASE");
            }
            return new ByteArrayInputStream(rows.getFirst());
        } catch (DataAccessException e) {
            throw new StorageUnavailableException(type(), "citirea a esuat: " + e.getMostSpecificCause().getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            ensureSchema();
            jdbc.update("DELETE FROM blob_object WHERE storage_key = ?", key);
        } catch (DataAccessException e) {
            throw new StorageUnavailableException(type(), "stergerea a esuat: " + e.getMostSpecificCause().getMessage(), e);
        }
    }

    @Override
    public boolean ping() {
        try {
            ensureSchema();
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (RuntimeException e) {
            log.debug("DATABASE storage ping esuat: {}", e.getMessage());
            return false;
        }
    }

    private void ensureSchema() {
        if (schemaReady.get()) {
            return;
        }
        synchronized (this) {
            if (!schemaReady.get()) {
                jdbc.execute(SCHEMA);
                schemaReady.set(true);
            }
        }
    }

    @PreDestroy
    void close() {
        dataSource.close();
    }
}
