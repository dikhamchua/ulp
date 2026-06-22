package com.ulp.classes.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Sinh ma lop hoc 5 ky tu (Lecturer-facing). Format:
 * <pre>
 *   [random]^4 + timestamp-derived[1]
 *   tu alphabet "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" (32 chars)
 * </pre>
 *
 * <p>Alphabet bo {@code I/O/0/1} de tranh nham lan khi doc/danh.
 * Vi tri thu 5 dung {@link Math#floorMod} de bao dam khong am du
 * {@code currentTimeMillis()} co the tra ve gia tri am o ranh gioi
 * (khong xay ra trong thuc te nhung an toan hon).
 *
 * <p>Logic xu ly collision nam o {@code ClassesService} — nho retry
 * len toi 3 lan voi cau truy van INSERT.
 */
@Component
public class ClassCodeGenerator {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 5;
    static final int RANDOM_PART_LENGTH = 4;

    private final SecureRandom random = new SecureRandom();

    /** Sinh mot ma lop ngau nhien moi. */
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
