package com.ulp.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link Slugify} — Vietnamese diacritics, đ/Đ, symbol
 * collapsing, edge hyphens, and the empty-input fallback.
 */
class SlugifyTest {

    @Test
    void strips_vietnamese_diacritics() {
        assertThat(Slugify.slugify("Lập trình")).isEqualTo("lap-trinh");
        assertThat(Slugify.slugify("Cơ sở dữ liệu")).isEqualTo("co-so-du-lieu");
        assertThat(Slugify.slugify("Tiếng Anh")).isEqualTo("tieng-anh");
    }

    @Test
    void maps_d_stroke_to_d() {
        assertThat(Slugify.slugify("Đà Nẵng")).isEqualTo("da-nang");
        assertThat(Slugify.slugify("đường")).isEqualTo("duong");
    }

    @Test
    void collapses_symbols_and_multiple_spaces() {
        assertThat(Slugify.slugify("C#/.NET")).isEqualTo("c-net");
        assertThat(Slugify.slugify("Web    Development")).isEqualTo("web-development");
        assertThat(Slugify.slugify("SQL & Database")).isEqualTo("sql-database");
    }

    @Test
    void trims_leading_and_trailing_hyphens() {
        assertThat(Slugify.slugify("  --Hello--  ")).isEqualTo("hello");
        assertThat(Slugify.slugify("!!!Java!!!")).isEqualTo("java");
    }

    @Test
    void blank_or_symbol_only_input_yields_safe_fallback() {
        assertThat(Slugify.slugify(null)).isEqualTo(Slugify.FALLBACK);
        assertThat(Slugify.slugify("")).isEqualTo(Slugify.FALLBACK);
        assertThat(Slugify.slugify("   ")).isEqualTo(Slugify.FALLBACK);
        assertThat(Slugify.slugify("###")).isEqualTo(Slugify.FALLBACK);
        assertThat(Slugify.slugify(Slugify.FALLBACK)).isNotBlank();
    }
}
