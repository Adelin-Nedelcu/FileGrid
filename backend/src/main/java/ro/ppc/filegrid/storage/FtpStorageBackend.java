package ro.ppc.filegrid.storage;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.StorageType;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class FtpStorageBackend implements StorageBackend {

    private static final Logger log = LoggerFactory.getLogger(FtpStorageBackend.class);
    private static final String PARTIAL_SUFFIX = ".part";

    private record Idle(FTPClient client, long since) {
    }

    private final FileGridProperties.Ftp cfg;
    private final Semaphore slots;
    private final ConcurrentLinkedDeque<Idle> idle = new ConcurrentLinkedDeque<>();

    public FtpStorageBackend(FileGridProperties props, MeterRegistry meters) {
        this.cfg = props.storage().ftp();
        this.slots = new Semaphore(cfg.maxConnections(), true);
        Gauge.builder("filegrid.ftp.connections.in_use", slots, s -> cfg.maxConnections() - s.availablePermits())
                .description("Conexiuni FTP folosite in acest moment")
                .register(meters);
        Gauge.builder("filegrid.ftp.connections.idle", idle, ConcurrentLinkedDeque::size)
                .description("Conexiuni FTP deschise si pastrate pentru reutilizare")
                .register(meters);
        Gauge.builder("filegrid.ftp.connections.limit", cfg::maxConnections)
                .register(meters);
    }

    @Override
    public StorageType type() {
        return StorageType.FTP;
    }

    public int maxConnections() {
        return cfg.maxConnections();
    }

    @Override
    public void write(String key, InputStream content, long length, String contentType) {
        FTPClient ftp = borrow();
        String partial = key + "." + UUID.randomUUID() + PARTIAL_SUFFIX;
        boolean published = false;
        boolean healthy = false;
        try {
            ensureParentDirectories(ftp, key);
            if (!ftp.storeFile(partial, content)) {
                throw new StorageUnavailableException(type(), "STOR a esuat: " + ftp.getReplyString().trim(), null);
            }
            if (!ftp.rename(partial, key)) {
                throw new StorageUnavailableException(type(), "RENAME a esuat: " + ftp.getReplyString().trim(), null);
            }
            published = true;
            healthy = true;
        } catch (IOException e) {
            throw new StorageUnavailableException(type(), "scrierea a esuat: " + e.getMessage(), e);
        } finally {
            if (!published) {
                try {
                    ftp.deleteFile(partial);
                } catch (IOException ignored) {
                }
            }
            giveBack(ftp, healthy);
        }
    }

    @Override
    public InputStream read(String key) {
        FTPClient ftp = borrow();
        InputStream raw;
        try {
            raw = ftp.retrieveFileStream(key);
        } catch (IOException e) {
            giveBack(ftp, false);
            throw new StorageUnavailableException(type(), "citirea a esuat: " + e.getMessage(), e);
        }
        if (raw == null) {
            int reply = ftp.getReplyCode();
            giveBack(ftp, FTPReply.isNegativePermanent(reply));
            if (reply == FTPReply.FILE_UNAVAILABLE) {
                throw new ObjectNotFoundException("Fisierul " + key + " nu exista pe FTP");
            }
            throw new StorageUnavailableException(type(), "RETR a esuat cu codul " + reply, null);
        }
        return new PooledStream(raw, ftp);
    }

    @Override
    public void delete(String key) {
        FTPClient ftp = borrow();
        boolean healthy = false;
        try {
            ftp.deleteFile(key);
            healthy = true;
        } catch (IOException e) {
            throw new StorageUnavailableException(type(), "stergerea a esuat: " + e.getMessage(), e);
        } finally {
            giveBack(ftp, healthy);
        }
    }

    @Override
    public boolean ping() {
        try {
            FTPClient ftp = connect();
            try {
                return ftp.sendNoOp();
            } finally {
                disconnectQuietly(ftp);
            }
        } catch (RuntimeException | IOException e) {
            log.debug("FTP ping esuat: {}", e.getMessage());
            discardIdle();
            return false;
        }
    }

    private FTPClient borrow() {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(cfg.poolWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new StorageUnavailableException(type(), "toate cele " + cfg.maxConnections()
                    + " conexiuni FTP sunt ocupate", null);
        }
        try {
            long now = System.nanoTime();
            for (Idle candidate = idle.pollFirst(); candidate != null; candidate = idle.pollFirst()) {
                if (now - candidate.since() > cfg.maxIdle().toNanos()) {
                    disconnectQuietly(candidate.client());
                    continue;
                }
                try {
                    if (candidate.client().isConnected() && candidate.client().sendNoOp()) {
                        return candidate.client();
                    }
                } catch (IOException ignored) {
                }
                disconnectQuietly(candidate.client());
            }
            return connect();
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
    }

    private void giveBack(FTPClient ftp, boolean reusable) {
        try {
            if (reusable && ftp.isConnected()) {
                idle.offerFirst(new Idle(ftp, System.nanoTime()));
            } else {
                disconnectQuietly(ftp);
            }
        } finally {
            slots.release();
        }
    }

    private final class PooledStream extends FilterInputStream {
        private final FTPClient ftp;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private boolean finished;

        PooledStream(InputStream raw, FTPClient ftp) {
            super(raw);
            this.ftp = ftp;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            finished |= b < 0;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            finished |= n < 0;
            return n;
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            boolean reusable = false;
            try {
                super.close();
                reusable = finished && ftp.completePendingCommand();
            } catch (IOException e) {
                log.debug("Transfer FTP inchis inainte de final: {}", e.getMessage());
            } finally {
                giveBack(ftp, reusable);
            }
        }
    }

    private FTPClient connect() {
        FTPClient ftp = new FTPClient();
        ftp.setSocketFactory(new NoDelaySocketFactory());
        ftp.setConnectTimeout((int) cfg.connectTimeout().toMillis());
        ftp.setDefaultTimeout((int) cfg.dataTimeout().toMillis());
        ftp.setDataTimeout(cfg.dataTimeout());
        ftp.setControlKeepAliveTimeout(Duration.ofSeconds(30));
        ftp.setBufferSize(64 * 1024);
        ftp.setPassiveNatWorkaroundStrategy(ignored -> ftp.getRemoteAddress().getHostAddress());
        try {
            ftp.connect(cfg.host(), cfg.port());
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                throw new IOException("serverul a refuzat conexiunea: " + ftp.getReplyString().trim());
            }
            if (!ftp.login(cfg.username(), cfg.password())) {
                throw new IOException("autentificare esuata");
            }
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            ftp.setSoTimeout((int) cfg.dataTimeout().toMillis());
            return ftp;
        } catch (IOException e) {
            disconnectQuietly(ftp);
            throw new StorageUnavailableException(type(), "nu ma pot conecta: " + e.getMessage(), e);
        }
    }

    private void ensureParentDirectories(FTPClient ftp, String key) throws IOException {
        int lastSlash = key.lastIndexOf('/');
        if (lastSlash <= 0) {
            return;
        }
        String[] parts = key.substring(0, lastSlash).split("/");
        StringBuilder path = new StringBuilder();
        for (String part : parts) {
            if (!path.isEmpty()) {
                path.append('/');
            }
            path.append(part);
            ftp.makeDirectory(path.toString());
        }
    }

    private void discardIdle() {
        for (Idle candidate = idle.pollFirst(); candidate != null; candidate = idle.pollFirst()) {
            disconnectQuietly(candidate.client());
        }
    }

    @PreDestroy
    void close() {
        discardIdle();
    }

    private static void disconnectQuietly(FTPClient ftp) {
        if (ftp.isConnected()) {
            try {
                ftp.logout();
            } catch (IOException ignored) {
            }
            try {
                ftp.disconnect();
            } catch (IOException ignored) {
            }
        }
    }
}
