package com.ulp.features.storage;

/**
 * Thrown when the active storage provider is R2 but credentials / bucket /
 * endpoint are incomplete. Fail-closed: callers must surface a clear
 * Vietnamese message and must NOT silently fall back to local disk.
 */
public class StorageNotConfiguredException extends RuntimeException {

    public StorageNotConfiguredException(String message) {
        super(message);
    }

    public StorageNotConfiguredException(String message, Throwable cause) {
        super(message, cause);
    }
}
