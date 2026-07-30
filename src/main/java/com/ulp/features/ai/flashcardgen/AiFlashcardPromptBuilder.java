package com.ulp.features.ai.flashcardgen;

import com.ulp.entities.AiSystemPrompt;
import com.ulp.features.admin.settings.repository.AiSystemPromptRepository;
import com.ulp.features.ai.flashcardgen.AiFlashcardGenDtos.GenerateRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Assembles the system and user turns for one flashcard-generation call.
 *
 * <p>The system prompt is admin-editable ({@code ai_system_prompts}, seeded by V44) so the
 * output contract can be tuned without a redeploy. A built-in copy is used when that row
 * is missing or has been disabled — the generator must keep working on a database whose
 * seed was never applied, and a disabled prompt should degrade the feature rather than
 * break it.
 */
@Component
public class AiFlashcardPromptBuilder {

    /** Name of the catalog row this feature reads. */
    public static final String PROMPT_NAME = "AI_FLASHCARD_GENERATOR";

    /** Fewest cards one generation may ask for. */
    static final int MIN_COUNT = 1;

    /** Most cards one generation may ask for, bounding both cost and response length. */
    static final int MAX_COUNT = 50;

    /** Sent when the caller did not name a language, so the model follows the material. */
    static final String LANGUAGE_AUTO = "theo ngôn ngữ của tài liệu";

    /** Longest accepted language hint; anything longer is prompt text, not a language. */
    private static final int MAX_LANGUAGE_CHARS = 40;

    private static final String FALLBACK_SYSTEM_PROMPT = """
            Bạn là trợ lý soạn thẻ ghi nhớ (flashcard) cho sinh viên và giảng viên đại học.
            Dựa trên tài liệu người dùng cung cấp, hãy sinh các thẻ ghi nhớ hai mặt.

            QUY TẮC BẮT BUỘC:
            1. Chỉ trả về JSON thuần. Không bọc trong markdown, không thêm bất kỳ chữ nào ngoài JSON.
            2. Cấu trúc JSON chính xác như sau:
            {"cards":[{"front":"thuật ngữ hoặc câu hỏi ngắn","back":"định nghĩa hoặc câu trả lời súc tích"}]}
            3. Mặt trước ("front") phải ngắn gọn: một thuật ngữ, khái niệm hoặc câu hỏi ngắn.
            4. Mặt sau ("back") là định nghĩa hoặc câu trả lời súc tích, không lan man.
            5. Cả hai mặt đều không được để trống.
            6. Không tạo hai thẻ có mặt trước trùng nhau.
            7. Nội dung là văn bản thuần, không dùng thẻ HTML, không dùng markdown.
            8. Bám sát nội dung tài liệu được cung cấp, không bịa thêm kiến thức ngoài tài liệu.
            9. Sinh đúng số lượng thẻ mà người dùng yêu cầu.
            10. Dùng đúng ngôn ngữ mà người dùng yêu cầu; nếu không nêu rõ thì theo ngôn ngữ của tài liệu.""";

    private final AiSystemPromptRepository promptRepository;

    public AiFlashcardPromptBuilder(AiSystemPromptRepository promptRepository) {
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
     * @param request  the caller's generation parameters
     * @param material the extracted document or pasted text
     * @return the message sent as the {@code user} turn
     */
    public String userMessage(GenerateRequest request, String material) {
        return "Yêu cầu:\n"
                + "- Số thẻ: " + clampCount(request.count()) + "\n"
                + "- Ngôn ngữ: " + normalizeLanguage(request.language()) + "\n\n"
                + "Tài liệu:\n" + material;
    }

    /**
     * Clamps the requested card count into the supported range.
     *
     * @param count the raw value from the form
     * @return a value between {@link #MIN_COUNT} and {@link #MAX_COUNT}
     */
    public static int clampCount(int count) {
        return Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));
    }

    /**
     * Turns the language field into a phrase the model can act on.
     *
     * <p>The value reaches the prompt verbatim, so it is trimmed and length-bounded: a
     * blank field means "follow the document", and an over-long value is a paste into the
     * wrong box rather than a language name.
     *
     * @param language the raw value from the form; may be {@code null}
     * @return the requested language, or {@link #LANGUAGE_AUTO} when none was given
     */
    public static String normalizeLanguage(String language) {
        String value = language == null ? "" : language.trim();
        if (value.isEmpty() || value.length() > MAX_LANGUAGE_CHARS) {
            return LANGUAGE_AUTO;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * Upper bound on the generated response, scaled to the number of cards asked for.
     *
     * <p>A flashcard is a term plus a one-line definition — far shorter than an MCQ with
     * four options — so 120 tokens per card leaves headroom for Vietnamese diacritics
     * without letting a 50-card request run unbounded.
     *
     * @param count the requested card count; clamped before scaling
     * @return the {@code max_tokens} value for the call
     */
    public static int maxTokensFor(int count) {
        return 120 * clampCount(count);
    }
}
