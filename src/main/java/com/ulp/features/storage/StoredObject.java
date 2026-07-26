package com.ulp.features.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Opened object stream returned by {@link ObjectStorage#open} /
 * {@link ObjectStorage#openRange}. Callers must close the handle to
 * release the underlying stream / HTTP connection.
 */
public final class StoredObject implements Closeable {

    private final InputStream inputStream;
    private final long contentLength;
    private final String contentType;

    public StoredObject(InputStream inputStream, long contentLength, String contentType) {
        this.inputStream = inputStream;
        this.contentLength = contentLength;
        this.contentType = contentType;
    }

    public InputStream inputStream() {
        return inputStream;
    }

    public long contentLength() {
        return contentLength;
    }

    public String contentType() {
        return contentType;
    }

    @Override
    public void close() throws IOException {
        if (inputStream != null) {
            inputStream.close();
        }
    }
}
