package com.ulp.features.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Caches a single {@link S3Client} built from the latest R2 settings.
 * Invalidated when an admin saves storage settings so the next call
 * rebuilds against the new credentials / endpoint.
 */
@Component
public class R2ClientHolder {

    private static final Logger log = LoggerFactory.getLogger(R2ClientHolder.class);

    private final AtomicReference<CachedClient> cached = new AtomicReference<>();

    /**
     * Returns a live client for the given config, rebuilding when the
     * fingerprint of credentials/endpoint/region changes.
     *
     * @return null when config is incomplete
     */
    public S3Client getOrCreate(R2Config config) {
        if (config == null || !config.isComplete()) {
            return null;
        }
        CachedClient current = cached.get();
        String fingerprint = config.fingerprint();
        if (current != null && fingerprint.equals(current.fingerprint)) {
            return current.client;
        }
        S3Client created = buildClient(config);
        CachedClient next = new CachedClient(fingerprint, created);
        CachedClient previous = cached.getAndSet(next);
        if (previous != null && previous.client != created) {
            closeQuietly(previous.client);
        }
        return created;
    }

    /** Drops the cached client so the next {@link #getOrCreate} rebuilds. */
    public void invalidate() {
        CachedClient previous = cached.getAndSet(null);
        if (previous != null) {
            closeQuietly(previous.client);
            log.info("R2 S3Client invalidated");
        }
    }

    private static S3Client buildClient(R2Config config) {
        AwsBasicCredentials creds = AwsBasicCredentials.create(
                config.accessKeyId(), config.secretAccessKey());
        S3Configuration s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();
        String region = (config.region() == null || config.region().isBlank())
                ? "auto" : config.region().trim();
        return S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .endpointOverride(URI.create(config.endpoint().trim()))
                .region(Region.of(region))
                .serviceConfiguration(s3Config)
                .build();
    }

    private static void closeQuietly(S3Client client) {
        try {
            client.close();
        } catch (RuntimeException ex) {
            log.warn("Failed to close previous R2 S3Client: {}", ex.getMessage());
        }
    }

    /** Immutable snapshot of the R2 connection settings used to build a client. */
    public record R2Config(
            String accessKeyId,
            String secretAccessKey,
            String bucket,
            String endpoint,
            String region
    ) {
        /** True when every required field is non-blank. */
        public boolean isComplete() {
            return isPresent(accessKeyId)
                    && isPresent(secretAccessKey)
                    && isPresent(bucket)
                    && isPresent(endpoint);
        }

        String fingerprint() {
            return String.join("|",
                    nullToEmpty(accessKeyId),
                    nullToEmpty(secretAccessKey),
                    nullToEmpty(bucket),
                    nullToEmpty(endpoint),
                    nullToEmpty(region));
        }

        private static boolean isPresent(String v) {
            return v != null && !v.isBlank();
        }

        private static String nullToEmpty(String v) {
            return v == null ? "" : v;
        }
    }

    private record CachedClient(String fingerprint, S3Client client) {
        CachedClient {
            Objects.requireNonNull(client, "client");
        }
    }
}
