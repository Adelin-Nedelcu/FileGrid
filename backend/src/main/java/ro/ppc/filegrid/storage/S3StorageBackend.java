package ro.ppc.filegrid.storage;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.FileGridProperties;
import ro.ppc.filegrid.domain.StorageType;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class S3StorageBackend implements StorageBackend {

    private static final Logger log = LoggerFactory.getLogger(S3StorageBackend.class);

    private final S3Client client;
    private final String bucket;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    public S3StorageBackend(FileGridProperties props) {
        FileGridProperties.S3 cfg = props.storage().s3();
        this.bucket = cfg.bucket();
        this.client = S3Client.builder()
                .endpointOverride(URI.create(cfg.endpoint()))
                .region(Region.of(cfg.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(cfg.connectTimeout())
                        .socketTimeout(cfg.socketTimeout())
                        .maxConnections(cfg.maxConnections()))
                .build();
    }

    @Override
    public StorageType type() {
        return StorageType.S3;
    }

    @Override
    public boolean requiresKnownLength() {
        return true;
    }

    @Override
    public void write(String key, InputStream content, long length, String contentType) {
        if (length < 0) {
            throw new IllegalArgumentException("S3 are nevoie de dimensiunea exacta a fisierului");
        }
        ensureBucket();
        try {
            client.putObject(b -> b.bucket(bucket).key(key).contentType(contentType).contentLength(length),
                    RequestBody.fromInputStream(content, length));
        } catch (SdkException e) {
            throw new StorageUnavailableException(type(), "scrierea a esuat: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream read(String key) {
        try {
            return client.getObject(b -> b.bucket(bucket).key(key));
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException("Obiectul " + key + " nu exista in S3");
        } catch (SdkException e) {
            throw new StorageUnavailableException(type(), "citirea a esuat: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw new StorageUnavailableException(type(), "stergerea a esuat: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean ping() {
        try {
            ensureBucket();
            client.headBucket(b -> b.bucket(bucket));
            return true;
        } catch (RuntimeException e) {
            log.debug("S3 ping esuat: {}", e.getMessage());
            return false;
        }
    }

    private void ensureBucket() {
        if (bucketReady.get()) {
            return;
        }
        try {
            client.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            createBucket();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                createBucket();
            } else {
                throw new StorageUnavailableException(type(), "bucket indisponibil: " + e.getMessage(), e);
            }
        } catch (SdkException e) {
            throw new StorageUnavailableException(type(), "nu ma pot conecta: " + e.getMessage(), e);
        }
        bucketReady.set(true);
    }

    private void createBucket() {
        log.info("Creez bucket-ul S3 '{}'", bucket);
        try {
            client.createBucket(b -> b.bucket(bucket));
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
        } catch (SdkException e) {
            throw new StorageUnavailableException(type(), "nu pot crea bucket-ul: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    void close() {
        client.close();
    }
}
