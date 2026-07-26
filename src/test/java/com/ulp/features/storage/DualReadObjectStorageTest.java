package com.ulp.features.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.ulp.common.IConstant.MSG_STORAGE_R2_NOT_CONFIGURED;
import static com.ulp.common.IConstant.STORAGE_PROVIDER_LOCAL;
import static com.ulp.common.IConstant.STORAGE_PROVIDER_R2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DualReadObjectStorage} write/read/delete rules.
 *
 * <p>R2 is stubbed by subclassing {@link R2ObjectStorage} and delegating to a
 * second {@link LocalObjectStorage} root so tests stay in-process.
 */
class DualReadObjectStorageTest {

    @TempDir
    Path localRoot;

    @TempDir
    Path r2Root;

    private LocalObjectStorage local;
    private LocalObjectStorage r2Side;
    private AtomicReference<String> provider;
    private AtomicBoolean r2Ready;
    private DualReadObjectStorage dual;

    @BeforeEach
    void setUp() {
        local = new LocalObjectStorage(localRoot);
        r2Side = new LocalObjectStorage(r2Root);
        provider = new AtomicReference<>(STORAGE_PROVIDER_LOCAL);
        r2Ready = new AtomicBoolean(true);
        dual = new DualReadObjectStorage(local, new LocalBackedR2(r2Side),
                provider::get, r2Ready::get);
    }

    @Test
    void writeLocal_whenProviderLocal() throws Exception {
        provider.set(STORAGE_PROVIDER_LOCAL);
        byte[] data = "local".getBytes(StandardCharsets.UTF_8);
        dual.put("a/x.bin", new ByteArrayInputStream(data), "application/octet-stream", data.length);

        assertThat(local.exists("a/x.bin")).isTrue();
        assertThat(r2Side.exists("a/x.bin")).isFalse();
    }

    @Test
    void writeR2_whenProviderR2AndReady() throws Exception {
        provider.set(STORAGE_PROVIDER_R2);
        r2Ready.set(true);
        byte[] data = "r2".getBytes(StandardCharsets.UTF_8);
        dual.put("b/y.bin", new ByteArrayInputStream(data), "application/octet-stream", data.length);

        assertThat(r2Side.exists("b/y.bin")).isTrue();
        assertThat(local.exists("b/y.bin")).isFalse();
    }

    @Test
    void writeR2_failsClosed_whenNotReady() {
        provider.set(STORAGE_PROVIDER_R2);
        r2Ready.set(false);
        byte[] data = "nope".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() ->
                dual.put("c/z.bin", new ByteArrayInputStream(data), "application/octet-stream", data.length))
                .isInstanceOf(StorageNotConfiguredException.class)
                .hasMessageContaining(MSG_STORAGE_R2_NOT_CONFIGURED);
        assertThat(local.exists("c/z.bin")).isFalse();
    }

    @Test
    void read_prefersLocalOverR2() throws Exception {
        byte[] localData = "from-local".getBytes(StandardCharsets.UTF_8);
        byte[] r2Data = "from-r2".getBytes(StandardCharsets.UTF_8);
        local.put("d/same.bin", new ByteArrayInputStream(localData), "text/plain", localData.length);
        r2Side.put("d/same.bin", new ByteArrayInputStream(r2Data), "text/plain", r2Data.length);
        r2Ready.set(true);

        try (StoredObject obj = dual.open("d/same.bin")) {
            assertThat(obj.inputStream().readAllBytes()).isEqualTo(localData);
        }
    }

    @Test
    void read_fallsBackToR2() throws Exception {
        byte[] r2Data = "only-r2".getBytes(StandardCharsets.UTF_8);
        r2Side.put("e/only.bin", new ByteArrayInputStream(r2Data), "text/plain", r2Data.length);
        r2Ready.set(true);

        try (StoredObject obj = dual.open("e/only.bin")) {
            assertThat(obj.inputStream().readAllBytes()).isEqualTo(r2Data);
        }
    }

    @Test
    void delete_bestEffortBoth() throws Exception {
        byte[] data = "both".getBytes(StandardCharsets.UTF_8);
        local.put("f/x.bin", new ByteArrayInputStream(data), "text/plain", data.length);
        r2Side.put("f/x.bin", new ByteArrayInputStream(data), "text/plain", data.length);
        r2Ready.set(true);

        dual.delete("f/x.bin");

        assertThat(local.exists("f/x.bin")).isFalse();
        assertThat(r2Side.exists("f/x.bin")).isFalse();
    }

    /** R2 stand-in backed by a second local root (no AWS SDK). */
    private static final class LocalBackedR2 extends R2ObjectStorage {
        private final LocalObjectStorage backend;

        LocalBackedR2(LocalObjectStorage backend) {
            super(() -> null, () -> "test-bucket");
            this.backend = backend;
        }

        @Override
        public void put(String key, InputStream data, String contentType, long contentLength)
                throws IOException {
            backend.put(key, data, contentType, contentLength);
        }

        @Override
        public void delete(String key) throws IOException {
            backend.delete(key);
        }

        @Override
        public boolean exists(String key) {
            return backend.exists(key);
        }

        @Override
        public StoredObject open(String key) throws IOException {
            return backend.open(key);
        }

        @Override
        public StoredObject openRange(String key, long start, long end) throws IOException {
            return backend.openRange(key, start, end);
        }

        @Override
        public void copy(String sourceKey, String destKey) throws IOException {
            backend.copy(sourceKey, destKey);
        }
    }
}
