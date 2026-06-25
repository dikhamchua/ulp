package com.ulp.features.classes.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates a 5-character class code for use by lecturers. Format:
 * <pre>
 *   [random]^4 + timestamp-derived[1]
 *   drawn from alphabet "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" (32 chars)
 * </pre>
 *
 * <p>The alphabet deliberately omits {@code I}, {@code O}, {@code 0}, and {@code 1}
 * to prevent visual ambiguity when reading or typing codes manually.
 * The 5th character is derived via {@link Math#floorMod} to guarantee a non-negative
 * index even if {@code currentTimeMillis()} wraps at a boundary
 * (does not occur in practice, but is safer).
 *
 * <p>Collision handling lives in {@code ClassesService} — it retries
 * up to 3 times with the INSERT query.
 */
@Component
public class ClassCodeGenerator {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 5;
    static final int RANDOM_PART_LENGTH = 4;

    private final SecureRandom random = new SecureRandom();

    /** Generates a new random class code. */
    public String generate() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < RANDOM_PART_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        int tsIdx = Math.floorMod(System.currentTimeMillis(), ALPHABET.length());
        sb.append(ALPHABET.charAt(tsIdx));
        return sb.toString();
    }
}
