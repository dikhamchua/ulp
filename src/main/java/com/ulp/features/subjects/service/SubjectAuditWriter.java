package com.ulp.features.subjects.service;

import com.ulp.features.subjects.entity.SubjectActivity;
import com.ulp.features.subjects.repository.SubjectActivityRepository;
import org.springframework.stereotype.Component;

/** Single insertion point for {@code subject_activities} rows. */
@Component
public class SubjectAuditWriter {

    private final SubjectActivityRepository activityRepository;

    public SubjectAuditWriter(SubjectActivityRepository activityRepository) {
        this.activityRepository = activityRepository;
    }

    public void write(Long subjectId, String type, String message, Long actorId) {
        activityRepository.save(
                new SubjectActivity(subjectId, type, message, null, actorId));
    }
}
