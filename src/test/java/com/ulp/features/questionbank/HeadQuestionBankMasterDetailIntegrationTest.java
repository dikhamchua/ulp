package com.ulp.features.questionbank;

import com.ulp.entities.Department;
import com.ulp.entities.User;
import com.ulp.features.admin.departments.repository.DepartmentRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.entity.SubjectChapter;
import com.ulp.features.subjects.repository.SubjectChapterRepository;
import com.ulp.features.subjects.repository.SubjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for the HEAD question-bank manage screen: subject selector →
 * chapters + items, create/edit/archive, bulk archive/unarchive, cross-department
 * isolation, chapter-belongs-to-subject rejection and the HEAD empty state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HeadQuestionBankMasterDetailIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private SubjectChapterRepository chapterRepository;
    @Autowired private QuestionBankItemRepository itemRepository;

    private Department cntt;
    private User head;
    private User lecturer;
    private Long subjectId;
    private Long chapterId;
    private Long activeItemId;
    private Long archivedItemId;

    @BeforeEach
    void setUp() {
        head = userRepository.findByEmailIgnoreCase("head@ulp.edu.vn").orElseThrow();
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        cntt = departmentRepository.findAll().stream()
                .filter(d -> "CNTT".equals(d.getCode()))
                .findFirst().orElseThrow();

        cntt.assignHead(head.getId());
        departmentRepository.save(cntt);
        head.promoteToHead(cntt.getId());
        userRepository.save(head);
        lecturer.setDepartmentId(cntt.getId());
        userRepository.save(lecturer);

        Subject subject = subjectRepository.findAllByDepartmentIdOrderByCodeAsc(cntt.getId()).stream()
                .filter(Subject::isActive)
                .findFirst().orElseThrow();
        subjectId = subject.getId();
        chapterId = chapterRepository.save(new SubjectChapter(subjectId, "Chương 1", (short) 1, head.getId())).getId();

        activeItemId = saveHeadItem(null, QuestionBankItem.STATUS_ACTIVE, "Câu hoạt động");
        archivedItemId = saveHeadItem(null, QuestionBankItem.STATUS_ARCHIVED, "Câu lưu trữ");
    }

    private Long saveHeadItem(Long chapter, String status, String content) {
        QuestionBankItem item = new QuestionBankItem(
                cntt.getId(), subjectId, null, chapter, lecturer.getId(),
                QuestionBankItem.TYPE_MCQ, status, "<p>" + content + "</p>", null);
        return itemRepository.save(item).getId();
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void manage_renders_subject_selector() throws Exception {
        mockMvc.perform(get("/head/question-bank"))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/manage"))
                .andExpect(model().attributeExists("subjectOptions"))
                .andExpect(model().attribute("emptyDepartment", false));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void manage_with_subject_lists_items() throws Exception {
        mockMvc.perform(get("/head/question-bank").param("subjectId", String.valueOf(subjectId)))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/manage"))
                .andExpect(model().attributeExists("items"))
                .andExpect(model().attribute("selectedSubjectId", subjectId));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void create_item_is_active_and_owned_by_head_bank() throws Exception {
        long before = itemRepository.count();
        mockMvc.perform(post("/head/question-bank")
                        .with(csrf())
                        .param("subjectId", String.valueOf(subjectId))
                        .param("chapterId", String.valueOf(chapterId))
                        .param("questionType", "MCQ")
                        .param("content", "<p>Câu mới của HEAD</p>")
                        .param("options[0].content", "A")
                        .param("options[0].correct", "true")
                        .param("options[1].content", "B"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"))
                .andExpect(redirectedUrl("/head/question-bank?subjectId=" + subjectId));

        assertThat(itemRepository.count()).isEqualTo(before + 1);
        QuestionBankItem created = itemRepository.findAll().stream()
                .filter(i -> "<p>Câu mới của HEAD</p>".equals(i.getContent()))
                .findFirst().orElseThrow();
        assertThat(created.getStatus()).isEqualTo(QuestionBankItem.STATUS_ACTIVE);
        assertThat(created.getOwnerId()).isNull();
        assertThat(created.getSubjectId()).isEqualTo(subjectId);
        assertThat(created.getChapterId()).isEqualTo(chapterId);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void create_rejects_inactive_subject_without_writing() throws Exception {
        Subject inactive = subjectRepository.findAllByDepartmentIdOrderByCodeAsc(cntt.getId()).stream()
                .filter(s -> !s.isActive())
                .findFirst().orElseThrow();
        long before = itemRepository.count();

        mockMvc.perform(post("/head/question-bank")
                        .with(csrf())
                        .param("subjectId", String.valueOf(inactive.getId()))
                        .param("questionType", "MCQ")
                        .param("content", "<p>Không được tạo</p>")
                        .param("options[0].content", "A")
                        .param("options[0].correct", "true")
                        .param("options[1].content", "B"))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/form"))
                .andExpect(model().attributeExists("flashError"));

        assertThat(itemRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void create_rejects_chapter_of_another_subject_without_writing() throws Exception {
        // A chapter belonging to a different department's subject.
        Department other = departmentRepository.findAll().stream()
                .filter(d -> !"CNTT".equals(d.getCode()))
                .findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(other != null, "needs a second department");
        Subject otherSubject = subjectRepository.findAllByDepartmentIdOrderByCodeAsc(other.getId()).stream()
                .filter(Subject::isActive)
                .findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(otherSubject != null, "needs a subject in the second department");
        SubjectChapter foreignChapter = chapterRepository.save(
                new SubjectChapter(otherSubject.getId(), "Chương lạ", (short) 1, head.getId()));

        long before = itemRepository.count();
        mockMvc.perform(post("/head/question-bank")
                        .with(csrf())
                        .param("subjectId", String.valueOf(subjectId))
                        .param("chapterId", String.valueOf(foreignChapter.getId()))
                        .param("questionType", "MCQ")
                        .param("content", "<p>Chương sai môn</p>")
                        .param("options[0].content", "A")
                        .param("options[0].correct", "true")
                        .param("options[1].content", "B"))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/form"))
                .andExpect(model().attributeExists("flashError"));

        assertThat(itemRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void archive_and_unarchive_head_item() throws Exception {
        mockMvc.perform(post("/head/question-bank/" + activeItemId + "/archive")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));
        assertThat(itemRepository.findById(activeItemId).orElseThrow().getStatus())
                .isEqualTo(QuestionBankItem.STATUS_ARCHIVED);

        mockMvc.perform(post("/head/question-bank/" + activeItemId + "/unarchive")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));
        assertThat(itemRepository.findById(activeItemId).orElseThrow().getStatus())
                .isEqualTo(QuestionBankItem.STATUS_ACTIVE);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void bulk_archive_and_unarchive() throws Exception {
        mockMvc.perform(post("/head/question-bank/bulk/archive")
                        .with(csrf())
                        .param("itemIds", String.valueOf(activeItemId)))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));
        assertThat(itemRepository.findById(activeItemId).orElseThrow().getStatus())
                .isEqualTo(QuestionBankItem.STATUS_ARCHIVED);

        mockMvc.perform(post("/head/question-bank/bulk/unarchive")
                        .with(csrf())
                        .param("itemIds", String.valueOf(activeItemId)))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));
        assertThat(itemRepository.findById(activeItemId).orElseThrow().getStatus())
                .isEqualTo(QuestionBankItem.STATUS_ACTIVE);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void bulk_archive_empty_selection_flashes_error() throws Exception {
        mockMvc.perform(post("/head/question-bank/bulk/archive")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void manage_forbidden_for_lecturer() throws Exception {
        mockMvc.perform(get("/head/question-bank"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void cross_department_head_item_not_manageable() throws Exception {
        Department other = departmentRepository.findAll().stream()
                .filter(d -> !"CNTT".equals(d.getCode()))
                .findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(other != null, "needs a second department");
        Subject otherSubject = subjectRepository.findAllByDepartmentIdOrderByCodeAsc(other.getId()).stream()
                .filter(Subject::isActive)
                .findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(otherSubject != null, "needs a subject in the second department");
        Long foreignItemId = itemRepository.save(new QuestionBankItem(
                other.getId(), otherSubject.getId(), null, null, head.getId(),
                QuestionBankItem.TYPE_MCQ, QuestionBankItem.STATUS_ACTIVE, "<p>Bộ môn khác</p>", null)).getId();

        mockMvc.perform(post("/head/question-bank/" + foreignItemId + "/archive")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));
        assertThat(itemRepository.findById(foreignItemId).orElseThrow().getStatus())
                .isEqualTo(QuestionBankItem.STATUS_ACTIVE);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void head_without_department_renders_empty_state() throws Exception {
        // Detach the seeded HEAD from every department so the resolver returns empty.
        cntt.assignHead(null);
        departmentRepository.save(cntt);
        head.setDepartmentId(null);
        userRepository.save(head);

        mockMvc.perform(get("/head/question-bank"))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/manage"))
                .andExpect(model().attribute("emptyDepartment", true))
                .andExpect(model().attributeDoesNotExist("subjects"));
    }
}
