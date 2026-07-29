package com.ulp.features.tests.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.TestActivity;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.tests.dto.LecturerTestDtos.BankItemSnapshot;
import com.ulp.features.tests.dto.LecturerTestDtos.BankOptionSnapshot;
import com.ulp.features.tests.dto.LecturerTestDtos.ClassOption;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamForm;
import com.ulp.features.tests.dto.LecturerTestDtos.LecturerExamRow;
import com.ulp.features.tests.dto.LecturerTestDtos.OptionForm;
import com.ulp.features.tests.dto.LecturerTestDtos.QuestionForm;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.repository.TestRepository;
import com.ulp.features.tests.support.TestAccessResolver;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static com.ulp.common.IConstant.DEFAULT_EXAM_PAGE_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link LecturerExamService}.
 *
 * <p>{@code Sprint7ExamAuthoringIntegrationTest} already covers the HTTP surface
 * and the ownership gate. These tests target the decision logic that never shows
 * up in a status code: which write path a save takes when the bank is locked,
 * which audit rows a save appends, how {@code totalQuestions} is re-derived, and
 * the guards around insert-from-bank.</p>
 */
class LecturerExamServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long CLASS_ID = 33L;
    private static final Long TEST_ID = 500L;

    private final TestRepository testRepository = mock(TestRepository.class);
    private final QuestionRepository questionRepository = mock(QuestionRepository.class);
    private final ClassRepository classRepository = mock(ClassRepository.class);
    private final TestAccessResolver accessResolver = mock(TestAccessResolver.class);
    private final TestActivityWriter activityWriter = mock(TestActivityWriter.class);
    private final TakeViewBuilder takeViewBuilder = mock(TakeViewBuilder.class);
    private final ExamQuestionBankWriter questionBankWriter = mock(ExamQuestionBankWriter.class);
    private final ExamQuestionBankPickerService questionBankPicker =
            mock(ExamQuestionBankPickerService.class);

    private final LecturerExamService service = new LecturerExamService(
            testRepository, questionRepository, classRepository, accessResolver,
            activityWriter, takeViewBuilder, questionBankWriter, questionBankPicker);

    @BeforeEach
    void stubSaveAsIdentity() {
        // The entity has no id setter, so persistence is modelled as identity:
        // whatever is saved comes back unchanged.
        when(testRepository.save(any(Test.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    /** A class led by {@link #USER_ID}; mocked because ClassEntity has no id setter. */
    private ClassEntity ledClass(Long id, String name) {
        ClassEntity c = mock(ClassEntity.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        return c;
    }

    /** A persisted exam mock reporting the given id/status. */
    private Test persistedTest(Long id, String status) {
        Test test = mock(Test.class);
        when(test.getId()).thenReturn(id);
        when(test.getStatus()).thenReturn(status);
        when(test.getTitle()).thenReturn("Giữa kỳ");
        return test;
    }

    private static QuestionForm mcq(String content) {
        return new QuestionForm(null, Question.TYPE_MCQ, content, null, BigDecimal.ONE,
                List.of(new OptionForm(null, "A", true), new OptionForm(null, "B", false)));
    }

    /** A form that passes {@code ExamFormValidator}: title + class + one question. */
    private static ExamForm formWith(Long id, String status, List<QuestionForm> questions) {
        return new ExamForm(id, "Giữa kỳ", null, CLASS_ID, Test.TYPE_MOCK, status,
                Test.TIME_MODE_FIXED_WINDOW, 45, null, null, null, false, false,
                null, null, questions, false);
    }

    private void givenUserLeadsClass() {
        ClassEntity owned = mock(ClassEntity.class);
        when(owned.getLecturerId()).thenReturn(USER_ID);
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(owned));
    }

    // ── Listing ─────────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void listOwnedSortsByUpdatedAtDescendingWithTheConfiguredPageSize() {
        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(List.of());
        when(testRepository.findOwnedByLecturer(eq(USER_ID), anyList(), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listOwned(USER_ID, 2);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(testRepository).findOwnedByLecturer(eq(USER_ID), anyList(), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(DEFAULT_EXAM_PAGE_SIZE);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    @org.junit.jupiter.api.Test
    void listOwnedClampsNegativePagesToZero() {
        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(List.of());
        when(testRepository.findOwnedByLecturer(eq(USER_ID), anyList(), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listOwned(USER_ID, -5);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(testRepository).findOwnedByLecturer(eq(USER_ID), anyList(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    @org.junit.jupiter.api.Test
    void listOwnedPassesASentinelClassIdWhenTheLecturerLeadsNoClass() {
        // An empty IN clause is invalid JPQL, so the query must still get one id.
        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(List.of());
        when(testRepository.findOwnedByLecturer(eq(USER_ID), anyList(), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listOwned(USER_ID, 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(testRepository).findOwnedByLecturer(eq(USER_ID), captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).containsExactly(-1L);
    }

    @org.junit.jupiter.api.Test
    void listOwnedResolvesClassNamesAndDefaultsANullQuestionCountToZero() {
        Test row = mock(Test.class);
        when(row.getId()).thenReturn(TEST_ID);
        when(row.getTitle()).thenReturn("Giữa kỳ");
        when(row.getClassId()).thenReturn(CLASS_ID);
        when(row.getTotalQuestions()).thenReturn(null);

        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(List.of());
        when(testRepository.findOwnedByLecturer(eq(USER_ID), anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row)));
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
        when(testRepository.findOwnedByLecturer(eq(USER_ID), anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row)));

        LecturerExamRow mapped = service.listOwned(USER_ID, 0).getContent().get(0);

        assertThat(mapped.className()).isNull();
        verify(classRepository, never()).findAllById(anyList());
    }

    @org.junit.jupiter.api.Test
    void listForClassQueriesOnlyThatClass() {
        when(testRepository.findByClassId(eq(CLASS_ID), any(Pageable.class))).thenReturn(Page.empty());

        service.listForClass(CLASS_ID, 1);

        verify(testRepository).findByClassId(eq(CLASS_ID), eq(PageRequest.of(1,
                DEFAULT_EXAM_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "updatedAt"))));
    }

    @org.junit.jupiter.api.Test
    void ledClassesMapsEveryClassTheLecturerLeads() {
        List<ClassEntity> classes = List.of(ledClass(1L, "SE1701"), ledClass(2L, "SE1702"));
        when(classRepository.findAllByLecturerId(USER_ID)).thenReturn(classes);

        List<ClassOption> options = service.ledClasses(USER_ID);

        assertThat(options).containsExactly(
                new ClassOption(1L, "SE1701"), new ClassOption(2L, "SE1702"));
    }

    // ── Save: authorization ─────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void saveRejectsAClassTheLecturerDoesNotLeadWithoutPersistingAnything() {
        ClassEntity foreign = mock(ClassEntity.class);
        when(foreign.getLecturerId()).thenReturn(999L);
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.save(USER_ID, formWith(null, Test.STATUS_DRAFT, List.of(mcq("Q1")))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage(TestAccessResolver.NF_MSG);

        verify(testRepository, never()).save(any(Test.class));
    }

    @org.junit.jupiter.api.Test
    void saveRejectsAMissingClass() {
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(USER_ID, formWith(null, Test.STATUS_DRAFT, List.of(mcq("Q1")))))
                .isInstanceOf(AccessDeniedException.class);

        verify(testRepository, never()).save(any(Test.class));
    }

    @org.junit.jupiter.api.Test
    void saveRunsFormValidationBeforeTouchingAuthorizationOrPersistence() {
        // A blank title must fail fast — no repository call at all.
        ExamForm invalid = formWith(null, Test.STATUS_DRAFT, List.of(mcq("Q1")));
        ExamForm blankTitle = new ExamForm(invalid.id(), "   ", null, CLASS_ID, Test.TYPE_MOCK,
                Test.STATUS_DRAFT, Test.TIME_MODE_FIXED_WINDOW, 45, null, null, null,
                false, false, null, null, invalid.questions(), false);

        assertThatThrownBy(() -> service.save(USER_ID, blankTitle))
                .isInstanceOf(IllegalArgumentException.class);

        verify(classRepository, never()).findById(anyLong());
        verify(testRepository, never()).save(any(Test.class));
    }

    // ── Save: write path + derived state ────────────────────────────────

    @org.junit.jupiter.api.Test
    void creatingAnExamReplacesTheBankAndDerivesTotalQuestionsFromTheForm() {
        givenUserLeadsClass();
        List<QuestionForm> questions = List.of(mcq("Q1"), mcq("Q2"), mcq("Q3"));

        service.save(USER_ID, formWith(null, Test.STATUS_DRAFT, questions));

        ArgumentCaptor<Test> captor = ArgumentCaptor.forClass(Test.class);
        verify(testRepository, times(2)).save(captor.capture());
        assertThat(captor.getValue().getTotalQuestions()).isEqualTo(3);
        verify(questionBankWriter).replaceQuestions(any(), eq(questions));
        verify(questionBankWriter, never()).updateQuestionContentsInPlace(any(), anyList());
    }

    @org.junit.jupiter.api.Test
    void creatingAnExamNeverAsksWhetherTheBankIsLocked() {
        // A brand-new exam cannot have responses; the query would be wasted.
        givenUserLeadsClass();

        service.save(USER_ID, formWith(null, Test.STATUS_DRAFT, List.of(mcq("Q1"))));

        verify(questionBankWriter, never()).hasStudentResponses(any());
    }

    @org.junit.jupiter.api.Test
    void editingAnUngradedExamReplacesTheWholeBank() {
        givenUserLeadsClass();
        Test existing = persistedTest(TEST_ID, Test.STATUS_DRAFT);
        when(accessResolver.requireManageable(TEST_ID, USER_ID)).thenReturn(existing);
        when(questionBankWriter.hasStudentResponses(TEST_ID)).thenReturn(false);

        service.save(USER_ID, formWith(TEST_ID, Test.STATUS_DRAFT, List.of(mcq("Q1"))));

        verify(questionBankWriter).replaceQuestions(eq(TEST_ID), anyList());
        verify(questionBankWriter, never()).updateQuestionContentsInPlace(any(), anyList());
    }

    @org.junit.jupiter.api.Test
    void editingAGradedExamUpdatesContentInPlaceSoResponseForeignKeysSurvive() {
        givenUserLeadsClass();
        Test existing = persistedTest(TEST_ID, Test.STATUS_PUBLISHED);
        when(accessResolver.requireManageable(TEST_ID, USER_ID)).thenReturn(existing);
        when(questionBankWriter.hasStudentResponses(TEST_ID)).thenReturn(true);

        service.save(USER_ID, formWith(TEST_ID, Test.STATUS_PUBLISHED, List.of(mcq("Q1"))));

        verify(questionBankWriter).updateQuestionContentsInPlace(eq(TEST_ID), anyList());
        verify(questionBankWriter, never()).replaceQuestions(any(), anyList());
    }

    // ── Save: audit trail ───────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void creatingADraftWritesOnlyACreatedActivityRow() {
        givenUserLeadsClass();

        service.save(USER_ID, formWith(null, Test.STATUS_DRAFT, List.of(mcq("Q1"))));

        verify(activityWriter).write(any(), eq(TestActivity.TYPE_CREATED), anyString(), any(), eq(USER_ID));
        verify(activityWriter, never()).write(any(), eq(TestActivity.TYPE_PUBLISHED),
                anyString(), any(), anyLong());
    }

    @org.junit.jupiter.api.Test
    void creatingAlreadyPublishedCountsAsAPublishTransition() {
        givenUserLeadsClass();

        service.save(USER_ID, formWith(null, Test.STATUS_PUBLISHED, List.of(mcq("Q1"))));

        verify(activityWriter).write(any(), eq(TestActivity.TYPE_CREATED), anyString(), any(), eq(USER_ID));
        verify(activityWriter).write(any(), eq(TestActivity.TYPE_PUBLISHED),
                anyString(), isNull(), eq(USER_ID));
    }

    @org.junit.jupiter.api.Test
    void publishingADraftWritesBothAnUpdatedAndAPublishedRow() {
        givenUserLeadsClass();
        // The entity reports DRAFT when the previous status is read, then
        // PUBLISHED after applyFields has run — matching a real transition.
        Test existing = mock(Test.class);
        when(existing.getId()).thenReturn(TEST_ID);
        when(existing.getTitle()).thenReturn("Giữa kỳ");
        when(existing.getStatus()).thenReturn(Test.STATUS_DRAFT, Test.STATUS_PUBLISHED);
        when(accessResolver.requireManageable(TEST_ID, USER_ID)).thenReturn(existing);

        service.save(USER_ID, formWith(TEST_ID, Test.STATUS_PUBLISHED, List.of(mcq("Q1"))));

        verify(activityWriter).write(eq(TEST_ID), eq(TestActivity.TYPE_UPDATED),
                anyString(), any(), eq(USER_ID));
        verify(activityWriter).write(eq(TEST_ID), eq(TestActivity.TYPE_PUBLISHED),
                anyString(), isNull(), eq(USER_ID));
    }

    @org.junit.jupiter.api.Test
    void resavingAnAlreadyPublishedExamDoesNotWriteASecondPublishedRow() {
        givenUserLeadsClass();
        Test existing = persistedTest(TEST_ID, Test.STATUS_PUBLISHED);
        when(accessResolver.requireManageable(TEST_ID, USER_ID)).thenReturn(existing);

        service.save(USER_ID, formWith(TEST_ID, Test.STATUS_PUBLISHED, List.of(mcq("Q1"))));

        verify(activityWriter).write(eq(TEST_ID), eq(TestActivity.TYPE_UPDATED),
                anyString(), any(), eq(USER_ID));
        verify(activityWriter, never()).write(any(), eq(TestActivity.TYPE_PUBLISHED),
                anyString(), any(), anyLong());
    }

    // ── Insert from bank ────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void insertFromBankRejectsAnEmptySelectionBeforeSpendingAQuery() {
        Test test = persistedTest(TEST_ID, Test.STATUS_DRAFT);
        when(accessResolver.requireManageable(TEST_ID, USER_ID)).thenReturn(test);

        assertThatThrownBy(() -> service.insertFromBank(USER_ID, Role.LECTURER, TEST_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.insertFromBank(USER_ID, Role.LECTURER, TEST_ID, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(questionBankPicker, never()).approvedSnapshotsByIds(any(), any(), any(), anyList());
    }

    @org.junit.jupiter.api.Test
    void insertFromBankIsBlockedOnceStudentsHaveAnswered() {
        // Appending questions changes the bank shape, which is unsafe when graded.
        Test test = persistedTest(TEST_ID, Test.STATUS_PUBLISHED);
        when(accessResolver.requireManageable(TEST_ID, USER_ID)).thenReturn(test);
        when(questionBankWriter.hasStudentResponses(TEST_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.insertFromBank(USER_ID, Role.LECTURER, TEST_ID, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(questionBankWriter, never()).appendQuestions(any(), anyList());
        verify(questionBankPicker, never()).approvedSnapshotsByIds(any(), any(), any(), anyList());
    }

    @org.junit.jupiter.api.Test
    void insertFromBankRejectsIdsThatResolveToNoApprovedItem() {
        Test test = persistedTest(TEST_ID, Test.STATUS_DRAFT);
        when(accessResolver.requireManageable(TEST_ID, USER_ID)).thenReturn(test);
        when(questionBankPicker.approvedSnapshotsByIds(USER_ID, Role.LECTURER, TEST_ID, List.of(1L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.insertFromBank(USER_ID, Role.LECTURER, TEST_ID, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(questionBankWriter, never()).appendQuestions(any(), anyList());
    }

    @org.junit.jupiter.api.Test
    void insertFromBankCopiesTheSnapshotContentInsteadOfLinkingToTheBank() {
        Test test = persistedTest(TEST_ID, Test.STATUS_DRAFT);
        when(accessResolver.requireManageable(TEST_ID, USER_ID)).thenReturn(test);
        when(questionBankPicker.approvedSnapshotsByIds(USER_ID, Role.LECTURER, TEST_ID, List.of(9L)))
                .thenReturn(List.of(new BankItemSnapshot(9L, "Chương 1", Question.TYPE_MR,
                        "Nội dung câu hỏi", "Giải thích",
                        List.of(new BankOptionSnapshot("A", true),
                                new BankOptionSnapshot("B", true),
                                new BankOptionSnapshot("C", false)))));
        when(questionBankWriter.appendQuestions(eq(TEST_ID), anyList())).thenReturn(1);
        when(questionRepository.findByTestIdOrderBySortOrderAscIdAsc(TEST_ID))
                .thenReturn(List.of(mock(Question.class), mock(Question.class)));

        int inserted = service.insertFromBank(USER_ID, Role.LECTURER, TEST_ID, List.of(9L));

        assertThat(inserted).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QuestionForm>> captor = ArgumentCaptor.forClass(List.class);
        verify(questionBankWriter).appendQuestions(eq(TEST_ID), captor.capture());
        QuestionForm copied = captor.getValue().get(0);
        // Ids are dropped so the rows are exam-owned, not live-linked to the bank.
        assertThat(copied.id()).isNull();
        assertThat(copied.type()).isEqualTo(Question.TYPE_MR);
        assertThat(copied.content()).isEqualTo("Nội dung câu hỏi");
        assertThat(copied.explanation()).isEqualTo("Giải thích");
        assertThat(copied.points()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(copied.options()).extracting(OptionForm::id).containsOnlyNulls();
        assertThat(copied.options()).extracting(OptionForm::correct)
                .containsExactly(true, true, false);
    }

    @org.junit.jupiter.api.Test
    void insertFromBankRecountsTotalQuestionsFromTheDatabaseNotFromTheInsertedCount() {
        // The insert appends to whatever is already there, so the count must be
        // re-read rather than added to a stale value.
        Test test = persistedTest(TEST_ID, Test.STATUS_DRAFT);
        when(accessResolver.requireManageable(TEST_ID, USER_ID)).thenReturn(test);
        when(questionBankPicker.approvedSnapshotsByIds(any(), any(), any(), anyList()))
                .thenReturn(List.of(new BankItemSnapshot(9L, "Chương 1", Question.TYPE_MCQ,
                        "Q", null, List.of(new BankOptionSnapshot("A", true)))));
        when(questionBankWriter.appendQuestions(eq(TEST_ID), anyList())).thenReturn(1);
        when(questionRepository.findByTestIdOrderBySortOrderAscIdAsc(TEST_ID))
                .thenReturn(List.of(mock(Question.class), mock(Question.class),
                        mock(Question.class), mock(Question.class)));

        service.insertFromBank(USER_ID, Role.LECTURER, TEST_ID, List.of(9L));

        verify(test).setTotalQuestions(4);
        verify(testRepository).save(test);
        verify(activityWriter).write(eq(TEST_ID), eq(TestActivity.TYPE_UPDATED),
                anyString(), isNull(), eq(USER_ID));
    }
}
