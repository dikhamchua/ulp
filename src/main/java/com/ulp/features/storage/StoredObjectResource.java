package com.ulp.features.storage;

import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

/**
 * Spring {@link Resource} adapter around an open {@link StoredObject}.
 * Closing the input stream closes the underlying stored object handle.
 */
public final class StoredObjectResource extends AbstractResource {

    private final StoredObject storedObject;
    private final String description;

    public StoredObjectResource(StoredObject storedObject) {
        this(storedObject, "stored object");
    }

    public StoredObjectResource(StoredObject storedObject, String description) {
        this.storedObject = storedObject;
        this.description = description == null ? "stored object" : description;
    }

    @Override
    @NonNull
    public String getDescription() {
        return description;
    }

    @Override
    @NonNull
    public InputStream getInputStream() {
        return new InputStream() {
            private final InputStream delegate = storedObject.inputStream();

            @Override
            public int read() throws IOException {
                return delegate.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return delegate.read(b, off, len);
            }

            @Override
            public void close() throws IOException {
                try {
                    delegate.close();
                } finally {
                    storedObject.close();
                }
            }
        };
    }

    @Override
    public long contentLength() {
        return storedObject.contentLength();
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isReadable() {
        return true;
    }

    @Override
    @Nullable
    public URL getURL() throws IOException {
        throw new IOException("URL not available for stored object");
    }

    @Override
    @Nullable
    public URI getURI() throws IOException {
        throw new IOException("URI not available for stored object");
    }

    @Override
    @NonNull
    public File getFile() throws IOException {
        throw new IOException("File not available for stored object");
    }
}
