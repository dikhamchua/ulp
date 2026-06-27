package com.ulp.features.lessons.controller.support;

import com.ulp.entities.SectionActivity;
import com.ulp.features.lessons.dto.SectionDtos.ActivityRow;
import com.ulp.features.lessons.repository.SectionActivityRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.ulp.common.IConstant.DEFAULT_HISTORY_PAGE_SIZE;

/**
 * Loads a page of {@link SectionActivity} rows mapped onto {@link ActivityRow}
 * DTOs with a Vietnamese type label.
 *
 * <p>Extracted from {@code SectionsController} during the file-size refactor
 * so the controller stays focused on request mapping; the index
 * {@code idx_asec_section (section_id, created_at)} keeps the lookup cheap.
 */
@Component
public class SectionActivityPageLoader {

    private final SectionActivityRepository activityRepository;

    public SectionActivityPageLoader(SectionActivityRepository activityRepository) {
        this.activityRepository = activityRepository;
    }

    /** Loads page {@code page} (0-based) of activity rows for the given section. */
    public Page<ActivityRow> load(Long sectionId, int page) {
        Pageable pageable = PageRequest.of(page, DEFAULT_HISTORY_PAGE_SIZE);
        Page<SectionActivity> raw = activityRepository
                .findBySectionIdOrderByCreatedAtDesc(sectionId, pageable);
        List<ActivityRow> rows = new ArrayList<>(raw.getNumberOfElements());
        for (SectionActivity a : raw.getContent()) {
            rows.add(new ActivityRow(a.getId(), a.getType(),
                    ActivityRowMapper.sectionLabel(a.getType()),
                    a.getDescription(), a.getCreatedAt()));
        }
        return new PageImpl<>(rows, pageable, raw.getTotalElements());
    }
}
