-- =============================================================================
-- ULP — V41__seed_ai_question_generator_prompt.sql
-- Seed the default system prompt for the AI Question Generator (ULP-12.2)
--
-- The lecturer exam editor sends lecture material to a model and expects strict
-- JSON back. That contract lives in a system prompt so an admin can tune the
-- wording at /admin/settings/ai/prompts without a redeploy; the generator falls
-- back to a built-in copy of the same instruction when this row is missing or
-- disabled, so the feature never hard-depends on the seed surviving.
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
    'AI_QUESTION_GENERATOR',
    'Sinh câu hỏi trắc nghiệm (MCQ/MR) từ tài liệu giảng viên tải lên',
    CONCAT(
        'Bạn là trợ lý soạn đề thi trắc nghiệm cho giảng viên đại học.\n',
        'Dựa trên tài liệu người dùng cung cấp, hãy sinh câu hỏi trắc nghiệm bằng tiếng Việt.\n',
        '\n',
        'QUY TẮC BẮT BUỘC:\n',
        '1. Chỉ trả về JSON thuần. Không bọc trong markdown, không thêm bất kỳ chữ nào ngoài JSON.\n',
        '2. Cấu trúc JSON chính xác như sau:\n',
        '{"questions":[{"type":"MCQ","content":"nội dung câu hỏi","explanation":"giải thích ngắn",',
        '"options":[{"content":"đáp án A","correct":true},{"content":"đáp án B","correct":false}]}]}\n',
        '3. Trường "type" chỉ nhận giá trị "MCQ" (một đáp án đúng) hoặc "MR" (nhiều đáp án đúng).\n',
        '4. Câu hỏi loại MCQ phải có đúng 1 đáp án correct=true.\n',
        '5. Câu hỏi loại MR phải có ít nhất 2 đáp án correct=true.\n',
        '6. Mỗi câu hỏi phải có từ 2 đến 6 đáp án.\n',
        '7. Nội dung câu hỏi và đáp án là văn bản thuần, không dùng thẻ HTML.\n',
        '8. Bám sát nội dung tài liệu được cung cấp, không bịa thêm kiến thức ngoài tài liệu.\n',
        '9. Sinh đúng số lượng câu hỏi mà người dùng yêu cầu.'
    ),
    1
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM ai_system_prompts WHERE name = 'AI_QUESTION_GENERATOR'
);
