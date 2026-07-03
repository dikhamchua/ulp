package com.ulp.common;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Computes the numbered-button window for the shared SSR pager fragment
 * ({@code templates/fragments/pager.html}). Reusable by any server-rendered
 * list page that shows a {@code org.springframework.data.domain.Page}.
 *
 * <p>The window collapses long page ranges: it always keeps the first page,
 * the last page and {@code current ± 1}, inserting a {@code -1} marker wherever
 * a run of pages is skipped (the fragment renders {@code -1} as an ellipsis).
 */
public final class PageWindow {

    /** Max numbered buttons shown before ellipsis collapsing kicks in. */
    public static final int WINDOW_SIZE = 7;

    private PageWindow() {
        // Utility holder — no instances.
    }

    /**
     * Builds the zero-based page indices to render as pager buttons. Shows every
     * page when {@code totalPages <= WINDOW_SIZE}; otherwise shows the first page,
     * the last page and {@code currentPage ± 1}, inserting {@code -1} (ellipsis
     * marker) wherever a run of pages is skipped. Returns an empty list when
     * there is at most one page (the fragment hides itself in that case anyway).
     *
     * @param currentPage zero-based index of the active page
     * @param totalPages  total number of pages
     * @return page indices to render, with {@code -1} marking a collapsed gap
     */
    public static List<Integer> of(int currentPage, int totalPages) {
        List<Integer> window = new ArrayList<>();
        if (totalPages <= 1) return window;
        if (totalPages <= WINDOW_SIZE) {
            for (int i = 0; i < totalPages; i++) window.add(i);
            return window;
        }
        int last = totalPages - 1;
        int from = Math.max(1, currentPage - 1);
        int to = Math.min(last - 1, currentPage + 1);
        window.add(0); // always show the first page
        if (from > 1) window.add(-1); // gap between first page and the window
        for (int i = from; i <= to; i++) window.add(i);
        if (to < last - 1) window.add(-1); // gap between the window and last page
        window.add(last); // always show the last page
        return window;
    }

    /**
     * Builds the URL-encoded query string for a pager link: the preserved
     * filters in {@code base} (null/blank values skipped) followed by
     * {@code page=page}. Values are encoded via {@link URLEncoder} so a filter
     * query with spaces or '&' is safe. The fragment appends this after
     * {@code baseUrl + '?'}.
     *
     * <p>Built in Java rather than via a Thymeleaf {@code @{url(map)}} expression
     * because that syntax does NOT expand a Map into query params — it would
     * stringify the whole map into a single malformed value.
     *
     * @param base page-independent query params to preserve; null → treated empty
     * @param page zero-based page index this link points at
     * @return e.g. {@code "status=all&size=5&page=1"} (no leading '?')
     */
    public static String query(Map<String, Object> base, int page) {
        StringBuilder sb = new StringBuilder();
        if (base != null) {
            for (Map.Entry<String, Object> e : base.entrySet()) {
                Object value = e.getValue();
                if (value == null || value.toString().isBlank()) continue;
                sb.append(encode(e.getKey())).append('=')
                        .append(encode(value.toString())).append('&');
            }
        }
        sb.append("page=").append(page);
        return sb.toString();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
