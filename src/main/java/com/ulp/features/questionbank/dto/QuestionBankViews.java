package com.ulp.features.questionbank.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Read-only DTOs for the subject → chapter organised question-bank screens. */
public final class QuestionBankViews {

    private QuestionBankViews() {
    }

    /** One subject shown in the bank subject selector (with in-scope item count). */
    public record SubjectOption(Long id, String code, String title, String label,
                                long itemCount, boolean active) {
    }

    /** One chapter of a subject shown on the HEAD screen or in the authoring form. */
    public record ChapterOption(Long id, String title, long itemCount) {
    }

    /** One question-bank item row on the list / HEAD manage screens. */
    public record ItemRow(Long id, String contentPreview, String questionType,
                          String status, Long subjectId, String subjectLabel,
                          Long chapterId, String chapterLabel,
                          LocalDateTime updatedAt,
                          boolean editable, boolean archivable, boolean unarchivable) {
    }

    public record OptionView(String content, boolean correct) {
    }

    /** Full item payload for the detail screen (content, options, labels, flags). */
    public record ItemDetail(Long id, String questionType, String status,
                             String content, String explanation,
                             Long subjectId, String subjectLabel,
                             Long chapterId, String chapterLabel,
                             String contributorName, LocalDateTime updatedAt,
                             List<OptionView> options,
                             boolean editable, boolean archivable, boolean unarchivable) {
    }
}
