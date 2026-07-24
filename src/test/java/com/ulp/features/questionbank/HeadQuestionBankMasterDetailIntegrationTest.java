package com.ulp.features.questionbank;

import com.ulp.entities.Department;
import com.ulp.entities.User;
import com.ulp.features.admin.departments.repository.DepartmentRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.questionbank.dto.QuestionBankViews.CategoryDetailView;
import com.ulp.features.questionbank.entity.QuestionBankCategory;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.repository.QuestionBankCategoryRepository;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for the HEAD question-bank master-detail redesign: category
 * master, category detail, bulk review actions, cross-department isolation and
 * the single-review redirect.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HeadQuestionBankMasterDetailIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private QuestionBankCategoryRepository categoryRepository;
    @Autowired private QuestionBankItemRepository itemRepository;

    private Department cntt;
    private User head;
    private User lecturer;
    private Long categoryId;
    private Long reviewItemId;
    private Long reviewItemId2;
    private Long approvedItemId;

    @BeforeEach
    void setUp() {
        head = userRepository.findByEmailIgnoreCase("head@ulp.edu.vn").orElseThrow();
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        cntt = departmentRepository.findAll().stream()
                .filter(d -> "CNTT".equals(d.getCode()))
                .findFirst().orElseThrow();

        // Resolve HEAD's department via head_user_id (see HeadDepartmentResolver).
        cntt.assignHead(head.getId());
        departmentRepository.save(cntt);
        head.promoteToHead(cntt.getId());
        userRepository.save(head);
        lecturer.setDepartmentId(cntt.getId());
        userRepository.save(lecturer);

        QuestionBankCategory category = categoryRepository.save(new QuestionBankCategory(
                cntt.getId(), "Master-detail cat", "Mô tả", true, head.getId()));
        categoryId = category.getId();

        reviewItemId = saveItem(category.getId(), QuestionBankItem.STATUS_REVIEW, "Câu chờ duyệt 1");
        reviewItemId2 = saveItem(category.getId(), QuestionBankItem.STATUS_REVIEW, "Câu chờ duyệt 2");
        approvedItemId = saveItem(category.getId(), QuestionBankItem.STATUS_APPROVED, "Câu đã duyệt");
    }

    private Long saveItem(Long catId, String status, String content) {
        QuestionBankItem item = new QuestionBankItem(
                cntt.getId(), catId, lecturer.getId(),
                QuestionBankItem.TYPE_MCQ, status, "<p>" + content + "</p>", null);
        return itemRepository.save(item).getId();
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void master_renders_category_list() throws Exception {
        mockMvc.perform(get("/head/question-bank"))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/manage"))
                .andExpect(model().attributeExists("categories"));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void detail_lists_category_questions() throws Exception {
        mockMvc.perform(get("/head/question-bank/categories/" + categoryId))
                .andExpect(status().isOk())
                .andExpect(view().name("questionbank/category-detail"))
                .andExpect(model().attribute("categoryDetail",
                        org.hamcrest.Matchers.instanceOf(CategoryDetailView.class)));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void bulk_approve_transitions_all_review_items() throws Exception {
        mockMvc.perform(post("/head/question-bank/categories/" + categoryId + "/bulk/approve")
                        .param("itemIds", String.valueOf(reviewItemId), String.valueOf(reviewItemId2))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        assertThat(itemRepository.findById(reviewItemId).orElseThrow().getWorkflowStatus())
                .isEqualTo(QuestionBankItem.STATUS_APPROVED);
        assertThat(itemRepository.findById(reviewItemId2).orElseThrow().getWorkflowStatus())
                .isEqualTo(QuestionBankItem.STATUS_APPROVED);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void bulk_approve_partial_skips_non_review() throws Exception {
        // One REVIEW (approvable) + one APPROVED (skipped): only the REVIEW transitions.
        mockMvc.perform(post("/head/question-bank/categories/" + categoryId + "/bulk/approve")
                        .param("itemIds", String.valueOf(reviewItemId), String.valueOf(approvedItemId))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        assertThat(itemRepository.findById(reviewItemId).orElseThrow().getWorkflowStatus())
                .isEqualTo(QuestionBankItem.STATUS_APPROVED);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void bulk_approve_empty_selection_flashes_error() throws Exception {
        mockMvc.perform(post("/head/question-bank/categories/" + categoryId + "/bulk/approve")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void bulk_approve_forbidden_for_lecturer() throws Exception {
        mockMvc.perform(post("/head/question-bank/categories/" + categoryId + "/bulk/approve")
                        .param("itemIds", String.valueOf(reviewItemId))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void detail_cross_department_category_flashes_error() throws Exception {
        Department other = departmentRepository.findAll().stream()
                .filter(d -> !"CNTT".equals(d.getCode()))
                .findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(other != null, "needs a second department");
        QuestionBankCategory otherCat = categoryRepository.save(new QuestionBankCategory(
                other.getId(), "Danh mục khác bộ môn", null, true, head.getId()));

        // requireVisibleCategory throws → redirect to master with a flash error,
        // never exposing the other department's payload.
        mockMvc.perform(get("/head/question-bank/categories/" + otherCat.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/head/question-bank"))
                .andExpect(flash().attributeExists("flashError"))
                .andExpect(model().attributeDoesNotExist("categoryDetail"));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void single_approve_redirects_to_detail() throws Exception {
        mockMvc.perform(post("/head/question-bank/" + reviewItemId + "/approve")
                        .param("categoryId", String.valueOf(categoryId))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/head/question-bank/categories/" + categoryId));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void single_unarchive_restores_prior_status() throws Exception {
        // Archive an APPROVED item first so status_before_archive remembers APPROVED.
        mockMvc.perform(post("/head/question-bank/" + approvedItemId + "/archive")
                        .param("categoryId", String.valueOf(categoryId))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(itemRepository.findById(approvedItemId).orElseThrow().getWorkflowStatus())
                .isEqualTo(QuestionBankItem.STATUS_ARCHIVED);

        mockMvc.perform(post("/head/question-bank/" + approvedItemId + "/unarchive")
                        .param("categoryId", String.valueOf(categoryId))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/head/question-bank/categories/" + categoryId))
                .andExpect(flash().attributeExists("flashSuccess"));

        assertThat(itemRepository.findById(approvedItemId).orElseThrow().getWorkflowStatus())
                .isEqualTo(QuestionBankItem.STATUS_APPROVED);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void bulk_unarchive_restores_archived_items() throws Exception {
        // Archive two items first, then bulk-unarchive them back to their prior status.
        mockMvc.perform(post("/head/question-bank/categories/" + categoryId + "/bulk/archive")
                        .param("itemIds", String.valueOf(reviewItemId), String.valueOf(approvedItemId))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/head/question-bank/categories/" + categoryId + "/bulk/unarchive")
                        .param("itemIds", String.valueOf(reviewItemId), String.valueOf(approvedItemId))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        assertThat(itemRepository.findById(reviewItemId).orElseThrow().getWorkflowStatus())
                .isEqualTo(QuestionBankItem.STATUS_REVIEW);
        assertThat(itemRepository.findById(approvedItemId).orElseThrow().getWorkflowStatus())
                .isEqualTo(QuestionBankItem.STATUS_APPROVED);
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void unarchive_forbidden_for_lecturer() throws Exception {
        mockMvc.perform(post("/head/question-bank/" + approvedItemId + "/unarchive")
                        .param("categoryId", String.valueOf(categoryId))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void toggle_flips_active_flag() throws Exception {
        boolean before = categoryRepository.findById(categoryId).orElseThrow().isActive();
        mockMvc.perform(post("/head/question-bank/categories/" + categoryId + "/toggle")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(categoryRepository.findById(categoryId).orElseThrow().isActive()).isEqualTo(!before);
    }
}
