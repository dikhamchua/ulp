package com.ulp.features.lessons.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helper that recognises a canonical YouTube URL and converts it to
 * the iframe-embed form used by the student lesson-detail page.
 *
 * <p>Why a narrow regex: server-side oEmbed would leak the URL to YouTube
 * on every save and would not catch a typo that nonetheless resolves to a
 * valid URL — see design D4. The canonical URL is stored verbatim; the
 * embed transformation runs at render time so the embed strategy can
 * evolve without a data migration.
 *
 * <p>Supported canonical shapes:
 * <ul>
 *   <li>{@code https://www.youtube.com/watch?v=ID}</li>
 *   <li>{@code https://m.youtube.com/watch?v=ID}</li>
 *   <li>{@code https://youtu.be/ID}</li>
 *   <li>{@code https://www.youtube.com/embed/ID} (passed through as-is on output)</li>
 * </ul>
 */
public final class YouTubeEmbedUrl {

    /** Matches the four canonical YouTube URL shapes; captures the video id. */
    private static final Pattern CANONICAL = Pattern.compile(
            "^https?://(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)"
                    + "([\\w-]{6,})(?:[?&].*)?$");

    private static final String EMBED_BASE = "https://www.youtube.com/embed/";

    private YouTubeEmbedUrl() {
        // utility holder
    }

    /** True when {@code url} matches the canonical YouTube pattern. */
    public static boolean matches(String url) {
        return url != null && CANONICAL.matcher(url).matches();
    }

    /**
     * Returns the embed URL for a recognised YouTube canonical URL.
     *
     * @throws IllegalArgumentException when the URL does not match
     */
    public static String toEmbedUrl(String canonical) {
        if (canonical == null) {
            throw new IllegalArgumentException("YouTube URL is null");
        }
        Matcher m = CANONICAL.matcher(canonical);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a YouTube URL: " + canonical);
        }
        return EMBED_BASE + m.group(1);
    }
}
