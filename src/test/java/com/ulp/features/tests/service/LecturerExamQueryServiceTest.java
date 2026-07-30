package com.ulp.features.tests.service;

import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.tests.dto.LecturerTestDtos.ClassOption;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamFilter;
import com.ulp.features.tests.dto.LecturerTestDtos.LecturerExamRow;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.repository.TestRepository;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static com.ulp.common.IConstant.DEFAULT_EXAM_PAGE_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LecturerExamQueryService}.
 *
 * <p>{@code LecturerOwnedExamFilterIntegrationTest} and
 * {@code ClassExamFilterPaginationIntegrationTest} cover the HTTP surface and
 * the real SQL. These tests pin the arguments the service hands the repository —
 * page clamping, page size, sort mapping, the empty-IN sentinel, and the batched
 * class-name lookup — none of which are visible in a status code.</p>
 */
class LecturerExamQueryServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long CLASS_ID = 33L;
    private static final Long TEST_ID = 500L;

    private final TestRepository testRepository = mock(TestRepository.class);
    private final ClassRepository classRepository = mock(ClassRepository.class);

    private final LecturerExamQueryService service =
            new LecturerExamQueryService(testRepository, classRepository);

    /** A class led by {@link #USER_ID}; mocked because ClassEntity has no id setter. */
    private ClassEntity ledClass(Long id, String name) {
        ClassEntity c = mock(ClassEntity.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        return c;
    }

    /** Stubs the owned-exam query to return {@code page} for any arguments. */
    private void givenOwnedQueryReturns(Page<Test> page) {
        when(testRepository.searchOwnedByLecturer(eq(USER_ID), anyList(), any(),
                anyString(), any(), any(), any(Pageable.class))).thenReturn(page);
    }

    /** Captures the Pageable the owned-exam query was called with. */
    private Pageable capturedOwnedPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(testRepository).searchOwnedByLecturer(eq(USER_ID), anyList(), any(),
                anyString(), any(), any(), captor.capture());
        return captor.getValue();
    }

    // ── Paging + sorting ────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void listOwnedSortsByUpdatedAtDescendingWithTheConfiguredPageSize() {
        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(List.of());
        givenOwnedQueryReturns(Page.empty());

        service.listOwned(USER_ID, 2);

        Pageable pageable = capturedOwnedPageable();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(DEFAULT_EXAM_PAGE_SIZE);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    @org.junit.jupiter.api.Test
    void listOwnedClampsNegativePagesToZero() {
        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(List.of());
        givenOwnedQueryReturns(Page.empty());

        service.listOwned(USER_ID, -5);

        assertThat(capturedOwnedPageable().getPageNumber()).isZero();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("title sorts A→Z; closing sorts by endAt ascending (MySQL puts nulls first)")
    void listOwnedMapsEachSortKeyToItsColumn() {
        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(List.of());
        givenOwnedQueryReturns(Page.empty());

        service.listOwned(USER_ID, 0, ExamFilter.of(null, null, null, "title"));
        assertThat(capturedOwnedPageable().getSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "title"));
    }

    @org.junit.jupiter.api.Test
    void listOwnedSortsByDeadlineAscendingForTheClosingKey() {
        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(List.of());
        givenOwnedQueryReturns(Page.empty());

        service.listOwned(USER_ID, 0, ExamFilter.of(null, null, null, "closing"));
        assertThat(capturedOwnedPageable().getSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "endAt"));
    }

    // ── Ownership scope ─────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void listOwnedPassesASentinelClassIdWhenTheLecturerLeadsNoClass() {
        // An empty IN clause is invalid JPQL, so the query must still get one id.
        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(List.of());
        givenOwnedQueryReturns(Page.empty());

        service.listOwned(USER_ID, 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(testRepository).searchOwnedByLecturer(eq(USER_ID), captor.capture(), any(),
                anyString(), any(), any(), any(Pageable.class));
        assertThat(captor.getValue()).containsExactly(-1L);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("an unfiltered list sends no status/type/class predicate at all")
    void listOwnedSendsNullPredicatesWhenTheFilterIsEmpty() {
        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(List.of());
        givenOwnedQueryReturns(Page.empty());

        service.listOwned(USER_ID, 0);

        verify(testRepository).searchOwnedByLecturer(eq(USER_ID), anyList(), isNull(),
                eq(""), isNull(), isNull(), any(Pageable.class));
    }

    @org.junit.jupiter.api.Test
    void listOwnedForwardsTheClassDimensionWhenTheLecturerLeadsThatClass() {
        List<ClassEntity> classes = List.of(ledClass(CLASS_ID, "SE1701"));
        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(classes);
        givenOwnedQueryReturns(Page.empty());
        ExamFilter filter = ExamFilter.of(null, null, null, null, CLASS_ID,
                List.of(new ClassOption(CLASS_ID, "SE1701")));

        service.listOwned(USER_ID, 0, filter);

        verify(testRepository).searchOwnedByLecturer(eq(USER_ID), anyList(), eq(CLASS_ID),
                anyString(), isNull(), isNull(), any(Pageable.class));
    }

    // ── Row mapping ─────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void listOwnedResolvesClassNamesAndDefaultsANullQuestionCountToZero() {
        Test row = mock(Test.class);
        when(row.getId()).thenReturn(TEST_ID);
        when(row.getTitle()).thenReturn("Giữa kỳ");
        when(row.getClassId()).thenReturn(CLASS_ID);
        when(row.getTotalQuestions()).thenReturn(null);

        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(List.of());
        givenOwnedQueryReturns(new PageImpl<>(List.of(row)));
        // Built before the when(...) call: ledClass stubs internally, and Mockito
        // rejects a nested stubbing started inside an unfinished one.
        List<ClassEntity> classes = List.of(ledClass(CLASS_ID, "SE1701"));
        when(classRepository.findAllById(List.of(CLASS_ID))).thenReturn(classes);

        LecturerExamRow mapped = service.listOwned(USER_ID, 0).getContent().get(0);

        assertThat(mapped.className()).isEqualTo("SE1701");
        assertThat(mapped.totalQuestions()).isZero();
    }

    @org.junit.jupiter.api.Test
    void listOwnedSkipsTheClassNameLookupWhenNoExamHasAClass() {
        Test row = mock(Test.class);
        when(row.getClassId()).thenReturn(null);
        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(List.of());
        givenOwnedQueryReturns(new PageImpl<>(List.of(row)));

        LecturerExamRow mapped = service.listOwned(USER_ID, 0).getContent().get(0);

        assertThat(mapped.className()).isNull();
        verify(classRepository, never()).findAllById(anyList());
    }

    // ── Class-scoped listing ────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void listForClassQueriesOnlyThatClass() {
        when(testRepository.searchByClass(eq(CLASS_ID), anyString(), any(), any(),
                any(Pageable.class))).thenReturn(Page.empty());

        service.listForClass(CLASS_ID, 1);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(testRepository).searchByClass(eq(CLASS_ID), eq(""), isNull(), isNull(),
                captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(DEFAULT_EXAM_PAGE_SIZE);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    // ── Class picker ────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void ledClassesMapsEveryClassTheLecturerLeads() {
        List<ClassEntity> classes = List.of(ledClass(1L, "SE1701"), ledClass(2L, "SE1702"));
        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(classes);

        List<ClassOption> options = service.ledClasses(USER_ID);

        assertThat(options).containsExactly(
                new ClassOption(1L, "SE1701"), new ClassOption(2L, "SE1702"));
    }
}
