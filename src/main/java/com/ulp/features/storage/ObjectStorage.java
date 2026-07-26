package com.ulp.features.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Backend-agnostic object storage contract used by every upload family
 * (avatar, exam image, lesson attachment/video, library).
 *
 * <p>Keys are relative paths without a leading slash (e.g.
 * {@code avatars/uuid.jpg}, {@code lessons/1/video/uuid.mp4}).
 * Implementations MUST reject path traversal ({@code ..}, leading
 * {@code /}, backslash).
 */
public interface ObjectStorage {

    /**
     * Writes {@code data} under {@code key}, replacing any previous object.
     *
     * @param contentLength exact byte length of {@code data}; required by S3-style APIs
     */
    void put(String key, InputStream data, String contentType, long contentLength) throws IOException;

    /** Deletes {@code key} when present; missing keys are not an error. */
    void delete(String key) throws IOException;

    /** True when an object exists for {@code key}. */
    boolean exists(String key);

    /** Opens the full object stream for {@code key}. */
    StoredObject open(String key) throws IOException;

    /**
     * Opens a byte range {@code [start, end]} inclusive. Callers that only
     * need a prefix should pass a finite {@code end}.
     */
    StoredObject openRange(String key, long start, long end) throws IOException;

    /** Server-side copy of bytes from {@code sourceKey} to {@code destKey}. */
    void copy(String sourceKey, String destKey) throws IOException;
}
