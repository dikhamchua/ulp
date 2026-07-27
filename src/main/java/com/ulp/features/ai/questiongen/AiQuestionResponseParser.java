package com.ulp.features.ai.questiongen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.DraftOption;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.DraftQuestion;
import com.ulp.features.tests.entity.Question;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a model reply into validated question drafts.
 *
 * <p>Models routinely wrap JSON in a markdown fence or prefix it with a sentence, even
 * when instructed not to. Rather than fail those replies, the parser locates the outermost
 * JSON object in the reply and parses that — a cheap recovery that avoids re-billing a
 * generation for a formatting slip.
 *
 * <p>Every question is validated before it can reach the preview. A malformed draft is
 * rejected outright instead of being silently repaired, because a question with the wrong
 * number of correct answers would grade students incorrectly once inserted.
 */
@Component
public class AiQuestionResponseParser {

    /** Fewest options a question may carry. Below two there is nothing to choose between. */
    static final int MIN_OPTIONS = 2;

    /** Most options a question may carry, matching what the exam builder renders comfortably. */
    static final int MAX_OPTIONS = 6;

    /**
     * Fewest correct answers a multi-response question may carry.
     *
     * <p>Matches the system prompt: an MR question with a single correct answer is an MCQ
     * wearing the wrong label, and would reach the exam as one.
     */
    static final int MIN_MR_CORRECT = 2;

    private static final String MSG_NOT_JSON =
            "AI trả về dữ liệu không hợp lệ, vui lòng thử lại";
    private static final String MSG_NO_QUESTIONS =
            "AI không sinh được câu hỏi nào từ tài liệu này, vui lòng thử lại";
    private static final String MSG_BAD_OPTION_COUNT =
            "AI trả về câu hỏi có số đáp án không hợp lệ, vui lòng thử lại";
    private static final String MSG_BAD_MCQ =
            "AI trả về câu hỏi một đáp án nhưng không có đúng một đáp án đúng, vui lòng thử lại";
    private static final String MSG_BAD_MR =
            "AI trả về câu hỏi nhiều đáp án nhưng không có ít nhất 2 đáp án đúng, vui lòng thử lại";
    private static final String MSG_EMPTY_CONTENT =
            "AI trả về câu hỏi hoặc đáp án rỗng, vui lòng thử lại";

    private final ObjectMapper objectMapper;

    public AiQuestionResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses and validates the assistant reply.
     *
     * @param reply the raw assistant message, possibly fenced or padded with prose
     * @return at least one validated draft question
     * @throws IllegalArgumentException when no JSON object can be recovered, the payload
     *                                  carries no questions, or any question is invalid
     */
    public List<DraftQuestion> parse(String reply) {
        JsonNode root = readJson(extractJsonObject(reply));
        JsonNode questions = root.path("questions");
        if (!questions.isArray() || questions.isEmpty()) {
            throw new IllegalArgumentException(MSG_NO_QUESTIONS);
        }

        List<DraftQuestion> drafts = new ArrayList<>();
        for (JsonNode node : questions) {
            drafts.add(toDraft(node));
        }
        return List.copyOf(drafts);
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

    /** Maps one JSON question onto a draft, rejecting anything that would grade wrongly. */
    private static DraftQuestion toDraft(JsonNode node) {
        String type = Question.TYPE_MR.equalsIgnoreCase(node.path("type").asText(""))
                ? Question.TYPE_MR
                : Question.TYPE_MCQ;
        String content = text(node, "content");
        if (content.isEmpty()) {
            throw new IllegalArgumentException(MSG_EMPTY_CONTENT);
        }

        JsonNode options = node.path("options");
        if (!options.isArray() || options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException(MSG_BAD_OPTION_COUNT);
        }

        List<DraftOption> drafts = new ArrayList<>();
        int correctCount = 0;
        for (JsonNode option : options) {
            String optionContent = text(option, "content");
            if (optionContent.isEmpty()) {
                throw new IllegalArgumentException(MSG_EMPTY_CONTENT);
            }
            boolean correct = option.path("correct").asBoolean(false);
            if (correct) {
                correctCount++;
            }
            drafts.add(new DraftOption(optionContent, correct));
        }

        if (Question.TYPE_MR.equals(type)) {
            if (correctCount < MIN_MR_CORRECT) {
                throw new IllegalArgumentException(MSG_BAD_MR);
            }
        } else if (correctCount != 1) {
            throw new IllegalArgumentException(MSG_BAD_MCQ);
        }

        String explanation = text(node, "explanation");
        return new DraftQuestion(type, content,
                explanation.isEmpty() ? null : explanation, List.copyOf(drafts));
    }

    /** Reads a string field, trimmed; missing or non-textual fields yield an empty string. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }
}
