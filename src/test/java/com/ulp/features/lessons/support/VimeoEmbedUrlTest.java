package com.ulp.features.lessons.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link VimeoEmbedUrl}. Verifies the regex accepts the
 * canonical numeric Vimeo URL shapes and rejects everything else.
 */
class VimeoEmbedUrlTest {

    @Test
    void canonical_vimeo_url_converts_to_player_embed() {
        String embed = VimeoEmbedUrl.toEmbedUrl("https://vimeo.com/123456789");
        assertThat(embed).isEqualTo("https://player.vimeo.com/video/123456789");
    }

    @Test
    void www_vimeo_url_converts_to_player_embed() {
        String embed = VimeoEmbedUrl.toEmbedUrl("https://www.vimeo.com/123456789");
        assertThat(embed).isEqualTo("https://player.vimeo.com/video/123456789");
    }

    @Test
    void player_vimeo_video_url_passes_through() {
        String embed = VimeoEmbedUrl.toEmbedUrl(
                "https://player.vimeo.com/video/123456789");
        assertThat(embed).isEqualTo("https://player.vimeo.com/video/123456789");
    }

    @Test
    void canonical_with_extra_query_params_accepted() {
        String embed = VimeoEmbedUrl.toEmbedUrl(
                "https://vimeo.com/123456789?h=abcdef");
        assertThat(embed).isEqualTo("https://player.vimeo.com/video/123456789");
    }

    @Test
    void non_vimeo_url_is_rejected() {
        assertThat(VimeoEmbedUrl.matches("https://malicious.example/123456789")).isFalse();
        assertThatThrownBy(() -> VimeoEmbedUrl.toEmbedUrl(
                "https://malicious.example/123456789"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void youtube_url_is_rejected_by_vimeo_helper() {
        assertThat(VimeoEmbedUrl.matches(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ")).isFalse();
    }

    @Test
    void non_numeric_path_is_rejected() {
        assertThat(VimeoEmbedUrl.matches("https://vimeo.com/channels/staffpicks"))
                .isFalse();
    }

    @Test
    void null_url_does_not_match() {
        assertThat(VimeoEmbedUrl.matches(null)).isFalse();
        assertThatThrownBy(() -> VimeoEmbedUrl.toEmbedUrl(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
