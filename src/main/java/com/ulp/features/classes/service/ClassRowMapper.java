package com.ulp.features.classes.service;

import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.ClassGradient;
import com.ulp.features.classes.dto.ClassesDtos.ClassRow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stateless mapping helpers shared by {@link ClassesService} for projecting
 * {@link ClassEntity} into list rows and capturing before/after snapshots
 * for the {@code TYPE_UPDATED} audit metadata.
 *
 * <p>Extracted during the file-size refactor; no Spring component needed
 * because everything is a pure function over the entity.
 */
final class ClassRowMapper {

    private ClassRowMapper() {
        // utility class
    }

    /**
     * Projects a {@link ClassEntity} into a list-row DTO. The gradient is
     * derived from {@code index} so different pages can repeat colours —
     * intentional and matches the audit's "good enough" tolerance for the
     * cosmetic ordering of class thumbnails.
     */
    static ClassRow toRow(ClassEntity e, int index) {
        // TODO Sprint 3/5: wire real counts from enrollments/lessons/assignments/lesson_attachments
        int studentCount = 0;
        int lectureCount = 0;
        int assignmentCount = 0;
        int materialCount = 0;
        String createdAtIso = e.getCreatedAt() != null ? e.getCreatedAt().toString() : "";
        return new ClassRow(
                e.getId(),
                e.getName(),
                e.getCode(),
                ClassGradient.forIndex(index).css(),
                studentCount, lectureCount, assignmentCount, materialCount,
                createdAtIso
        );
    }

    /**
     * Captures the editable fields of {@code entity} as an insertion-ordered
     * map suitable for the {@code old}/{@code new} sides of an update audit.
     */
    static Map<String, Object> snapshot(ClassEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.getName());
        m.put("description", e.getDescription());
        m.put("startDate", e.getStartDate() != null ? e.getStartDate().toString() : null);
        m.put("endDate", e.getEndDate() != null ? e.getEndDate().toString() : null);
        m.put("maxStudents", e.getMaxStudents());
        return m;
    }
}
