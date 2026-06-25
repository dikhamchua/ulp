package com.ulp.features.classes.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link InviteTokenGenerator}.
 *
 * <p>Covers every assertion under
 * {@code class-invite-codes/spec.md} "CODE token format" and
 * "LINK token format" requirements.
 */
class InviteTokenGeneratorTest {

    private final InviteTokenGenerator generator = new InviteTokenGenerator();

    @Test
    void generate_code_returns_six_chars_from_allowed_alphabet() {
        for (int i = 0; i < 200; i++) {
            String code = generator.generateCode();
            assertThat(code).hasSize(6);
            assertThat(code).matches("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$");
        }
    }

    @Test
    void generate_link_returns_thirty_two_base64url_safe_chars() {
        for (int i = 0; i < 200; i++) {
            String link = generator.generateLink();
            assertThat(link).matches("^[A-Za-z0-9_-]{32}$");
        }
    }

    @Test
    void two_consecutive_link_calls_produce_different_values() {
        String a = generator.generateLink();
        String b = generator.generateLink();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void two_thousand_code_calls_show_low_collision_rate() {
        // Birthday: 32^6 = ~1.07 * 10^9 codes. With 2000 draws the
        // expected duplicate count is < 2. We allow up to 20 to keep
        // the test stable on slow machines. A genuinely broken
        // generator (e.g. always-same-seed) would saturate at 0
        // unique values, which this assertion still catches.
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        for (int i = 0; i < 2000; i++) {
            if (!seen.add(generator.generateCode())) {
                duplicates++;
            }
        }
        assertThat(duplicates).isLessThan(20);
    }

    @Test
    void code_alphabet_excludes_ambiguous_glyphs() {
        for (int i = 0; i < 500; i++) {
            String code = generator.generateCode();
            assertThat(code).doesNotContain("I").doesNotContain("O")
                    .doesNotContain("0").doesNotContain("1");
        }
    }
}
