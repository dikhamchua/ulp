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
import com.ulp.features.tests.dto.LecturerTestDtos.BankChapterOption;
import com.ulp.features.tests.dto.LecturerTestDtos.BankItemSnapshot;
import com.ulp.features.tests.dto.LecturerTestDtos.BankSearchResult;
import com.ulp.features.tests.service.ExamQuestionBankPickerService;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the class-scoped picker used by exam <em>create</em> mode: it must offer
 * the same union the test-scoped picker offers (own private bank + HEAD bank of
 * the class subject) so authors see identical questions before and after the
 * exam row exists, and it must deny callers who do not lead the class.
 */
@SpringBootTest
@Transactional
class CreateModeBankPickerIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private com.ulp.features.tests.repository.TestRepository testRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private QuestionBankItemRepository itemRepository;
    @Autowired private QuestionBankOptionRepository bankOptionRepository;
    @Autowired private ExamQuestionBankPickerService pickerService;

    private Long lecturerId;
    private Long departmentId;
    private Long classId;
    private Long testId;
    private Long classSubjectId;
    private Long headItemId;
    private Long ownItemId;
    private Long otherSubjectItemId;
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
        classId = clazz.getId();
        classSubjectId = subject.getId();

        // Second active subject so the "other subject" exclusion is asserted even
        // when the seeded catalog ships only one active subject per department.
        subjectRepository.save(new Subject(departmentId, "CNTT103", "Môn khác cho create mode",
                "Second active subject for create-mode picker scoping", true, lecturerId));

        // A test on the same class, so both entry points can be compared directly.
        com.ulp.features.tests.entity.Test test =
                new com.ulp.features.tests.entity.Test(lecturerId, com.ulp.features.tests.entity.Test.TYPE_MOCK);
        test.setTitle("Đề đối chiếu create mode");
        test.setClassId(classId);
        testId = testRepository.save(test).getId();

        headItemId = saveItem(classSubjectId, null, QuestionBankItem.STATUS_ACTIVE, "Câu ngân hàng bộ môn");
        ownItemId = saveItem(classSubjectId, lecturerId, QuestionBankItem.STATUS_ACTIVE, "Câu cá nhân");
        Subject otherSubject = subjectRepository.findAllByDepartmentIdOrderByCodeAsc(departmentId).stream()
                .filter(s -> s.isActive() && !s.getId().equals(classSubjectId))
                .findFirst().orElse(null);
        if (otherSubject != null) {
            otherSubjectItemId = saveItem(otherSubject.getId(), lecturerId,
                    QuestionBankItem.STATUS_ACTIVE, "Câu môn khác");
        }
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
    void class_scoped_search_returns_same_union_as_test_scoped_search() {
        BankSearchResult byClass =
                pickerService.searchActiveForClass(lecturerId, Role.LECTURER, classId, null, null);
        List<BankItemSnapshot> byTest =
                pickerService.searchActive(lecturerId, Role.LECTURER, testId, null, null);

        assertThat(byClass.items()).isNotEmpty();
        assertThat(byClass.items().stream().map(BankItemSnapshot::id))
                .contains(headItemId, ownItemId);
        BankItemSnapshot head = byClass.items().stream()
                .filter(s -> s.id().equals(headItemId)).findFirst().orElseThrow();
        BankItemSnapshot own = byClass.items().stream()
                .filter(s -> s.id().equals(ownItemId)).findFirst().orElseThrow();
        assertThat(head.source()).isEqualTo(ExamQuestionBankPickerService.SOURCE_HEAD_BANK);
        assertThat(own.source()).isEqualTo(ExamQuestionBankPickerService.SOURCE_LECTURER_BANK);

        // Create mode must show exactly what edit mode shows for the same class.
        assertThat(byClass.items().stream().map(BankItemSnapshot::id).toList())
                .isEqualTo(byTest.stream().map(BankItemSnapshot::id).toList());
        assertThat(byClass.scope().subjectBound()).isTrue();
        assertThat(byClass.scope().classId()).isEqualTo(classId);
    }

    @Test
    void class_scoped_search_excludes_other_subject_and_archived_items() {
        BankSearchResult result =
                pickerService.searchActiveForClass(lecturerId, Role.LECTURER, classId, null, null);

        assertThat(result.items().stream().map(BankItemSnapshot::id))
                .doesNotContain(archivedItemId)
                .doesNotContain(otherSubjectItemId);
    }

    @Test
    void class_scoped_chapters_are_empty_when_class_has_no_subject() {
        ClassEntity clazz = classRepository.findById(classId).orElseThrow();
        clazz.setSubjectId(null);
        classRepository.save(clazz);

        // An authorized read with no scope is an empty state, never an exception.
        List<BankChapterOption> chapters =
                pickerService.chaptersForClass(lecturerId, Role.LECTURER, classId);
        assertThat(chapters).isEmpty();

        // The search still degrades to HEAD-bank items rather than failing.
        BankSearchResult result =
                pickerService.searchActiveForClass(lecturerId, Role.LECTURER, classId, null, null);
        assertThat(result.scope().subjectBound()).isFalse();
        assertThat(result.items().stream().map(BankItemSnapshot::id))
                .contains(headItemId)
                .doesNotContain(ownItemId);
    }

    @Test
    void class_scoped_search_denied_for_a_lecturer_who_does_not_lead_the_class() {
        User other = userRepository.findByEmailIgnoreCase("head@ulp.edu.vn").orElseThrow();

        // Authoring is lead-only, mirroring LecturerExamService.requireLeadsClass
        // which gates the save. Browsing a class one cannot author is a dead end.
        assertThatThrownBy(() -> pickerService.searchActiveForClass(
                other.getId(), other.getRole(), classId, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void class_scoped_search_denied_for_admin_without_department() {
        User admin = userRepository.findByEmailIgnoreCase("admin@ulp.edu.vn").orElseThrow();

        // ADMIN leads no class, so layer 2 rejects with AccessDeniedException,
        // which the controller maps to 403 — not a catch-all 500.
        assertThatThrownBy(() -> pickerService.searchActiveForClass(
                admin.getId(), admin.getRole(), classId, null, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> pickerService.chaptersForClass(
                admin.getId(), admin.getRole(), classId))
                .isInstanceOf(AccessDeniedException.class);
    }
}
