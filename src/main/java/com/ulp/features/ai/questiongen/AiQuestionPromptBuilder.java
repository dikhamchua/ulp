package com.ulp.features.ai.questiongen;

import com.ulp.features.admin.settings.repository.AiSystemPromptRepository;
import com.ulp.entities.AiSystemPrompt;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.GenerateRequest;
import com.ulp.features.tests.entity.Question;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Assembles the system and user turns for one question-generation call.
 *
 * <p>The system prompt is admin-editable ({@code ai_system_prompts}, seeded by V41) so the
 * output contract can be tuned without a redeploy. A built-in copy is used when that row
 * is missing or has been disabled — the generator must keep working on a database whose
 * seed was never applied, and a disabled prompt should degrade the feature rather than
 * break it.
 */
@Component
public class AiQuestionPromptBuilder {

    /** Name of the catalog row this feature reads. */
    public static final String PROMPT_NAME = "AI_QUESTION_GENERATOR";

    /** Fewest questions one generation may ask for. */
    static final int MIN_COUNT = 1;

    /** Most questions one generation may ask for, bounding both cost and response length. */
    static final int MAX_COUNT = 20;

    private static final String FALLBACK_SYSTEM_PROMPT = """
            Bạn là trợ lý soạn đề thi trắc nghiệm cho giảng viên đại học.
            Dựa trên tài liệu người dùng cung cấp, hãy sinh câu hỏi trắc nghiệm bằng tiếng Việt.

            QUY TẮC BẮT BUỘC:
            1. Chỉ trả về JSON thuần. Không bọc trong markdown, không thêm bất kỳ chữ nào ngoài JSON.
            2. Cấu trúc JSON chính xác như sau:
            {"questions":[{"type":"MCQ","content":"nội dung câu hỏi","explanation":"giải thích ngắn",\
            "options":[{"content":"đáp án A","correct":true},{"content":"đáp án B","correct":false}]}]}
            3. Trường "type" chỉ nhận giá trị "MCQ" (một đáp án đúng) hoặc "MR" (nhiều đáp án đúng).
            4. Câu hỏi loại MCQ phải có đúng 1 đáp án correct=true.
            5. Câu hỏi loại MR phải có ít nhất 2 đáp án correct=true.
            6. Mỗi câu hỏi phải có từ 2 đến 6 đáp án.
            7. Nội dung câu hỏi và đáp án là văn bản thuần, không dùng thẻ HTML.
            8. Bám sát nội dung tài liệu được cung cấp, không bịa thêm kiến thức ngoài tài liệu.
            9. Sinh đúng số lượng câu hỏi mà người dùng yêu cầu.""";

    private final AiSystemPromptRepository promptRepository;

    public AiQuestionPromptBuilder(AiSystemPromptRepository promptRepository) {
        this.promptRepository = promptRepository;
    }

    /**
     * Returns the admin-configured system prompt, or the built-in default.
     *
     * @return a non-blank instruction block
     */
    public String systemPrompt() {
        return promptRepository.findByNameAndEnabledTrue(PROMPT_NAME)
                .map(AiSystemPrompt::getContent)
                .filter(content -> content != null && !content.isBlank())
                .orElse(FALLBACK_SYSTEM_PROMPT);
    }

    /**
     * Builds the user turn: the generation parameters followed by the source material.
     *
     * @param request  the lecturer's generation parameters
     * @param material the extracted document or pasted text
     * @return the message sent as the {@code user} turn
     */
    public String userMessage(GenerateRequest request, String material) {
        return "Yêu cầu:\n"
                + "- Số câu hỏi: " + clampCount(request.count()) + "\n"
                + "- Loại câu hỏi: " + normalizeType(request.type())
                + (Question.TYPE_MR.equals(normalizeType(request.type()))
                        ? " (nhiều đáp án đúng)" : " (một đáp án đúng)") + "\n"
                + "- Độ khó: " + normalizeDifficulty(request.difficulty()) + "\n\n"
                + "Tài liệu:\n" + material;
    }

    /**
     * Clamps the requested question count into the supported range.
     *
     * @param count the raw value from the form
     * @return a value between {@link #MIN_COUNT} and {@link #MAX_COUNT}
     */
    public static int clampCount(int count) {
        return Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));
    }

    /**
     * Coerces the requested question type to one the exam builder supports.
     *
     * @param type the raw value from the form
     * @return {@code MR} when explicitly asked for, otherwise {@code MCQ}
     */
    public static String normalizeType(String type) {
        return Question.TYPE_MR.equalsIgnoreCase(type == null ? "" : type.trim())
                ? Question.TYPE_MR
                : Question.TYPE_MCQ;
    }

    /**
     * Maps the difficulty select onto the Vietnamese wording sent to the model.
     *
     * @param difficulty the raw value from the form
     * @return a human-readable difficulty label; defaults to medium
     */
    public static String normalizeDifficulty(String difficulty) {
        String value = difficulty == null ? "" : difficulty.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "easy" -> "dễ";
            case "hard" -> "khó";
            default -> "trung bình";
        };
    }

    /**
     * Upper bound on the generated response, scaled to the number of questions asked for.
     *
     * <p>A Vietnamese MCQ with four options and a short explanation runs roughly 250
     * tokens; 400 leaves headroom for MR questions and longer stems without letting a
     * 20-question request run unbounded.
     *
     * @param count the clamped question count
     * @return the {@code max_tokens} value for the call
     */
    public static int maxTokensFor(int count) {
        return 400 * clampCount(count);
    }
}
