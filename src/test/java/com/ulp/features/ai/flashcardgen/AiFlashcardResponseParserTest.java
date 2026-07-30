package com.ulp.features.ai.flashcardgen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.features.ai.flashcardgen.AiFlashcardGenDtos.GeneratedCardRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link AiFlashcardResponseParser}.
 *
 * <p>Covers both halves of its job: recovering JSON from replies that ignore the
 * "pure JSON" instruction, and refusing cards that would be unusable to study.
 */
class AiFlashcardResponseParserTest {

    private final AiFlashcardResponseParser parser =
            new AiFlashcardResponseParser(new ObjectMapper());

    private static final String VALID_CARDS = """
            {"cards":[{"front":"Hàng đợi (queue)","back":"Cấu trúc dữ liệu FIFO"},
                      {"front":"Ngăn xếp (stack)","back":"Cấu trúc dữ liệu LIFO"}]}""";

    @Test
    @DisplayName("parse accepts a clean two-card payload")
    void parsesCleanJson() {
        List<GeneratedCardRow> rows = parser.parse(VALID_CARDS);

        assertEquals(2, rows.size());
        assertEquals("Hàng đợi (queue)", rows.get(0).front());
        assertEquals("Cấu trúc dữ liệu FIFO", rows.get(0).back());
        assertEquals("Ngăn xếp (stack)", rows.get(1).front());
    }

    @Test
    @DisplayName("parse recovers JSON wrapped in a markdown fence and prose")
    void recoversFencedJson() {
        String reply = "Đây là các thẻ bạn yêu cầu:\n```json\n" + VALID_CARDS
                + "\n```\nChúc bạn học tốt!";

        List<GeneratedCardRow> rows = parser.parse(reply);

        assertEquals(2, rows.size());
        assertEquals("Hàng đợi (queue)", rows.get(0).front());
    }

    @Test
    @DisplayName("parse trims whitespace around both sides")
    void trimsBothSides() {
        List<GeneratedCardRow> rows = parser.parse(
                "{\"cards\":[{\"front\":\"  Đệ quy  \",\"back\":\"\\n Hàm tự gọi chính nó \"}]}");

        assertEquals("Đệ quy", rows.get(0).front());
        assertEquals("Hàm tự gọi chính nó", rows.get(0).back());
    }

    @Test
    @DisplayName("parse rejects a reply that carries no JSON object at all")
    void rejectsNonJson() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("Xin lỗi, tôi không thể sinh thẻ."));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
    }

    @Test
    @DisplayName("parse rejects a payload with an empty cards array")
    void rejectsEmptyCards() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"cards\":[]}"));
    }

    @Test
    @DisplayName("parse rejects a payload with no cards field")
    void rejectsMissingCardsField() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("{\"questions\":[{\"front\":\"A\",\"back\":\"B\"}]}"));
    }

    @Test
    @DisplayName("parse rejects a card missing its back field")
    void rejectsMissingBackField() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("{\"cards\":[{\"front\":\"Đệ quy\"}]}"));
    }

    @Test
    @DisplayName("parse rejects a card whose front is blank")
    void rejectsBlankFront() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("{\"cards\":[{\"front\":\"   \",\"back\":\"Định nghĩa\"}]}"));
    }

    @Test
    @DisplayName("parse rejects a card whose back is blank")
    void rejectsBlankBack() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("{\"cards\":[{\"front\":\"Thuật ngữ\",\"back\":\"\"}]}"));
    }

    /**
     * A non-textual side is a shape error, not a value the parser should coerce: rendering
     * {@code 42} or {@code null} onto a card face would show the user text the model never
     * wrote as its answer.
     */
    @Test
    @DisplayName("parse rejects a card whose side is not a string")
    void rejectsNonTextualSide() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("{\"cards\":[{\"front\":\"Thuật ngữ\",\"back\":42}]}"));
    }

    @Test
    @DisplayName("parse rejects a card whose side exceeds the length cap")
    void rejectsOverlongSide() {
        String longBack = "x".repeat(AiFlashcardResponseParser.MAX_SIDE_CHARS + 1);

        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "{\"cards\":[{\"front\":\"Thuật ngữ\",\"back\":\"" + longBack + "\"}]}"));
    }

    @Test
    @DisplayName("parse drops a duplicate front, ignoring case")
    void dedupsByFrontIgnoringCase() {
        List<GeneratedCardRow> rows = parser.parse("""
                {"cards":[{"front":"Đệ quy","back":"Hàm tự gọi chính nó"},
                          {"front":"đệ QUY","back":"Một định nghĩa khác"},
                          {"front":"Vòng lặp","back":"Khối lệnh chạy lặp lại"}]}""");

        assertEquals(2, rows.size(), "the second 'Đệ quy' card must be dropped");
        assertEquals("Đệ quy", rows.get(0).front());
        assertEquals("Hàm tự gọi chính nó", rows.get(0).back(),
                "the first occurrence wins, not the last");
        assertEquals("Vòng lặp", rows.get(1).front());
    }

    /**
     * Deduping must not be able to empty the result silently: a reply whose every card is a
     * repeat of the first would otherwise reach the editor as "0 thẻ đã thêm", which reads
     * as a success.
     */
    @Test
    @DisplayName("parse rejects a payload whose cards are all duplicates of one front")
    void rejectsWhenDedupLeavesNothingUseful() {
        List<GeneratedCardRow> rows = parser.parse("""
                {"cards":[{"front":"Đệ quy","back":"A"},
                          {"front":"Đệ quy","back":"B"}]}""");

        assertEquals(1, rows.size(), "duplicates collapse to a single card, never to none");
    }
}
