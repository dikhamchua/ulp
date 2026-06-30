package com.ulp.features.lessons.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helper that recognises a canonical Vimeo URL and converts it to
 * the iframe-embed player URL.
 *
 * <p>Same rationale as {@link YouTubeEmbedUrl}: the lecturer types a
 * canonical URL, the database stores it verbatim, and the embed
 * transformation happens at render time.
 *
 * <p>Supported canonical shapes:
 * <ul>
 *   <li>{@code https://vimeo.com/123456789}</li>
 *   <li>{@code https://www.vimeo.com/123456789}</li>
 *   <li>{@code https://player.vimeo.com/video/123456789}</li>
 * </ul>
 */
public final class VimeoEmbedUrl {

    /** Matches the three canonical Vimeo URL shapes; captures the numeric id. */
    private static final Pattern CANONICAL = Pattern.compile(
            "^https?://(?:www\\.|player\\.)?vimeo\\.com/(?:video/)?(\\d{6,})(?:[?&].*)?$");

    private static final String EMBED_BASE = "https://player.vimeo.com/video/";

    private VimeoEmbedUrl() {
        // utility holder
    }

    /** True when {@code url} matches the canonical Vimeo pattern. */
    public static boolean matches(String url) {
        return url != null && CANONICAL.matcher(url).matches();
    }

    /**
     * Returns the embed URL for a recognised Vimeo canonical URL.
     *
     * @throws IllegalArgumentException when the URL does not match
     */
    public static String toEmbedUrl(String canonical) {
        if (canonical == null) {
            throw new IllegalArgumentException("Vimeo URL is null");
        }
        Matcher m = CANONICAL.matcher(canonical);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a Vimeo URL: " + canonical);
        }
        return EMBED_BASE + m.group(1);
    }
}
