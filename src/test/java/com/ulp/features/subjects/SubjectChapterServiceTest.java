package com.ulp.features.subjects;

import com.ulp.features.subjects.dto.SubjectDtos.ChapterRow;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.entity.SubjectChapter;
import com.ulp.features.subjects.repository.SubjectChapterRepository;
import com.ulp.features.subjects.service.SubjectChapterService;
import com.ulp.features.subjects.service.SubjectChapterService.MoveDirection;
import com.ulp.features.subjects.service.SubjectService;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for subject sample-chapter outline rules. */
class SubjectChapterServiceTest {

    private static final Long SUBJECT_ID = 50L;
    private static final Long HEAD_ID = 7L;
    private static final Long ADMIN_ID = 1L;
    private static final Long DEPT_A = 10L;

    private SubjectChapterRepository chapterRepository;
    private SubjectService subjectService;
    private SubjectChapterService service;
    private Subject subject;

    @BeforeEach
    void setUp() {
        chapterRepository = mock(SubjectChapterRepository.class);
        subjectService = mock(SubjectService.class);
        service = new SubjectChapterService(chapterRepository, subjectService);

        subject = new Subject(DEPT_A, "PRJ301", "Java", null, true, ADMIN_ID);
        ReflectionTestUtils.setField(subject, "id", SUBJECT_ID);
        when(subjectService.requireScopedSubject(SUBJECT_ID, HEAD_ID, Role.HEAD))
                .thenReturn(subject);
        when(subjectService.requireScopedSubject(SUBJECT_ID, ADMIN_ID, Role.ADMIN))
                .thenReturn(subject);
    }

    @Test
    void create_appends_after_max_order() {
        when(chapterRepository.findMaxDisplayOrder(SUBJECT_ID)).thenReturn((short) 1);
        AtomicLong ids = new AtomicLong(10);
        when(chapterRepository.save(any(SubjectChapter.class))).thenAnswer(inv -> {
            SubjectChapter c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", ids.getAndIncrement());
            return c;
        });

        ChapterRow row = service.createChapter(SUBJECT_ID, "  Chương 3  ", HEAD_ID, Role.HEAD);

        assertThat(row.title()).isEqualTo("Chương 3");
        assertThat(row.displayOrder()).isEqualTo((short) 2);
        verify(chapterRepository).save(org.mockito.ArgumentMatchers.argThat(c ->
                SUBJECT_ID.equals(c.getSubjectId())
                        && "Chương 3".equals(c.getTitle())
                        && Short.valueOf((short) 2).equals(c.getDisplayOrder())));
    }

    @Test
    void create_first_chapter_starts_at_zero() {
        when(chapterRepository.findMaxDisplayOrder(SUBJECT_ID)).thenReturn((short) -1);
        when(chapterRepository.save(any(SubjectChapter.class))).thenAnswer(inv -> {
            SubjectChapter c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", 1L);
            return c;
        });

        ChapterRow row = service.createChapter(SUBJECT_ID, "Chương 1", ADMIN_ID, Role.ADMIN);

        assertThat(row.displayOrder()).isEqualTo((short) 0);
    }

    @Test
    void move_up_swaps_order_with_previous() {
        SubjectChapter first = chapter(1L, "A", (short) 0);
        SubjectChapter second = chapter(2L, "B", (short) 1);
        List<SubjectChapter> ordered = new ArrayList<>(List.of(first, second));
        when(chapterRepository.findBySubjectIdOrderByDisplayOrderAsc(SUBJECT_ID))
                .thenReturn(ordered);
        when(chapterRepository.saveAndFlush(any(SubjectChapter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chapterRepository.save(any(SubjectChapter.class))).thenAnswer(inv -> inv.getArgument(0));

        service.moveChapter(SUBJECT_ID, 2L, MoveDirection.UP, HEAD_ID, Role.HEAD);

        assertThat(first.getDisplayOrder()).isEqualTo((short) 1);
        assertThat(second.getDisplayOrder()).isEqualTo((short) 0);
    }

    @Test
    void move_up_on_first_is_noop() {
        SubjectChapter first = chapter(1L, "A", (short) 0);
        when(chapterRepository.findBySubjectIdOrderByDisplayOrderAsc(SUBJECT_ID))
                .thenReturn(List.of(first));

        service.moveChapter(SUBJECT_ID, 1L, MoveDirection.UP, HEAD_ID, Role.HEAD);

        assertThat(first.getDisplayOrder()).isEqualTo((short) 0);
        verify(chapterRepository, never()).saveAndFlush(any());
    }

    @Test
    void soft_delete_clears_display_order() {
        SubjectChapter ch = chapter(3L, "X", (short) 2);
        when(chapterRepository.findByIdAndSubjectId(3L, SUBJECT_ID)).thenReturn(Optional.of(ch));
        when(chapterRepository.save(any(SubjectChapter.class))).thenAnswer(inv -> inv.getArgument(0));

        service.softDeleteChapter(SUBJECT_ID, 3L, HEAD_ID, Role.HEAD);

        assertThat(ch.isDeleted()).isTrue();
        assertThat(ch.getDisplayOrder()).isNull();
        verify(chapterRepository).save(ch);
    }

    @Test
    void head_cross_department_denied_does_not_save() {
        when(subjectService.requireScopedSubject(eq(SUBJECT_ID), eq(HEAD_ID), eq(Role.HEAD)))
                .thenThrow(new AccessDeniedException("cross"));

        assertThatThrownBy(() ->
                service.createChapter(SUBJECT_ID, "Nope", HEAD_ID, Role.HEAD))
                .isInstanceOf(AccessDeniedException.class);
        verify(chapterRepository, never()).save(any());
    }

    @Test
    void list_maps_rows_in_order() {
        when(chapterRepository.findBySubjectIdOrderByDisplayOrderAsc(SUBJECT_ID))
                .thenReturn(List.of(
                        chapter(1L, "One", (short) 0),
                        chapter(2L, "Two", (short) 1)));

        List<ChapterRow> rows = service.listChapters(SUBJECT_ID, HEAD_ID, Role.HEAD);

        assertThat(rows).extracting(ChapterRow::title).containsExactly("One", "Two");
    }

    private static SubjectChapter chapter(Long id, String title, short order) {
        SubjectChapter c = new SubjectChapter(SUBJECT_ID, title, order, HEAD_ID);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }
}
