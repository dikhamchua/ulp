package com.ulp.features.ai.flashcardgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.features.ai.flashcardgen.AiFlashcardGenDtos.GeneratedCardRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a model reply into validated flashcard rows.
 *
 * <p>Models routinely wrap JSON in a markdown fence or prefix it with a sentence, even
 * when instructed not to. Rather than fail those replies, the parser locates the outermost
 * JSON object in the reply and parses that — a cheap recovery that avoids re-billing a
 * generation for a formatting slip.
 *
 * <p>A malformed card is rejected outright rather than silently repaired: a card with one
 * blank side is unusable for study, and inventing the missing side would put text the model
 * never produced in front of the user.
 */
@Component
public class AiFlashcardResponseParser {

    /**
     * Longest accepted text on either side of a card.
     *
     * <p>{@code front_text}/{@code back_text} are {@code TEXT} columns, so this bound is
     * about usefulness rather than storage: a card whose face runs past a few hundred
     * characters cannot be recalled at a glance, and a model that produces one has drifted
     * from the "term / short answer" contract in the system prompt.
     */
    static final int MAX_SIDE_CHARS = 500;

    private static final String MSG_NOT_JSON =
            "AI trả về dữ liệu không hợp lệ, vui lòng thử lại";
    private static final String MSG_NO_CARDS =
            "AI không sinh được thẻ nào từ tài liệu này, vui lòng thử lại";
    private static final String MSG_EMPTY_SIDE =
            "AI trả về thẻ có mặt trước hoặc mặt sau rỗng, vui lòng thử lại";
    private static final String MSG_SIDE_TOO_LONG =
            "AI trả về thẻ có nội dung quá dài, vui lòng thử lại";

    private final ObjectMapper objectMapper;

    public AiFlashcardResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses and validates the assistant reply.
     *
     * @param reply the raw assistant message, possibly fenced or padded with prose
     * @return at least one validated card row, duplicate fronts removed
     * @throws IllegalArgumentException when no JSON object can be recovered, the payload
     *                                  carries no cards, any card has a blank or
     *                                  over-long side, or every card was a duplicate
     */
    public List<GeneratedCardRow> parse(String reply) {
        JsonNode root = readJson(extractJsonObject(reply));
        JsonNode cards = root.path("cards");
        if (!cards.isArray() || cards.isEmpty()) {
            throw new IllegalArgumentException(MSG_NO_CARDS);
        }

        List<GeneratedCardRow> rows = new ArrayList<>();
        Set<String> seenFronts = new HashSet<>();
        for (JsonNode node : cards) {
            GeneratedCardRow row = toRow(node);
            // Dedup by front, case-insensitively: two cards asking the same thing make the
            // review session repeat itself, and there is no unique key to catch it later.
            if (seenFronts.add(row.front().toLowerCase(Locale.ROOT))) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(MSG_NO_CARDS);
        }
        return List.copyOf(rows);
    }

    // ─────────────────────────────────────────────────────────────────

    /**
     * Slices out the outermost {@code { ... }} span of the reply.
     *
     * <p>Scanning from the first brace to the last, rather than balancing braces, is
     * deliberate: the payload is a single top-level object, so the last closing brace is
     * always its own. Anything that is not valid JSON after the slice is caught by
     * {@link #readJson(String)} anyway.
     */
    private static String extractJsonObject(String reply) {
        if (reply == null) {
            throw new IllegalArgumentException(MSG_NOT_JSON);
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException(MSG_NOT_JSON);
        }
        return reply.substring(start, end + 1);
    }

    /** Parses the sliced JSON, mapping any Jackson failure onto the user-facing message. */
    private JsonNode readJson(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException(MSG_NOT_JSON);
            }
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException(MSG_NOT_JSON);
        }
    }

    /** Maps one JSON card onto a row, rejecting anything that would be unusable to study. */
    private static GeneratedCardRow toRow(JsonNode node) {
        String front = requireSide(text(node, "front"));
        String back = requireSide(text(node, "back"));
        return new GeneratedCardRow(front, back);
    }

    /** Enforces the non-blank and length contract on one side of a card. */
    private static String requireSide(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(MSG_EMPTY_SIDE);
        }
        if (value.length() > MAX_SIDE_CHARS) {
            throw new IllegalArgumentException(MSG_SIDE_TOO_LONG);
        }
        return value;
    }

    /** Reads a string field, trimmed; missing or non-textual fields yield an empty string. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }
}
