-- Exam rich-text (Quill + embedded images) can exceed MySQL TEXT (64KB),
-- especially when a paste briefly carries a data:image base64 payload.
-- Promote both content columns to MEDIUMTEXT (16MB).

ALTER TABLE questions
    MODIFY COLUMN content MEDIUMTEXT NOT NULL;

ALTER TABLE question_options
    MODIFY COLUMN content MEDIUMTEXT NOT NULL;
