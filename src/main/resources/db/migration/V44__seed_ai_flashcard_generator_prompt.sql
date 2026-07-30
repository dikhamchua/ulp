-- =============================================================================
-- ULP — V44__seed_ai_flashcard_generator_prompt.sql
-- Seed the default system prompt for the AI Flashcard Generator (ULP-12.4)
--
-- The deck editor sends study material to a model and expects strict JSON back.
-- That contract lives in a system prompt so an admin can tune the wording at
-- /admin/settings/ai/prompts without a redeploy; the generator falls back to a
-- built-in copy of the same instruction when this row is missing or disabled, so
-- the feature never hard-depends on the seed surviving.
--
-- Idempotent via INSERT ... SELECT ... WHERE NOT EXISTS: re-running on a
-- database where an admin already created a prompt with this name must not
-- overwrite their edited content, and the UNIQUE key on name would otherwise
-- abort the migration.
--
-- No new table and no permission rows: ai_system_prompts and the 'system.ai'
-- gate both already exist (V40 and V4 respectively).
-- =============================================================================

SET NAMES utf8mb4;

INSERT INTO ai_system_prompts (name, description, content, is_enabled)
SELECT
    'AI_FLASHCARD_GENERATOR',
    'Sinh thẻ ghi nhớ (mặt trước/mặt sau) từ tài liệu người dùng tải lên',
    CONCAT(
        'Bạn là trợ lý soạn thẻ ghi nhớ (flashcard) cho sinh viên và giảng viên đại học.\n',
        'Dựa trên tài liệu người dùng cung cấp, hãy sinh các thẻ ghi nhớ hai mặt.\n',
        '\n',
        'QUY TẮC BẮT BUỘC:\n',
        '1. Chỉ trả về JSON thuần. Không bọc trong markdown, không thêm bất kỳ chữ nào ngoài JSON.\n',
        '2. Cấu trúc JSON chính xác như sau:\n',
        '{"cards":[{"front":"thuật ngữ hoặc câu hỏi ngắn","back":"định nghĩa hoặc câu trả lời súc tích"}]}\n',
        '3. Mặt trước ("front") phải ngắn gọn: một thuật ngữ, khái niệm hoặc câu hỏi ngắn.\n',
        '4. Mặt sau ("back") là định nghĩa hoặc câu trả lời súc tích, không lan man.\n',
        '5. Cả hai mặt đều không được để trống.\n',
        '6. Không tạo hai thẻ có mặt trước trùng nhau.\n',
        '7. Nội dung là văn bản thuần, không dùng thẻ HTML, không dùng markdown.\n',
        '8. Bám sát nội dung tài liệu được cung cấp, không bịa thêm kiến thức ngoài tài liệu.\n',
        '9. Sinh đúng số lượng thẻ mà người dùng yêu cầu.\n',
        '10. Dùng đúng ngôn ngữ mà người dùng yêu cầu; nếu không nêu rõ thì theo ngôn ngữ của tài liệu.'
    ),
    1
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM ai_system_prompts WHERE name = 'AI_FLASHCARD_GENERATOR'
);
