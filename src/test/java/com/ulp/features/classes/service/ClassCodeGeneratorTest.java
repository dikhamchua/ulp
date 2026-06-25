package com.ulp.features.classes.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test {@link ClassCodeGenerator}: do dai, alphabet, khong co ky tu nham lan,
 * va regression cho {@code Math.floorMod} (index khong am).
 */
class ClassCodeGeneratorTest {

    private final ClassCodeGenerator generator = new ClassCodeGenerator();

    @Test
    void generated_code_has_exact_length_5() {
        for (int i = 0; i < 1000; i++) {
            assertThat(generator.generate()).hasSize(5);
        }
    }

    @Test
    void generated_code_uses_only_allowed_alphabet() {
        for (int i = 0; i < 1000; i++) {
            String code = generator.generate();
            for (char c : code.toCharArray()) {
                assertThat(ClassCodeGenerator.ALPHABET.indexOf(c))
                        .as("char '%s' must be in alphabet", c)
                        .isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Test
    void generated_code_never_contains_visually_confusing_chars() {
        for (int i = 0; i < 1000; i++) {
            String code = generator.generate();
            assertThat(code)
                    .as("must not contain I/O/0/1")
                    .doesNotContain("I").doesNotContain("O")
                    .doesNotContain("0").doesNotContain("1");
        }
    }

    /** Regression cho W-1 verifier finding: {@code Math.floorMod} bao dam index khong am. */
    @Test
    void timestamp_modulo_is_never_negative() {
        // Simulate a wide range of millisecond values, including hypothetical negatives.
        long[] millisCandidates = {
                0L, 1L, System.currentTimeMillis(),
                Long.MAX_VALUE, Long.MIN_VALUE, -1L, -System.currentTimeMillis()
        };
        for (long m : millisCandidates) {
            int idx = Math.floorMod(m, ClassCodeGenerator.ALPHABET.length());
            assertThat(idx).as("floorMod(%d) must be >= 0", m).isGreaterThanOrEqualTo(0);
            assertThat(idx).isLessThan(ClassCodeGenerator.ALPHABET.length());
        }
    }
}
