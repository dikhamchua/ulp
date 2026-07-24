package com.ulp.features.questionbank;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.questionbank.entity.QuestionBankCategory;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.entity.QuestionBankOption;
import com.ulp.features.questionbank.repository.QuestionBankCategoryRepository;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import com.ulp.features.questionbank.repository.QuestionBankOptionRepository;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.QuestionOption;
import com.ulp.features.tests.repository.QuestionOptionRepository;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.repository.TestRepository;
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
 * Verifies that inserting an approved shared-bank question into a test copies it
 * as an exam-owned snapshot: later bank edits do not mutate inserted questions.
 */
@SpringBootTest
@Transactional
class ExamQuestionBankInsertIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private TestRepository testRepository;
    @Autowired private QuestionBankCategoryRepository categoryRepository;
    @Autowired private QuestionBankItemRepository itemRepository;
    @Autowired private QuestionBankOptionRepository bankOptionRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuestionOptionRepository optionRepository;
    @Autowired private LecturerExamService examService;

    private Long lecturerId;
    private Long departmentId;
    private Long testId;
    private Long approvedItemId;

    @BeforeEach
    void setUp() {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        lecturerId = lecturer.getId();
        departmentId = lecturer.getDepartmentId();

        ClassEntity clazz = classRepository.findAllByLecturerId(lecturerId).stream().findFirst().orElseThrow();
        com.ulp.features.tests.entity.Test test =
                new com.ulp.features.tests.entity.Test(lecturerId, com.ulp.features.tests.entity.Test.TYPE_MOCK);
        test.setTitle("Bài test chèn từ ngân hàng");
        test.setClassId(clazz.getId());
        testId = testRepository.save(test).getId();

        QuestionBankCategory category = categoryRepository.save(new QuestionBankCategory(
                departmentId, "Chèn snapshot", null, true, lecturerId));
        QuestionBankItem item = itemRepository.save(new QuestionBankItem(
                departmentId, category.getId(), lecturerId,
                QuestionBankItem.TYPE_MCQ, QuestionBankItem.STATUS_APPROVED,
                "<p>Nội dung gốc</p>", "<p>Giải thích gốc</p>"));
        bankOptionRepository.save(new QuestionBankOption(item.getId(), "<p>Đáp án A</p>", true, 1));
        bankOptionRepository.save(new QuestionBankOption(item.getId(), "<p>Đáp án B</p>", false, 2));
        approvedItemId = item.getId();
    }

    @Test
    void insert_copies_snapshot_and_later_bank_edits_do_not_mutate_inserted_question() {
        int inserted = examService.insertFromBank(
                lecturerId, Role.LECTURER, testId, List.of(approvedItemId));
        assertThat(inserted).isEqualTo(1);

        List<Question> questions = questionRepository.findByTestIdOrderBySortOrderAscIdAsc(testId);
        assertThat(questions).hasSize(1);
        Question inserted1 = questions.get(0);
        assertThat(inserted1.getContent()).contains("Nội dung gốc");
        List<QuestionOption> options = optionRepository.findByQuestionIdOrderBySortOrderAscIdAsc(inserted1.getId());
        assertThat(options).hasSize(2);
        assertThat(options.get(0).isCorrect()).isTrue();

        // Mutate the bank item after the insert: the copied exam question must NOT change.
        QuestionBankItem bankItem = itemRepository.findById(approvedItemId).orElseThrow();
        bankItem.updateAuthoring(bankItem.getCategoryId(), QuestionBankItem.TYPE_MCQ,
                "<p>Nội dung ĐÃ SỬA</p>", "<p>Giải thích đã sửa</p>");
        itemRepository.save(bankItem);

        Question afterEdit = questionRepository.findById(inserted1.getId()).orElseThrow();
        assertThat(afterEdit.getContent()).contains("Nội dung gốc");
        assertThat(afterEdit.getContent()).doesNotContain("ĐÃ SỬA");
    }

    @Test
    void insert_with_empty_selection_is_rejected() {
        assertThatThrownBy(() -> examService.insertFromBank(lecturerId, Role.LECTURER, testId, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
