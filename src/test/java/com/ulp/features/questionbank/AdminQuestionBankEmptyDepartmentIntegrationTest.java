package com.ulp.features.questionbank;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Regression tests for the department-less question bank entry point.
 *
 * <p>The seeded ADMIN account has no {@code department_id}, so the shared
 * question bank cannot resolve a department for it. Two guarantees are covered:
 * the header no longer offers the question bank link to ADMIN, and the screens
 * still render their empty state instead of a 500 error page if reached by URL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminQuestionBankEmptyDepartmentIntegrationTest {

    private static final String QUESTION_BANK_LINK = "/lecturer/question-bank";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private QuestionBankItemRepository itemRepository;

    @BeforeEach
    void assertAdminHasNoDepartment() {
        User admin = userRepository.findByEmailIgnoreCase("admin@ulp.edu.vn").orElseThrow();
        assertThat(admin.getDepartmentId())
                .as("test premise: the seeded admin is not attached to a department")
                .isNull();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void headerHidesQuestionBankLinkFromAdmin() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(QUESTION_BANK_LINK))));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void headerStillShowsQuestionBankLinkToLecturer() throws Exception {
        mockMvc.perform(get("/lecturer/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(QUESTION_BANK_LINK)));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void listRendersEmptyStateForDepartmentLessAdmin() throws Exception {
        mockMvc.perform(get("/lecturer/question-bank"))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/list"))
                .andExpect(model().attribute("emptyDepartment", true))
                .andExpect(model().attributeExists("items"))
                .andExpect(model().attributeExists("categories"));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void createFormRendersEmptyStateForDepartmentLessAdmin() throws Exception {
        mockMvc.perform(get("/lecturer/question-bank/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/form"))
                .andExpect(model().attribute("emptyDepartment", true))
                .andExpect(model().attributeExists("categories"));
    }

    /**
     * The write path must stay closed: a department-less caller cannot persist a
     * question even by posting the form directly, since every item is owned by a
     * department. The form is re-rendered with an explanatory message instead.
     */
    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void createPostPersistsNothingForDepartmentLessAdmin() throws Exception {
        long before = itemRepository.count();

        mockMvc.perform(post("/lecturer/question-bank")
                        .with(csrf())
                        .param("categoryId", "1")
                        .param("questionType", "MCQ")
                        .param("content", "Question authored without a department")
                        .param("options[0].content", "A")
                        .param("options[0].correct", "true")
                        .param("options[1].content", "B")
                        .param("workflowAction", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/form"))
                .andExpect(model().attributeExists("flashError"));

        assertThat(itemRepository.count())
                .as("a department-less caller must not create question bank items")
                .isEqualTo(before);
    }

    /** The HEAD-only curation area stays forbidden for ADMIN. */
    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void headCurationAreaRemainsForbiddenForAdmin() throws Exception {
        mockMvc.perform(get("/head/question-bank"))
                .andExpect(status().isForbidden());
    }
}
