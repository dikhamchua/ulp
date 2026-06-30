package com.ulp.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HtmlSanitizer}. The policy is documented in
 * the {@code add-lesson-crud} design D2 — verify the four canonical
 * payloads from the spec (script tag, event handler, data-URI image,
 * {@code javascript:} URL).
 */
class HtmlSanitizerTest {

    @Test
    void blank_input_returns_empty_string() {
        assertThat(HtmlSanitizer.sanitize(null)).isEmpty();
        assertThat(HtmlSanitizer.sanitize("")).isEmpty();
        assertThat(HtmlSanitizer.sanitize("   ")).isEmpty();
    }

    @Test
    void script_tag_is_stripped() {
        String cleaned = HtmlSanitizer.sanitize("<p>Safe</p><script>alert(1)</script>");
        assertThat(cleaned).contains("<p>Safe</p>");
        assertThat(cleaned).doesNotContainIgnoringCase("<script");
        assertThat(cleaned).doesNotContain("alert(1)");
    }

    @Test
    void event_handler_attribute_is_stripped() {
        String cleaned = HtmlSanitizer.sanitize("<p onclick=\"evil()\">Click</p>");
        assertThat(cleaned).contains("<p>Click</p>");
        assertThat(cleaned).doesNotContainIgnoringCase("onclick");
    }

    @Test
    void javascript_url_is_dropped() {
        String cleaned = HtmlSanitizer.sanitize("<a href=\"javascript:alert(1)\">x</a>");
        assertThat(cleaned).doesNotContainIgnoringCase("javascript:");
    }

    @Test
    void data_uri_image_is_preserved() {
        String html = "<p>See <img src=\"data:image/png;base64,iVBORw0KGgoAAAA\" alt=\"x\"></p>";
        String cleaned = HtmlSanitizer.sanitize(html);
        assertThat(cleaned).contains("data:image/png;base64,iVBORw0KGgoAAAA");
        assertThat(cleaned).contains("alt=\"x\"");
    }

    @Test
    void http_link_with_target_is_preserved() {
        String cleaned = HtmlSanitizer.sanitize(
                "<a href=\"https://example.com\" target=\"_blank\">site</a>");
        assertThat(cleaned).contains("href=\"https://example.com\"");
        assertThat(cleaned).contains("target=\"_blank\"");
    }

    @Test
    void sanitize_is_idempotent_across_block_paragraphs() {
        // Round-trip test: sanitizing the same HTML twice must yield the
        // exact same string. Guards against the Jsoup prettyPrint drift bug
        // where each call inserted \n between <p> tags, causing Quill to
        // later re-parse them as empty paragraphs and accumulate blank lines.
        String input = "<p>First paragraph</p><p>Second paragraph</p>";
        String once = HtmlSanitizer.sanitize(input);
        String twice = HtmlSanitizer.sanitize(once);
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void sanitize_does_not_insert_newlines_between_blocks() {
        // Block elements must sit directly next to each other — no \n,
        // no indentation. Whitespace between blocks gets re-parsed by Quill
        // as empty paragraphs on the next form load.
        String cleaned = HtmlSanitizer.sanitize(
                "<p>A</p><p>B</p><h2>C</h2><ul><li>D</li></ul>");
        assertThat(cleaned).doesNotContain("\n");
        assertThat(cleaned).doesNotContain("  ");
    }

    @Test
    void sanitize_preserves_paragraph_structure_without_growth() {
        // Simulate three round-trips (lecturer saves the same lesson 3
        // times). Output length must stay constant; structure must not grow.
        String input = "<p>Đoạn 1</p><p>Đoạn 2</p><p>Đoạn 3</p>";
        String r1 = HtmlSanitizer.sanitize(input);
        String r2 = HtmlSanitizer.sanitize(r1);
        String r3 = HtmlSanitizer.sanitize(r2);
        assertThat(r2).isEqualTo(r1);
        assertThat(r3).isEqualTo(r1);
    }
}
