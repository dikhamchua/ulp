package com.ulp.features.lessons.controller.support;

import com.ulp.entities.LessonActivity;
import com.ulp.entities.SectionActivity;

/**
 * Maps section/lesson activity type constants to their Vietnamese display
 * labels for the history tab. Two separate methods because the two entities
 * carry different type vocabularies — sections do RENAMED, lessons do UPDATED
 * plus PUBLISHED/UNPUBLISHED — and a forced union would either lose context
 * (a single "modify" label) or invent a label-per-entity-pair lookup that
 * doesn't simplify anything. Unknown types pass through as their raw key so
 * future activity types render until labelled.
 */
public final class ActivityRowMapper {

    private ActivityRowMapper() {
        // utility holder
    }

    /** Maps a section-activity type to its Vietnamese label; unknown types pass through. */
    public static String sectionLabel(String type) {
        return switch (type) {
            case SectionActivity.TYPE_CREATED -> "Tạo mới";
            case SectionActivity.TYPE_RENAMED -> "Đổi tên";
            case SectionActivity.TYPE_REORDERED -> "Sắp xếp lại";
            case SectionActivity.TYPE_DELETED -> "Xoá";
            // Fallback so future activity types render their raw key until labelled.
            default -> type;
        };
    }

    /** Maps a lesson-activity type to its Vietnamese label; unknown types pass through. */
    public static String lessonLabel(String type) {
        return switch (type) {
            case LessonActivity.TYPE_CREATED -> "Tạo mới";
            case LessonActivity.TYPE_UPDATED -> "Cập nhật";
            case LessonActivity.TYPE_PUBLISHED -> "Xuất bản";
            case LessonActivity.TYPE_UNPUBLISHED -> "Chuyển nháp";
            case LessonActivity.TYPE_REORDERED -> "Sắp xếp lại";
            case LessonActivity.TYPE_DELETED -> "Xoá";
            // Fallback so future activity types render their raw key until labelled.
            default -> type;
        };
    }
}
