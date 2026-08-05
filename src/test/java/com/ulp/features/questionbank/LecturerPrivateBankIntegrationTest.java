package com.ulp.features.questionbank;

import com.ulp.entities.Department;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.admin.departments.repository.DepartmentRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.repository.SubjectRepository;
import com.ulp.security.Role;
import com.ulp.security.UlpUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for lecturer-private bank isolation: the lecturer list shows
 * only the actor's own items, HEAD cannot see or archive them, and the owner can
 * create/edit/archive their own items.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LecturerPrivateBankIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private QuestionBankItemRepository itemRepository;

    private User lecturer;
    private User otherLecturer;
    private User head;
    private Department cntt;
    private Long subjectId;
    private Long ownItemId;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        head = userRepository.findByEmailIgnoreCase("head@ulp.edu.vn").orElseThrow();
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

        ownItemId = itemRepository.save(new QuestionBankItem(
                cntt.getId(), subjectId, lecturer.getId(), null, lecturer.getId(),
                QuestionBankItem.TYPE_MCQ, QuestionBankItem.STATUS_ACTIVE, "<p>Câu riêng của GV</p>", null)).getId();

        // A second lecturer of the same department whose private bank is distinct
        // from the first lecturer's (used for cross-lecturer write denial tests).
        otherLecturer = UserFactory.newAdminCreated(
                "lecturer-b@ulp.edu.vn", "dummy-password-hash", "Giang Vien B",
                Role.LECTURER, true, null, null);
        otherLecturer.setDepartmentId(cntt.getId());
        otherLecturer = userRepository.save(otherLecturer);
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_list_shows_own_private_item() throws Exception {
        mockMvc.perform(get("/lecturer/question-bank"))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/list"))
                .andExpect(model().attributeExists("items"))
                .andExpect(model().attribute("emptyDepartment", false));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void head_list_does_not_show_lecturer_private_items() throws Exception {
        // HEAD reaching the lecturer screen sees their own (empty) private bank;
        // the first lecturer's private item must be absent from the rendered list.
        mockMvc.perform(get("/lecturer/question-bank"))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/list"))
                .andExpect(model().attribute("emptyDepartment", false))
                .andExpect(model().attribute("items", hasSize(0)))
                .andExpect(content().string(not(containsString("Câu riêng của GV"))));
    }

    @Test
    void other_lecturer_cannot_edit_lecturer_a_private_item() throws Exception {
        long before = itemRepository.count();
        QuestionBankItem original = itemRepository.findById(ownItemId).orElseThrow();

        // Act as the second lecturer POSTing an edit to the first lecturer's item.
        UlpUserDetails principal = new UlpUserDetails(otherLecturer);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        mockMvc.perform(post("/lecturer/question-bank/" + ownItemId + "/edit")
                        .with(authentication(auth))
                        .with(csrf())
                        .param("subjectId", String.valueOf(subjectId))
                        .param("questionType", QuestionBankItem.TYPE_MCQ)
                        .param("content", "<p>Nội dung sửa bởi giảng viên B</p>")
                        .param("explanation", "")
                        .param("options[0].content", "Đáp án A")
                        .param("options[0].correct", "true")
                        .param("options[1].content", "Đáp án B")
                        .param("options[1].correct", "false")
                        .param("options[2].content", "Đáp án C")
                        .param("options[2].correct", "false")
                        .param("options[3].content", "Đáp án D")
                        .param("options[3].correct", "false"))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/form"))
                .andExpect(model().attributeExists("flashError"));

        // The write must be denied by row content, not just by status code.
        QuestionBankItem after = itemRepository.findById(ownItemId).orElseThrow();
        assertThat(after.getContent()).isEqualTo(original.getContent());
        assertThat(after.getStatus()).isEqualTo(QuestionBankItem.STATUS_ACTIVE);
        assertThat(after.getSubjectId()).isEqualTo(original.getSubjectId());
        assertThat(itemRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void owner_can_archive_and_unarchive_own_item() throws Exception {
        mockMvc.perform(post("/lecturer/question-bank/" + ownItemId + "/archive")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));
        assertThat(itemRepository.findById(ownItemId).orElseThrow().getStatus())
                .isEqualTo(QuestionBankItem.STATUS_ARCHIVED);

        mockMvc.perform(post("/lecturer/question-bank/" + ownItemId + "/unarchive")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));
        assertThat(itemRepository.findById(ownItemId).orElseThrow().getStatus())
                .isEqualTo(QuestionBankItem.STATUS_ACTIVE);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void head_cannot_archive_lecturer_private_item() throws Exception {
        long before = itemRepository.count();
        mockMvc.perform(post("/head/question-bank/" + ownItemId + "/archive")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));
        assertThat(itemRepository.findById(ownItemId).orElseThrow().getStatus())
                .as("HEAD must not mutate a lecturer's private item")
                .isEqualTo(QuestionBankItem.STATUS_ACTIVE);
        assertThat(itemRepository.count()).isEqualTo(before);
    }
}
