package com.ulp.classes.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates invite-token values for {@code class_invite_codes} rows.
 *
 * <p>Two token shapes are produced:
 * <ul>
 *   <li><b>CODE</b> — 6 chars from the ambiguity-free alphabet
 *       {@code ABCDEFGHJKLMNPQRSTUVWXYZ23456789} (32 chars). The
 *       letters {@code I}, {@code O} and the digits {@code 0},
 *       {@code 1} are intentionally excluded to prevent visual
 *       ambiguity when read or typed by hand. Same alphabet as
 *       {@link ClassCodeGenerator} — but CODE here is 6 chars long
 *       (vs 5 for class identifier), allowing the two namespaces to
 *       be distinguished at a glance.</li>
 *   <li><b>LINK</b> — 32 chars base64url-safe ({@code [A-Za-z0-9_-]})
 *       built from 24 random bytes (192 bits of entropy) via
 *       {@link SecureRandom}. Padding is stripped.</li>
 * </ul>
 *
 * <p>Note: this class produces tokens; uniqueness is enforced by the
 * DB unique index {@code idx_ic_code} on
 * {@code class_invite_codes.code}. {@code InviteCodeService} handles
 * retries on collision.
 */
@Component
public class InviteTokenGenerator {

    /**
     * Ambiguity-free alphabet shared with {@link ClassCodeGenerator}.
     * Identical letters / digits omitted: {@code I/O/0/1}.
     */
    static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Generated CODE-token length in chars. Must stay in sync with the literal in {@link #CODE_REGEX}. */
    public static final int CODE_LENGTH = 6;

    /** Number of random bytes that feed the base64url LINK token. */
    static final int LINK_RANDOM_BYTES = 24;

    /** Final LINK length after stripping base64 padding. Must stay in sync with the literal in {@link #LINK_REGEX}. */
    public static final int LINK_LENGTH = 32;

    /**
     * Regex matching a valid invite CODE submission (server-side validation).
     * Java annotation arguments require compile-time constants, so the length
     * literal must mirror {@link #CODE_LENGTH}. Keep the two in sync.
     */
    public static final String CODE_REGEX = "^[A-Za-z0-9]{6}$";

    /**
     * Regex matching a valid invite LINK token (server-side validation).
     * Java annotation arguments require compile-time constants, so the length
     * literal must mirror {@link #LINK_LENGTH}. Keep the two in sync.
     */
    public static final String LINK_REGEX = "^[A-Za-z0-9_-]{32}$";

    private final SecureRandom random = new SecureRandom();

    /**
     * Generates a fresh 6-character CODE token. Every character is
     * drawn from {@link #CODE_ALPHABET}.
     *
     * @return a 6-char invite CODE token
     */
    public String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * Generates a fresh 32-character LINK token from 24 random bytes
     * encoded as base64url (no padding).
     *
     * @return a 32-char base64url-safe invite LINK token
     */
    public String generateLink() {
        byte[] bytes = new byte[LINK_RANDOM_BYTES];
        random.nextBytes(bytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // 24 bytes -> 32 base64 chars without padding; defensive trim
        // in case the JDK ever changes encoder behavior.
        if (encoded.length() > LINK_LENGTH) {
            encoded = encoded.substring(0, LINK_LENGTH);
        }
        return encoded;
    }
}
