package com.ulp.features.classes.service;

import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.ClassGradient;
import com.ulp.features.classes.dto.ClassesDtos.ClassRow;
import com.ulp.features.classes.service.support.ClassListStatsLoader;
import com.ulp.features.classes.service.support.ClassListStatsLoader.Stats;

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
     *
     * @param displayCode code shown under the class name (prefer active invite CODE)
     * @param stats       batch-loaded counters for the list card (never null)
     */
    static ClassRow toRow(ClassEntity e, int index, String displayCode, Stats stats) {
        Stats safe = stats != null ? stats : ClassListStatsLoader.ZERO;
        String createdAtIso = e.getCreatedAt() != null ? e.getCreatedAt().toString() : "";
        String code = displayCode != null && !displayCode.isBlank() ? displayCode : e.getCode();
        return new ClassRow(
                e.getId(),
                e.getName(),
                code,
                ClassGradient.forIndex(index).css(),
                safe.studentCount(),
                safe.lectureCount(),
                safe.assignmentCount(),
                safe.materialCount(),
                createdAtIso,
                e.getStatus()
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
