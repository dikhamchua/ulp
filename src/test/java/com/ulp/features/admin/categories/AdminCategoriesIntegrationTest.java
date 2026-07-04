package com.ulp.features.admin.categories;

import com.ulp.entities.Category;
import com.ulp.features.admin.categories.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration test for the {@code /admin/categories} screen.
 *
 * <p>Covers every requirement scenario from
 * {@code specs/admin-course-categories/spec.md}: tree render, ADMIN-only access,
 * create parent/child, slug auto-generation + collision suffix, blank-name
 * validation, two-level cap + self-parent rejection, delete guards (children /
 * course links), successful leaf delete, and active toggle.
 *
 * <p>Seed data (V2): parent {@code 'Lập trình'} (slug {@code lap-trinh}) has
 * children {@code Java / Python / Web Development}; {@code 'Tiếng Nhật N5'} is a
 * leaf. Seeded admin {@code admin@ulp.edu.vn} (V5) has the ADMIN role.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminCategoriesIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // ──────────────── List (tree) ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void list_renders_tree_with_parent_and_child() throws Exception {
        mockMvc.perform(get("/admin/categories"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/categories"))
                .andExpect(model().attribute("activeTab", "categories"))
                // Parent "Lập trình" and its child "Java" from the V2 seed.
                .andExpect(content().string(containsString("Lập trình")))
                .andExpect(content().string(containsString("Java")));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void list_returns_403_for_non_admin() throws Exception {
        mockMvc.perform(get("/admin/categories"))
                .andExpect(status().isForbidden());
    }

    // ──────────────── Create ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void create_parent_persists_with_null_parent_and_generated_slug() throws Exception {
        mockMvc.perform(post("/admin/categories").with(csrf())
                        .param("name", "Trí tuệ nhân tạo")
                        .param("description", "")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/categories"))
                .andExpect(flash().attributeExists("flashSuccess"));

        Category saved = categoryRepository.findByParentIdIsNullOrderByNameAsc().stream()
                .filter(c -> "Trí tuệ nhân tạo".equals(c.getName()))
                .findFirst().orElseThrow();
        assertThat(saved.getParentId()).isNull();
        assertThat(saved.getSlug()).isEqualTo("tri-tue-nhan-tao");
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void create_child_under_top_level_parent() throws Exception {
        Category parent = parentBySlug("lap-trinh");

        mockMvc.perform(post("/admin/categories").with(csrf())
                        .param("name", "Rust")
                        .param("parentId", String.valueOf(parent.getId()))
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        Category child = categoryRepository.findByParentIdOrderByNameAsc(parent.getId()).stream()
                .filter(c -> "Rust".equals(c.getName()))
                .findFirst().orElseThrow();
        assertThat(child.getParentId()).isEqualTo(parent.getId());
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void create_slug_collision_appends_numeric_suffix() throws Exception {
        // "Lập trình" already exists → slug "lap-trinh"; a second one must suffix.
        mockMvc.perform(post("/admin/categories").with(csrf())
                        .param("name", "Lập trình")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        boolean hasSuffixed = categoryRepository.findByParentIdIsNullOrderByNameAsc().stream()
                .anyMatch(c -> "lap-trinh-2".equals(c.getSlug()));
        assertThat(hasSuffixed).isTrue();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void create_blank_name_re_renders_form_with_field_error() throws Exception {
        mockMvc.perform(post("/admin/categories").with(csrf())
                        .param("name", "")
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/categories-form"))
                .andExpect(model().attributeHasFieldErrors("form", "name"));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void create_child_under_a_child_is_rejected() throws Exception {
        Category parent = parentBySlug("lap-trinh");
        Category child = categoryRepository.findByParentIdOrderByNameAsc(parent.getId())
                .stream().findFirst().orElseThrow();

        // Choosing a parent that is itself a child breaks the 2-level cap.
        mockMvc.perform(post("/admin/categories").with(csrf())
                        .param("name", "Quá sâu")
                        .param("parentId", String.valueOf(child.getId()))
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/categories-form"))
                .andExpect(model().attributeExists("flashError"));

        boolean created = categoryRepository.findAll().stream()
                .anyMatch(c -> "Quá sâu".equals(c.getName()));
        assertThat(created).isFalse();
    }

    // ──────────────── Edit ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void edit_rename_regenerates_slug() throws Exception {
        Category leaf = newLeaf("Tạm thời", "tam-thoi");

        mockMvc.perform(post("/admin/categories/" + leaf.getId() + "/edit").with(csrf())
                        .param("name", "Đã đổi tên")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        Category reloaded = categoryRepository.findById(leaf.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Đã đổi tên");
        assertThat(reloaded.getSlug()).isEqualTo("da-doi-ten");
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void edit_giving_a_parent_to_a_category_with_children_is_rejected() throws Exception {
        Category parentWithKids = parentBySlug("lap-trinh"); // has children in seed
        Category otherParent = parentBySlug("co-so-du-lieu");

        mockMvc.perform(post("/admin/categories/" + parentWithKids.getId() + "/edit").with(csrf())
                        .param("name", parentWithKids.getName())
                        .param("parentId", String.valueOf(otherParent.getId()))
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("flashError"));

        Category reloaded = categoryRepository.findById(parentWithKids.getId()).orElseThrow();
        assertThat(reloaded.getParentId()).isNull();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void edit_setting_self_as_parent_is_rejected() throws Exception {
        Category leaf = newLeaf("Tự thân", "tu-than");

        mockMvc.perform(post("/admin/categories/" + leaf.getId() + "/edit").with(csrf())
                        .param("name", leaf.getName())
                        .param("parentId", String.valueOf(leaf.getId()))
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("flashError"));

        Category reloaded = categoryRepository.findById(leaf.getId()).orElseThrow();
        assertThat(reloaded.getParentId()).isNull();
    }

    // ──────────────── Delete ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void delete_unused_leaf_hard_deletes_the_row() throws Exception {
        Category leaf = newLeaf("Xoá được", "xoa-duoc");

        mockMvc.perform(post("/admin/categories/" + leaf.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        assertThat(categoryRepository.findById(leaf.getId())).isEmpty();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void delete_blocked_when_category_has_children() throws Exception {
        Category parentWithKids = parentBySlug("lap-trinh");

        mockMvc.perform(post("/admin/categories/" + parentWithKids.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));

        assertThat(categoryRepository.findById(parentWithKids.getId())).isPresent();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void delete_blocked_when_category_is_linked_to_a_course() throws Exception {
        Category leaf = newLeaf("Đang dùng", "dang-dung");
        long courseId = insertCourse("Khoá học mẫu", "khoa-hoc-mau");
        jdbcTemplate.update(
                "INSERT INTO course_categories (course_id, category_id) VALUES (?, ?)",
                courseId, leaf.getId());

        mockMvc.perform(post("/admin/categories/" + leaf.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));

        assertThat(categoryRepository.findById(leaf.getId())).isPresent();
    }

    // ──────────────── Toggle ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void toggle_flips_active_state() throws Exception {
        Category leaf = newLeaf("Bật tắt", "bat-tat");
        assertThat(leaf.isActive()).isTrue();

        mockMvc.perform(post("/admin/categories/" + leaf.getId() + "/toggle").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        assertThat(categoryRepository.findById(leaf.getId()).orElseThrow().isActive()).isFalse();

        mockMvc.perform(post("/admin/categories/" + leaf.getId() + "/toggle").with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(categoryRepository.findById(leaf.getId()).orElseThrow().isActive()).isTrue();
    }

    // ──────────────── Helpers ────────────────

    private Category parentBySlug(String slug) {
        return categoryRepository.findByParentIdIsNullOrderByNameAsc().stream()
                .filter(c -> slug.equals(c.getSlug()))
                .findFirst().orElseThrow();
    }

    private Category newLeaf(String name, String slug) {
        return categoryRepository.save(new Category(name, slug, null, null, true));
    }

    /** Inserts a minimal course row (department 1 + admin user 1 both seeded). */
    private long insertCourse(String title, String slug) {
        Long deptId = jdbcTemplate.queryForObject(
                "SELECT id FROM departments ORDER BY id LIMIT 1", Long.class);
        Long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE role = 'ADMIN' ORDER BY id LIMIT 1", Long.class);
        jdbcTemplate.update(
                "INSERT INTO courses (title, slug, description, department_id, created_by, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'DRAFT')",
                title, slug, "seed", deptId, adminId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM courses WHERE slug = ?", Long.class, slug);
    }
}
