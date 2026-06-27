package com.ulp.features.lessons.service;

import com.ulp.entities.Lesson;
import com.ulp.entities.LessonActivity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.ulp.common.IConstant.LESSON_STATUS_DRAFT;
import static com.ulp.common.IConstant.LESSON_STATUS_PUBLISHED;

/**
 * Helpers that build audit metadata + apply post-save status transitions
 * for {@link LessonsService#update}.
 *
 * <p>Extracted during the file-size refactor so {@code LessonsService}
 * stays focused on the orchestration / authorization layer. No state of
 * its own; pure functions plus a writer collaborator.
 */
@Component
public class LessonsUpdateHelper {

    private final LessonActivityWriter activityWriter;
    private final com.ulp.features.lessons.repository.LessonRepository lessonRepository;

    public LessonsUpdateHelper(LessonActivityWriter activityWriter,
                               com.ulp.features.lessons.repository.LessonRepository lessonRepository) {
        this.activityWriter = activityWriter;
        this.lessonRepository = lessonRepository;
    }

    /** Builds the diff metadata payload and writes the UPDATED audit row. */
    public void writeUpdateActivity(Lesson saved, String oldTitle, String oldBody,
                                    String newBody, boolean titleChanged,
                                    boolean bodyChanged, Long userId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (titleChanged) {
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("old", oldTitle);
            diff.put("new", saved.getTitle());
            metadata.put("title", diff);
        }
        if (bodyChanged) {
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("old", oldBody);
            diff.put("new", newBody);
            metadata.put("body", diff);
        }
        activityWriter.write(
                saved.getId(),
                LessonActivity.TYPE_UPDATED,
                "Cập nhật bài giảng " + saved.getTitle(),
                metadata,
                userId);
    }

    /**
     * Applies a status transition triggered by an update form. Skips the
     * audit write when the incoming status is null/blank or unchanged so
     * "Đã cập nhật" stands alone when only title/body moved.
     */
    public void applyStatusTransition(Lesson lesson, String requestedStatus, Long userId) {
        if (requestedStatus == null || requestedStatus.isBlank()) return;
        if (requestedStatus.equals(lesson.getStatus())) return;
        if (LESSON_STATUS_PUBLISHED.equals(requestedStatus)) {
            lesson.publish();
            lessonRepository.save(lesson);
            activityWriter.write(
                    lesson.getId(),
                    LessonActivity.TYPE_PUBLISHED,
                    "Xuất bản bài giảng " + lesson.getTitle(),
                    userId);
        } else if (LESSON_STATUS_DRAFT.equals(requestedStatus)) {
            lesson.unpublish();
            lessonRepository.save(lesson);
            activityWriter.write(
                    lesson.getId(),
                    LessonActivity.TYPE_UNPUBLISHED,
                    "Chuyển bài giảng " + lesson.getTitle() + " về nháp",
                    userId);
        }
    }
}
