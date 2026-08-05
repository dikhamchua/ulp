package com.ulp.features.questionbank;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.entity.QuestionBankOption;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import com.ulp.features.questionbank.repository.QuestionBankOptionRepository;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.repository.SubjectRepository;
import com.ulp.features.tests.dto.LecturerTestDtos.BankItemSnapshot;
import com.ulp.features.tests.dto.LecturerTestDtos.BankSearchResult;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.QuestionOption;
import com.ulp.features.tests.repository.QuestionOptionRepository;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.repository.TestRepository;
import com.ulp.features.tests.service.ExamQuestionBankPickerService;
import com.ulp.features.tests.service.LecturerExamService;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the exam bank picker sources ACTIVE items from the author's own
 * private bank plus the HEAD bank of the class subject (each source-labelled),
 * and that inserting them copies exam-owned snapshots: later bank edits do not
 * mutate inserted questions.
 */
@SpringBootTest
@Transactional
class ExamQuestionBankInsertIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private TestRepository testRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private QuestionBankItemRepository itemRepository;
    @Autowired private QuestionBankOptionRepository bankOptionRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuestionOptionRepository optionRepository;
    @Autowired private LecturerExamService examService;
    @Autowired private ExamQuestionBankPickerService pickerService;

    private Long lecturerId;
    private Long departmentId;
    private Long testId;
    private Long classSubjectId;
    private Long headItemId;
    private Long ownItemId;
    private Long otherSubjectItemId;
    private Long otherSubjectHeadItemId;
    private Long archivedItemId;

    @BeforeEach
    void setUp() {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        lecturerId = lecturer.getId();
        departmentId = lecturer.getDepartmentId();

        ClassEntity clazz = classRepository.findAllByLecturerId(lecturerId).stream().findFirst().orElseThrow();
        Subject subject = subjectRepository.findAllByDepartmentIdOrderByCodeAsc(departmentId).stream()
                .filter(Subject::isActive)
                .findFirst().orElseThrow();
        clazz.setSubjectId(subject.getId());
        clazz.setDepartmentId(departmentId);
        classRepository.save(clazz);
        classSubjectId = subject.getId();

        // Ensure a second active subject exists so the "other subject" exclusion
        // is asserted even when the seeded catalog has only one active subject.
        subjectRepository.save(new Subject(departmentId, "CNTT102", "Môn khác CNTT",
                "Second active subject for picker scoping tests", true, lecturerId));

        com.ulp.features.tests.entity.Test test =
                new com.ulp.features.tests.entity.Test(lecturerId, com.ulp.features.tests.entity.Test.TYPE_MOCK);
        test.setTitle("Bài test chèn từ ngân hàng");
        test.setClassId(clazz.getId());
        testId = testRepository.save(test).getId();

        // HEAD bank item (ownerId null) of the class subject.
        headItemId = saveItem(classSubjectId, null, QuestionBankItem.STATUS_ACTIVE, "Câu ngân hàng bộ môn");
        // Lecturer's own private item of the class subject.
        ownItemId = saveItem(classSubjectId, lecturerId, QuestionBankItem.STATUS_ACTIVE, "Câu cá nhân");
        // Own item of a DIFFERENT subject (must be excluded by subject scoping).
        Subject otherSubject = subjectRepository.findAllByDepartmentIdOrderByCodeAsc(departmentId).stream()
                .filter(s -> s.isActive() && !s.getId().equals(classSubjectId))
                .findFirst().orElse(null);
        if (otherSubject != null) {
            otherSubjectItemId = saveItem(otherSubject.getId(), lecturerId,
                    QuestionBankItem.STATUS_ACTIVE, "Câu môn khác");
            otherSubjectHeadItemId = saveItem(otherSubject.getId(), null,
                    QuestionBankItem.STATUS_ACTIVE, "Câu HEAD môn khác");
        }
        // Archived own item of the class subject (must be excluded).
        archivedItemId = saveItem(classSubjectId, lecturerId,
                QuestionBankItem.STATUS_ARCHIVED, "Câu đã lưu trữ");
    }

    private Long saveItem(Long subjectId, Long ownerId, String status, String content) {
        QuestionBankItem item = new QuestionBankItem(
                departmentId, subjectId, ownerId, null, lecturerId,
                QuestionBankItem.TYPE_MCQ, status, "<p>" + content + "</p>", "<p>Giải thích gốc</p>");
        QuestionBankItem saved = itemRepository.save(item);
        bankOptionRepository.save(new QuestionBankOption(saved.getId(), "<p>Đáp án A</p>", true, 1));
        bankOptionRepository.save(new QuestionBankOption(saved.getId(), "<p>Đáp án B</p>", false, 2));
        return saved.getId();
    }

    @Test
    void picker_returns_own_and_head_bank_union_with_source_labels() {
        List<BankItemSnapshot> results =
                pickerService.searchActive(lecturerId, Role.LECTURER, testId, null, null);

        assertThat(results).isNotEmpty();
        assertThat(results.stream().map(BankItemSnapshot::id))
                .contains(headItemId, ownItemId);
        BankItemSnapshot head = results.stream().filter(s -> s.id().equals(headItemId)).findFirst().orElseThrow();
        BankItemSnapshot own = results.stream().filter(s -> s.id().equals(ownItemId)).findFirst().orElseThrow();
        assertThat(head.source()).isEqualTo(ExamQuestionBankPickerService.SOURCE_HEAD_BANK);
        assertThat(own.source()).isEqualTo(ExamQuestionBankPickerService.SOURCE_LECTURER_BANK);
    }

    @Test
    void picker_excludes_other_subject_and_archived_items() {
        List<BankItemSnapshot> results =
                pickerService.searchActive(lecturerId, Role.LECTURER, testId, null, null);

        assertThat(results.stream().map(BankItemSnapshot::id))
                .doesNotContain(archivedItemId)
                .doesNotContain(otherSubjectItemId);
    }

    @Test
    void picker_falls_back_to_department_when_class_has_no_subject() {
        ClassEntity clazz = classRepository.findAllByLecturerId(lecturerId).stream().findFirst().orElseThrow();
        clazz.setSubjectId(null);
        classRepository.save(clazz);

        List<BankItemSnapshot> results =
                pickerService.searchActive(lecturerId, Role.LECTURER, testId, null, null);

        // HEAD-bank items of the department are offered even without a subject,
        // but the author's own private items are NOT part of the fallback.
        assertThat(results.stream().map(BankItemSnapshot::id))
                .contains(headItemId)
                .doesNotContain(ownItemId);
    }

    @Test
    void search_with_scope_reports_subject_bound_when_class_has_subject() {
        BankSearchResult result =
                pickerService.searchActiveWithScope(lecturerId, Role.LECTURER, testId, null, null);

        assertThat(result.items()).isNotEmpty();
        assertThat(result.scope().subjectBound()).isTrue();
        assertThat(result.scope().subjectLabel()).isNotBlank();
        assertThat(result.scope().classId()).isNotNull();
        // No chapter and no query were supplied, so nothing narrowed the result.
        assertThat(result.scope().chapterFilterApplied()).isFalse();
    }

    @Test
    void search_with_scope_reports_subject_unbound_when_class_has_no_subject() {
        ClassEntity clazz = classRepository.findAllByLecturerId(lecturerId).stream().findFirst().orElseThrow();
        clazz.setSubjectId(null);
        classRepository.save(clazz);

        BankSearchResult result =
                pickerService.searchActiveWithScope(lecturerId, Role.LECTURER, testId, null, null);

        // The client uses these to explain the HEAD-bank-only degradation and to
        // link back to the class so the author can bind a subject.
        assertThat(result.scope().subjectBound()).isFalse();
        assertThat(result.scope().subjectLabel()).isNull();
        assertThat(result.scope().classId()).isEqualTo(clazz.getId());
    }

    @Test
    void search_with_scope_flags_chapter_filter_applied() {
        BankSearchResult byQuery =
                pickerService.searchActiveWithScope(lecturerId, Role.LECTURER, testId, null, "zzzqqq");
        assertThat(byQuery.items()).isEmpty();
        assertThat(byQuery.scope().chapterFilterApplied()).isTrue();

        // A chapter id alone must flag it too, so "clear filters" is offered.
        BankSearchResult byChapter =
                pickerService.searchActiveWithScope(lecturerId, Role.LECTURER, testId, -1L, null);
        assertThat(byChapter.items()).isEmpty();
        assertThat(byChapter.scope().chapterFilterApplied()).isTrue();
    }

    @Test
    void insert_from_bank_drops_head_item_of_another_subject() {
        if (otherSubjectHeadItemId == null) {
            return; // department has only one active subject — nothing to assert
        }
        // A HEAD-bank item of a DIFFERENT subject in the same department must not
        // be insertable into a test whose class teaches another subject (W1).
        assertThatThrownBy(() -> examService.insertFromBank(
                lecturerId, Role.LECTURER, testId, List.of(otherSubjectHeadItemId)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void insert_copies_snapshot_and_later_bank_edits_do_not_mutate_inserted_question() {
        int inserted = examService.insertFromBank(
                lecturerId, Role.LECTURER, testId, List.of(headItemId));
        assertThat(inserted).isEqualTo(1);

        List<Question> questions = questionRepository.findByTestIdOrderBySortOrderAscIdAsc(testId);
        assertThat(questions).hasSize(1);
        Question inserted1 = questions.get(0);
        assertThat(inserted1.getContent()).contains("Câu ngân hàng bộ môn");
        List<QuestionOption> options = optionRepository.findByQuestionIdOrderBySortOrderAscIdAsc(inserted1.getId());
        assertThat(options).hasSize(2);
        assertThat(options.get(0).isCorrect()).isTrue();

        // Mutate the bank item after the insert: the copied exam question must NOT change.
        QuestionBankItem bankItem = itemRepository.findById(headItemId).orElseThrow();
        bankItem.updateAuthoring(bankItem.getSubjectId(), bankItem.getChapterId(), QuestionBankItem.TYPE_MCQ,
                "<p>Nội dung ĐÃ SỬA</p>", "<p>Giải thích đã sửa</p>");
        itemRepository.save(bankItem);

        Question afterEdit = questionRepository.findById(inserted1.getId()).orElseThrow();
        assertThat(afterEdit.getContent()).contains("Câu ngân hàng bộ môn");
        assertThat(afterEdit.getContent()).doesNotContain("ĐÃ SỬA");
    }

    @Test
    void insert_with_empty_selection_is_rejected() {
        assertThatThrownBy(() -> examService.insertFromBank(lecturerId, Role.LECTURER, testId, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
