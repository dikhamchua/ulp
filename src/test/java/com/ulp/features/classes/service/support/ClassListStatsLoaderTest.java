package com.ulp.features.classes.service.support;

import com.ulp.features.assignments.repository.AssignmentRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.classes.service.support.ClassListStatsLoader.Stats;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClassListStatsLoader} batch aggregation.
 */
class ClassListStatsLoaderTest {

    private EnrollmentRepository enrollmentRepository;
    private LessonRepository lessonRepository;
    private AssignmentRepository assignmentRepository;
    private LessonAttachmentRepository lessonAttachmentRepository;
    private ClassListStatsLoader loader;

    @BeforeEach
    void setUp() {
        enrollmentRepository = mock(EnrollmentRepository.class);
        lessonRepository = mock(LessonRepository.class);
        assignmentRepository = mock(AssignmentRepository.class);
        lessonAttachmentRepository = mock(LessonAttachmentRepository.class);
        loader = new ClassListStatsLoader(
                enrollmentRepository, lessonRepository,
                assignmentRepository, lessonAttachmentRepository);
    }

    @Test
    void load_empty_short_circuits_without_queries() {
        Map<Long, Stats> result = loader.load(List.of());

        assertThat(result).isEmpty();
        verify(enrollmentRepository, never()).countActiveGroupedByClassIds(any());
        verify(lessonRepository, never()).countAllGroupedByClassIds(any());
        verify(assignmentRepository, never()).countNotDeletedGroupedByClassIds(any());
        verify(lessonAttachmentRepository, never()).countGroupedByClassIds(any());
    }

    @Test
    void load_merges_four_count_maps_and_defaults_missing_to_zero() {
        when(enrollmentRepository.countActiveGroupedByClassIds(List.of(1L, 2L)))
                .thenReturn(List.of(enrollmentCount(1L, 10L)));
        when(lessonRepository.countAllGroupedByClassIds(List.of(1L, 2L)))
                .thenReturn(List.of(lessonCount(1L, 4L), lessonCount(2L, 1L)));
        when(assignmentRepository.countNotDeletedGroupedByClassIds(List.of(1L, 2L)))
                .thenReturn(List.of(assignmentCount(2L, 3L)));
        when(lessonAttachmentRepository.countGroupedByClassIds(List.of(1L, 2L)))
                .thenReturn(List.of(attachmentCount(1L, 8L)));

        Map<Long, Stats> result = loader.load(List.of(1L, 2L));

        assertThat(result.get(1L)).isEqualTo(new Stats(10, 4, 0, 8));
        assertThat(result.get(2L)).isEqualTo(new Stats(0, 1, 3, 0));
    }

    private static EnrollmentRepository.ClassCount enrollmentCount(Long classId, Long cnt) {
        return new EnrollmentRepository.ClassCount() {
            @Override public Long getClassId() { return classId; }
            @Override public Long getCnt() { return cnt; }
        };
    }

    private static LessonRepository.ClassCount lessonCount(Long classId, Long cnt) {
        return new LessonRepository.ClassCount() {
            @Override public Long getClassId() { return classId; }
            @Override public Long getCnt() { return cnt; }
        };
    }

    private static AssignmentRepository.ClassCount assignmentCount(Long classId, Long cnt) {
        return new AssignmentRepository.ClassCount() {
            @Override public Long getClassId() { return classId; }
            @Override public Long getCnt() { return cnt; }
        };
    }

    private static LessonAttachmentRepository.ClassCount attachmentCount(Long classId, Long cnt) {
        return new LessonAttachmentRepository.ClassCount() {
            @Override public Long getClassId() { return classId; }
            @Override public Long getCnt() { return cnt; }
        };
    }
}
