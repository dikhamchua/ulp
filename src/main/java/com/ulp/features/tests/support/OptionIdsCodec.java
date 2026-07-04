package com.ulp.features.tests.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal JSON codec for the {@code selected_option_ids} column, which stores a
 * JSON array of option ids (e.g. {@code "[12,15]"}). The values are always
 * numeric ids, so a hand-rolled parser/serializer avoids pulling Jackson into
 * the grading path and keeps the stored text canonical.
 */
public final class OptionIdsCodec {

    private OptionIdsCodec() {
        // utility holder
    }

    /** Serializes ids to a canonical JSON array string, e.g. {@code [1,2,3]}. */
    public static String toJson(Set<Long> ids) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Long id : ids) {
            if (id == null) continue;
            if (!first) sb.append(',');
            sb.append(id);
            first = false;
        }
        return sb.append(']').toString();
    }

    /**
     * Parses a stored JSON array of ids back into a set. Tolerates null/blank
     * (→ empty set) and ignores any non-numeric token defensively.
     */
    public static Set<Long> parse(String json) {
        Set<Long> ids = new LinkedHashSet<>();
        if (json == null || json.isBlank()) return ids;
        String body = json.trim();
        if (body.startsWith("[")) body = body.substring(1);
        if (body.endsWith("]")) body = body.substring(0, body.length() - 1);
        for (String token : body.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try {
                ids.add(Long.parseLong(t));
            } catch (NumberFormatException ignored) {
                // Skip malformed tokens; a bad id simply doesn't match any option.
            }
        }
        return ids;
    }

    /** Normalizes a client-supplied id list into a de-duplicated, non-null set. */
    public static Set<Long> fromList(List<Long> ids) {
        Set<Long> out = new LinkedHashSet<>();
        if (ids == null) return out;
        for (Long id : ids) {
            if (id != null) out.add(id);
        }
        return out;
    }

    /** Sorted copy for stable, canonical JSON storage. */
    public static Set<Long> sorted(Set<Long> ids) {
        List<Long> list = new ArrayList<>(ids);
        list.sort(Long::compareTo);
        return new LinkedHashSet<>(list);
    }
}
