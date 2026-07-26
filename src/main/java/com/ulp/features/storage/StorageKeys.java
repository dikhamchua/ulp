package com.ulp.features.storage;

import static com.ulp.common.IConstant.MSG_ATTACHMENT_INVALID;

/**
 * Shared key sanitisation for {@link ObjectStorage} implementations.
 * Rejects traversal and absolute paths so a malicious DB row cannot
 * escape the storage root / bucket prefix.
 */
public final class StorageKeys {

    private StorageKeys() {
    }

    /**
     * Validates and normalises a relative object key.
     *
     * @throws IllegalArgumentException when the key is blank, absolute,
     *                                  contains {@code ..}, or uses backslash
     */
    public static String requireSafeKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        String trimmed = key.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")
                || trimmed.contains("..") || trimmed.contains("\\")
                || trimmed.contains("//")) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        return trimmed;
    }
}
