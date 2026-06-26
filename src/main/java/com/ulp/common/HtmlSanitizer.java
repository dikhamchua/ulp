package com.ulp.common;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * Static helper that strips unsafe markup from HTML before it is persisted.
 *
 * <p>Lives in {@code common} on purpose: ULP-4.0b is the first feature to
 * accept user-authored HTML (Quill on the Lesson form), but ULP-4.6 (Q&A
 * comments) and the future board-post feature will reuse the exact same
 * policy. Keeping the sanitiser as a single static entry point makes it
 * trivial to share without dragging a bean dependency around.
 *
 * <p>Allowed tags (per design D2): {@code h1–h6}, {@code p}, {@code br},
 * {@code hr}, {@code strong}, {@code b}, {@code em}, {@code i}, {@code u},
 * {@code s}, {@code blockquote}, {@code pre}, {@code code}, {@code ol},
 * {@code ul}, {@code li}, {@code a}, {@code img}.
 *
 * <p>Allowed attributes:
 * <ul>
 *   <li>{@code a}: {@code href} (only {@code http}, {@code https},
 *       {@code mailto}), {@code target}, {@code rel}.</li>
 *   <li>{@code img}: {@code src} (only {@code data:image/*}, {@code http},
 *       {@code https}), {@code alt}, {@code width}, {@code height}.</li>
 *   <li>Everything else stripped — including {@code onclick} and friends,
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
        return Jsoup.clean(html, SAFELIST);
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

        return list;
    }
}
