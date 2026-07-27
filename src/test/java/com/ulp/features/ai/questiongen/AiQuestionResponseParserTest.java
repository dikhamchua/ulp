package com.ulp.features.ai.questiongen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.DraftQuestion;
import com.ulp.features.tests.entity.Question;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AiQuestionResponseParser}.
 *
 * <p>Covers both halves of its job: recovering JSON from replies that ignore the
 * "pure JSON" instruction, and refusing drafts whose answer keys would grade students
 * incorrectly.
 */
class AiQuestionResponseParserTest {

    private final AiQuestionResponseParser parser =
            new AiQuestionResponseParser(new ObjectMapper());

    private static final String VALID_MCQ = """
            {"questions":[{"type":"MCQ","content":"Thủ đô của Việt Nam là gì?",
            "explanation":"Hà Nội là thủ đô.",
            "options":[{"content":"Hà Nội","correct":true},
                       {"content":"Đà Nẵng","correct":false},
                       {"content":"Huế","correct":false}]}]}""";

    @Test
    @DisplayName("parse accepts a clean MCQ payload")
    void parsesCleanJson() {
        List<DraftQuestion> drafts = parser.parse(VALID_MCQ);

        assertEquals(1, drafts.size());
        DraftQuestion draft = drafts.get(0);
        assertEquals(Question.TYPE_MCQ, draft.type());
        assertEquals("Thủ đô của Việt Nam là gì?", draft.content());
        assertEquals(3, draft.options().size());
        assertTrue(draft.options().get(0).correct());
    }

    @Test
    @DisplayName("parse recovers JSON wrapped in a markdown fence and prose")
    void recoversFencedJson() {
        String reply = "Đây là các câu hỏi bạn yêu cầu:\n```json\n" + VALID_MCQ
                + "\n```\nChúc bạn dạy tốt!";

        List<DraftQuestion> drafts = parser.parse(reply);

        assertEquals(1, drafts.size());
        assertEquals("Thủ đô của Việt Nam là gì?", drafts.get(0).content());
    }

    @Test
    @DisplayName("parse accepts an MR question with several correct answers")
    void parsesMultiResponse() {
        String reply = """
                {"questions":[{"type":"MR","content":"Ngôn ngữ nào chạy trên JVM?",
                "options":[{"content":"Java","correct":true},
                           {"content":"Kotlin","correct":true},
                           {"content":"Python","correct":false}]}]}""";

        List<DraftQuestion> drafts = parser.parse(reply);

        assertEquals(Question.TYPE_MR, drafts.get(0).type());
        assertNull(drafts.get(0).explanation(), "a missing explanation should map to null");
    }

    @Test
    @DisplayName("parse rejects a reply that carries no JSON object at all")
    void rejectsNonJson() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("Xin lỗi, tôi không thể sinh câu hỏi."));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
    }

    @Test
    @DisplayName("parse rejects a payload with an empty questions array")
    void rejectsEmptyQuestions() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("{\"questions\":[]}"));
    }

    @Test
    @DisplayName("parse rejects a question with no options field")
    void rejectsMissingOptions() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "{\"questions\":[{\"type\":\"MCQ\",\"content\":\"Câu hỏi\"}]}"));
    }

    @Test
    @DisplayName("parse rejects a question with only one option")
    void rejectsSingleOption() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"questions":[{"type":"MCQ","content":"Câu hỏi",
                "options":[{"content":"Chỉ một","correct":true}]}]}"""));
    }

    @Test
    @DisplayName("parse rejects an MCQ carrying two correct answers")
    void rejectsMcqWithTwoCorrect() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"questions":[{"type":"MCQ","content":"Câu hỏi",
                "options":[{"content":"A","correct":true},
                           {"content":"B","correct":true}]}]}"""));
    }

    @Test
    @DisplayName("parse rejects an MCQ carrying no correct answer")
    void rejectsMcqWithNoCorrect() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"questions":[{"type":"MCQ","content":"Câu hỏi",
                "options":[{"content":"A","correct":false},
                           {"content":"B","correct":false}]}]}"""));
    }

    @Test
    @DisplayName("parse rejects an MR carrying no correct answer")
    void rejectsMrWithNoCorrect() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"questions":[{"type":"MR","content":"Câu hỏi",
                "options":[{"content":"A","correct":false},
                           {"content":"B","correct":false}]}]}"""));
    }

    /**
     * An MR question with a single correct answer is an MCQ under the wrong label, and the
     * system prompt already demands at least two — the parser must hold the same line, or
     * the mislabelled question reaches the exam.
     */
    @Test
    @DisplayName("parse rejects an MR carrying only one correct answer")
    void rejectsMrWithOneCorrect() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"questions":[{"type":"MR","content":"Câu hỏi",
                "options":[{"content":"A","correct":true},
                           {"content":"B","correct":false},
                           {"content":"C","correct":false}]}]}"""));
    }

    @Test
    @DisplayName("parse rejects a question whose body is blank")
    void rejectsBlankContent() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"questions":[{"type":"MCQ","content":"   ",
                "options":[{"content":"A","correct":true},
                           {"content":"B","correct":false}]}]}"""));
    }

    @Test
    @DisplayName("parse rejects a question whose option body is blank")
    void rejectsBlankOptionContent() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"questions":[{"type":"MCQ","content":"Câu hỏi",
                "options":[{"content":"","correct":true},
                           {"content":"B","correct":false}]}]}"""));
    }
}
