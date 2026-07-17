package com.ulp.common;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * Static helper that strips unsafe markup from HTML before it is persisted.
 *
 * <p>Lives in {@code common} so every feature that accepts user-authored
 * HTML (lesson body, comments, board posts) can reuse the same policy
 * from a single entry point without dragging a bean dependency around.
 *
 * <p>Allowed tags: {@code h1–h6}, {@code p}, {@code br}, {@code hr},
 * {@code strong}, {@code b}, {@code em}, {@code i}, {@code u}, {@code s},
 * {@code blockquote}, {@code pre}, {@code code}, {@code ol}, {@code ul},
 * {@code li}, {@code a}, {@code img}.
 *
 * <p>Allowed attributes:
 * <ul>
 *   <li>{@code a}: {@code href} (only {@code http}, {@code https},
 *       {@code mailto}), {@code target}, {@code rel}.</li>
 *   <li>{@code img}: {@code src} (only {@code data:image/*}, {@code http},
 *       {@code https}), {@code alt}, {@code width}, {@code height}.</li>
 *   <li>Everything else is stripped — including {@code onclick},
 *       inline {@code style}, and any {@code javascript:} URL scheme.</li>
 * </ul>
 */
public final class HtmlSanitizer {

    /**
     * Shared Safelist instance. Jsoup's {@link Safelist} is documented as
     * being safe for read-only reuse once configured, so we keep a single
     * static copy instead of rebuilding it on every call.
     */
    private static final Safelist SAFELIST = buildSafelist();

    private HtmlSanitizer() {
        // utility holder
    }

    /**
     * Strips disallowed tags / attributes / URL schemes from the supplied
     * HTML body and returns the cleaned markup.
     *
     * @param html raw HTML straight from the form submission; may be
     *             {@code null} or blank
     * @return the sanitised HTML, or an empty string when {@code html} is
     *         {@code null} / blank
     */
    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        // Disable pretty-print so sanitize() is idempotent. Default Jsoup
        // output inserts \n + indentation between block elements, which Quill
        // later re-parses as empty <p><br></p> paragraphs — see bug fix for
        // lesson rich-text round-trip drift.
        //
        // A non-empty base URI is required for preserveRelativeLinks to keep
        // server-uploaded paths like /uploads/exams/<uuid>.png (Quill img src).
        Document.OutputSettings settings = new Document.OutputSettings()
                .prettyPrint(false);
        return Jsoup.clean(html, "https://ulp.local", SAFELIST, settings);
    }

    private static Safelist buildSafelist() {
        Safelist list = new Safelist()
                .addTags("h1", "h2", "h3", "h4", "h5", "h6",
                         "p", "br", "hr",
                         "strong", "b", "em", "i", "u", "s",
                         "blockquote", "pre", "code",
                         "ol", "ul", "li",
                         "a", "img");

        list.addAttributes("a", "href", "target", "rel");
        list.addProtocols("a", "href", "http", "https", "mailto");

        list.addAttributes("img", "src", "alt", "width", "height");
        // Allow inline data URIs in addition to http(s) so a lecturer can
        // paste a small embedded image without the attachment pipeline.
        list.addProtocols("img", "src", "data", "http", "https");
        // Keep relative /uploads/... paths from server image upload endpoints.
        list.preserveRelativeLinks(true);

        return list;
    }
}
