package com.ulp.features.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Disk-backed {@link ObjectStorage} under a configurable root directory
 * (typically {@code app.upload.dir}). Keys map 1:1 to relative paths.
 */
public class LocalObjectStorage implements ObjectStorage {

    private final Path root;

    public LocalObjectStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create upload root: " + this.root, e);
        }
    }

    /** Absolute filesystem root this store writes under. */
    public Path root() {
        return root;
    }

    @Override
    public void put(String key, InputStream data, String contentType, long contentLength)
            throws IOException {
        Path dest = resolve(key);
        Files.createDirectories(dest.getParent());
        // Stream copy — never buffer the whole payload (video can be 200 MB).
        try (OutputStream out = Files.newOutputStream(dest)) {
            data.transferTo(out);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        Path target = resolve(key);
        Files.deleteIfExists(target);
    }

    @Override
    public boolean exists(String key) {
        try {
            return Files.isRegularFile(resolve(key));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override
    public StoredObject open(String key) throws IOException {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            throw new IOException("Object not found: " + key);
        }
        long size = Files.size(path);
        String type = Files.probeContentType(path);
        return new StoredObject(Files.newInputStream(path), size, type);
    }

    @Override
    public StoredObject openRange(String key, long start, long end) throws IOException {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            throw new IOException("Object not found: " + key);
        }
        long size = Files.size(path);
        if (start < 0 || start >= size || end < start) {
            throw new IOException("Invalid range " + start + "-" + end + " for size " + size);
        }
        long cappedEnd = Math.min(end, size - 1);
        long length = cappedEnd - start + 1;
        InputStream full = Files.newInputStream(path);
        long skipped = full.skip(start);
        if (skipped < start) {
            full.close();
            throw new IOException("Failed to seek to range start " + start);
        }
        // Limit stream so callers cannot read past the requested end.
        InputStream limited = new LimitedInputStream(full, length);
        String type = Files.probeContentType(path);
        return new StoredObject(limited, length, type);
    }

    @Override
    public void copy(String sourceKey, String destKey) throws IOException {
        Path source = resolve(sourceKey);
        Path dest = resolve(destKey);
        if (!Files.isRegularFile(source)) {
            throw new IOException("Source object not found: " + sourceKey);
        }
        Files.createDirectories(dest.getParent());
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path resolve(String key) {
        String safe = StorageKeys.requireSafeKey(key);
        Path resolved = root.resolve(safe).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes storage root: " + key);
        }
        return resolved;
    }

    /** InputStream that returns EOF after {@code remaining} bytes and closes the delegate. */
    static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        LimitedInputStream(InputStream delegate, long remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = delegate.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            int n = delegate.read(b, off, toRead);
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
