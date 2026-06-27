package com.ulp.features.lessons.controller.support;

import com.ulp.entities.LessonActivity;
import com.ulp.features.lessons.dto.LessonDtos.LessonActivityRow;
import com.ulp.features.lessons.repository.LessonActivityRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.ulp.common.IConstant.DEFAULT_HISTORY_PAGE_SIZE;

/**
 * Loads a page of {@link LessonActivity} rows mapped onto {@link LessonActivityRow}
 * DTOs with a Vietnamese type label.
 *
 * <p>Extracted from {@code LessonsController} during the file-size refactor
 * so the controller stays focused on request mapping.
 */
@Component
public class LessonActivityPageLoader {

    private final LessonActivityRepository activityRepository;

    public LessonActivityPageLoader(LessonActivityRepository activityRepository) {
        this.activityRepository = activityRepository;
    }

    /** Loads page {@code page} (0-based) of activity rows for the given lesson. */
    public Page<LessonActivityRow> load(Long lessonId, int page) {
        Pageable pageable = PageRequest.of(page, DEFAULT_HISTORY_PAGE_SIZE);
        Page<LessonActivity> raw = activityRepository
                .findByLessonIdOrderByCreatedAtDesc(lessonId, pageable);
        List<LessonActivityRow> rows = new ArrayList<>(raw.getNumberOfElements());
        for (LessonActivity a : raw.getContent()) {
            rows.add(new LessonActivityRow(a.getId(), a.getType(),
                    ActivityRowMapper.lessonLabel(a.getType()),
                    a.getDescription(), a.getCreatedAt()));
        }
        return new PageImpl<>(rows, pageable, raw.getTotalElements());
    }
}
