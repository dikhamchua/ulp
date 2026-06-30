package com.ulp.features.lessons.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link YouTubeEmbedUrl}. Verifies the regex accepts the
 * four canonical shapes and rejects look-alike or unrelated URLs.
 */
class YouTubeEmbedUrlTest {

    @Test
    void watch_v_form_converts_to_embed() {
        String embed = YouTubeEmbedUrl.toEmbedUrl(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(embed).isEqualTo("https://www.youtube.com/embed/dQw4w9WgXcQ");
    }

    @Test
    void short_youtu_be_form_converts_to_embed() {
        String embed = YouTubeEmbedUrl.toEmbedUrl("https://youtu.be/dQw4w9WgXcQ");
        assertThat(embed).isEqualTo("https://www.youtube.com/embed/dQw4w9WgXcQ");
    }

    @Test
    void mobile_youtube_form_converts_to_embed() {
        String embed = YouTubeEmbedUrl.toEmbedUrl(
                "https://m.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(embed).isEqualTo("https://www.youtube.com/embed/dQw4w9WgXcQ");
    }

    @Test
    void embed_form_is_already_embed() {
        String embed = YouTubeEmbedUrl.toEmbedUrl(
                "https://www.youtube.com/embed/dQw4w9WgXcQ");
        assertThat(embed).isEqualTo("https://www.youtube.com/embed/dQw4w9WgXcQ");
    }

    @Test
    void watch_with_extra_query_params_is_accepted() {
        String embed = YouTubeEmbedUrl.toEmbedUrl(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s");
        assertThat(embed).isEqualTo("https://www.youtube.com/embed/dQw4w9WgXcQ");
    }

    @Test
    void non_youtube_url_is_rejected() {
        assertThat(YouTubeEmbedUrl.matches("https://malicious.example/x")).isFalse();
        assertThatThrownBy(() -> YouTubeEmbedUrl.toEmbedUrl("https://malicious.example/x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void vimeo_url_is_rejected_by_youtube_helper() {
        assertThat(YouTubeEmbedUrl.matches("https://vimeo.com/123456789")).isFalse();
    }

    @Test
    void null_url_does_not_match() {
        assertThat(YouTubeEmbedUrl.matches(null)).isFalse();
        assertThatThrownBy(() -> YouTubeEmbedUrl.toEmbedUrl(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void look_alike_domain_is_rejected() {
        // The path looks right but the host is wrong — defends against
        // open-redirect bait that abuses YouTube branding.
        assertThat(YouTubeEmbedUrl.matches(
                "https://youtube.com.attacker.example/watch?v=abc123"))
                .isFalse();
    }
}
