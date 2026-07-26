package com.ulp.features.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import static com.ulp.common.IConstant.MSG_STORAGE_R2_NOT_CONFIGURED;

/**
 * Cloudflare R2-backed {@link ObjectStorage} via the AWS SDK v2 S3 client.
 * Put streams through a temp file so large videos are not buffered in heap,
 * and retries up to 2 times (3 attempts total) before failing.
 */
public class R2ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(R2ObjectStorage.class);
    private static final int MAX_PUT_ATTEMPTS = 3;

    private final Supplier<S3Client> clientSupplier;
    private final Supplier<String> bucketSupplier;

    public R2ObjectStorage(Supplier<S3Client> clientSupplier, Supplier<String> bucketSupplier) {
        this.clientSupplier = clientSupplier;
        this.bucketSupplier = bucketSupplier;
    }

    @Override
    public void put(String key, InputStream data, String contentType, long contentLength)
            throws IOException {
        String safeKey = StorageKeys.requireSafeKey(key);
        S3Client client = requireClient();
        String bucket = requireBucket();
        String type = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;

        // Spill to temp so retries re-read without holding the whole object in RAM.
        Path temp = Files.createTempFile("ulp-r2-put-", ".bin");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                data.transferTo(out);
            }
            long length = Files.size(temp);
            IOException last = null;
            for (int attempt = 1; attempt <= MAX_PUT_ATTEMPTS; attempt++) {
                try {
                    PutObjectRequest req = PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(safeKey)
                            .contentType(type)
                            .contentLength(length)
                            .build();
                    client.putObject(req, RequestBody.fromFile(temp));
                    return;
                } catch (RuntimeException ex) {
                    last = new IOException(
                            "R2 put failed for key " + safeKey + " (attempt " + attempt + ")", ex);
                    log.warn("R2 put attempt {}/{} failed for {}: {}",
                            attempt, MAX_PUT_ATTEMPTS, safeKey, ex.getMessage());
                }
            }
            throw last;
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                log.debug("Failed to delete R2 put temp file: {}", cleanup.getMessage());
            }
        }
    }

    @Override
    public void delete(String key) throws IOException {
        String safeKey = StorageKeys.requireSafeKey(key);
        try {
            requireClient().deleteObject(DeleteObjectRequest.builder()
                    .bucket(requireBucket())
                    .key(safeKey)
                    .build());
        } catch (RuntimeException ex) {
            throw new IOException("R2 delete failed for key " + safeKey, ex);
        }
    }

    @Override
    public boolean exists(String key) {
        String safeKey;
        try {
            safeKey = StorageKeys.requireSafeKey(key);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        S3Client client = clientSupplier.get();
        String bucket = bucketSupplier.get();
        if (client == null || bucket == null || bucket.isBlank()) {
            return false;
        }
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(safeKey).build());
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) return false;
            log.warn("R2 exists check failed for {}: {}", safeKey, ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            log.warn("R2 exists check failed for {}: {}", safeKey, ex.getMessage());
            return false;
        }
    }

    @Override
    public StoredObject open(String key) throws IOException {
        String safeKey = StorageKeys.requireSafeKey(key);
        try {
            ResponseInputStream<GetObjectResponse> resp = requireClient().getObject(
                    GetObjectRequest.builder().bucket(requireBucket()).key(safeKey).build());
            GetObjectResponse meta = resp.response();
            long length = meta.contentLength() == null ? -1L : meta.contentLength();
            return new StoredObject(resp, length, meta.contentType());
        } catch (NoSuchKeyException ex) {
            throw new IOException("Object not found: " + safeKey, ex);
        } catch (RuntimeException ex) {
            throw new IOException("R2 open failed for key " + safeKey, ex);
        }
    }

    @Override
    public StoredObject openRange(String key, long start, long end) throws IOException {
        String safeKey = StorageKeys.requireSafeKey(key);
        if (start < 0 || end < start) {
            throw new IOException("Invalid range " + start + "-" + end);
        }
        String rangeHeader = "bytes=" + start + "-" + end;
        try {
            ResponseInputStream<GetObjectResponse> resp = requireClient().getObject(
                    GetObjectRequest.builder()
                            .bucket(requireBucket())
                            .key(safeKey)
                            .range(rangeHeader)
                            .build());
            GetObjectResponse meta = resp.response();
            long length = meta.contentLength() == null ? (end - start + 1) : meta.contentLength();
            return new StoredObject(resp, length, meta.contentType());
        } catch (NoSuchKeyException ex) {
            throw new IOException("Object not found: " + safeKey, ex);
        } catch (RuntimeException ex) {
            throw new IOException("R2 openRange failed for key " + safeKey, ex);
        }
    }

    @Override
    public void copy(String sourceKey, String destKey) throws IOException {
        String src = StorageKeys.requireSafeKey(sourceKey);
        String dest = StorageKeys.requireSafeKey(destKey);
        String bucket = requireBucket();
        try {
            requireClient().copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket)
                    .sourceKey(src)
                    .destinationBucket(bucket)
                    .destinationKey(dest)
                    .build());
        } catch (RuntimeException ex) {
            throw new IOException("R2 copy failed " + src + " -> " + dest, ex);
        }
    }

    private S3Client requireClient() {
        S3Client client = clientSupplier.get();
        if (client == null) {
            throw new StorageNotConfiguredException(MSG_STORAGE_R2_NOT_CONFIGURED);
        }
        return client;
    }

    private String requireBucket() {
        String bucket = bucketSupplier.get();
        if (bucket == null || bucket.isBlank()) {
            throw new StorageNotConfiguredException(MSG_STORAGE_R2_NOT_CONFIGURED);
        }
        return bucket;
    }
}
