package ro.ppc.filegrid.storage;

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

@Component
public class FtpStorageBackend implements StorageBackend {

    private static final Logger log = LoggerFactory.getLogger(FtpStorageBackend.class);
    private static final String PARTIAL_SUFFIX = ".part";

    private final FileGridProperties.Ftp cfg;

    public FtpStorageBackend(FileGridProperties props) {
        this.cfg = props.storage().ftp();
    }

    @Override
    public StorageType type() {
        return StorageType.FTP;
    }

    @Override
    public void write(String key, InputStream content, long length, String contentType) {
        FTPClient ftp = connect();
        try {
            ensureParentDirectories(ftp, key);
            String partial = key + PARTIAL_SUFFIX;
            if (!ftp.storeFile(partial, content)) {
                throw new StorageUnavailableException(type(), "STOR a esuat: " + ftp.getReplyString().trim(), null);
            }
            if (!ftp.rename(partial, key)) {
                ftp.deleteFile(partial);
                throw new StorageUnavailableException(type(), "RENAME a esuat: " + ftp.getReplyString().trim(), null);
            }
        } catch (IOException e) {
            throw new StorageUnavailableException(type(), "scrierea a esuat: " + e.getMessage(), e);
        } finally {
            disconnectQuietly(ftp);
        }
    }

    @Override
    public InputStream read(String key) {
        FTPClient ftp = connect();
        try {
            InputStream raw = ftp.retrieveFileStream(key);
            if (raw == null) {
                int reply = ftp.getReplyCode();
                disconnectQuietly(ftp);
                if (reply == FTPReply.FILE_UNAVAILABLE) {
                    throw new ObjectNotFoundException("Fisierul " + key + " nu exista pe FTP");
                }
                throw new StorageUnavailableException(type(), "RETR a esuat cu codul " + reply, null);
            }
            return new FilterInputStream(raw) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                        ftp.completePendingCommand();
                    } finally {
                        disconnectQuietly(ftp);
                    }
                }
            };
        } catch (IOException e) {
            disconnectQuietly(ftp);
            throw new StorageUnavailableException(type(), "citirea a esuat: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        FTPClient ftp = connect();
        try {
            ftp.deleteFile(key);
            ftp.deleteFile(key + PARTIAL_SUFFIX);
        } catch (IOException e) {
            throw new StorageUnavailableException(type(), "stergerea a esuat: " + e.getMessage(), e);
        } finally {
            disconnectQuietly(ftp);
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
            return false;
        }
    }

    private FTPClient connect() {
        FTPClient ftp = new FTPClient();
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
