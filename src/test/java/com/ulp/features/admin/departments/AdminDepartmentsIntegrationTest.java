package com.ulp.features.admin.departments;

import com.ulp.entities.Department;
import com.ulp.entities.DepartmentActivity;
import com.ulp.entities.User;
import com.ulp.features.admin.departments.repository.DepartmentActivityRepository;
import com.ulp.features.admin.departments.repository.DepartmentRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for {@code /admin/departments}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminDepartmentsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private DepartmentActivityRepository activityRepository;
    @Autowired private UserRepository userRepository;

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void list_renders_seeded_departments() throws Exception {
        mockMvc.perform(get("/admin/departments"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/departments"))
                .andExpect(model().attribute("activeTab", "departments"))
                .andExpect(content().string(containsString("Công nghệ thông tin")));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void list_name_links_to_edit() throws Exception {
        Department dept = departmentRepository.findAll().stream()
                .filter(d -> "CNTT".equals(d.getCode()))
                .findFirst().orElseThrow();

        mockMvc.perform(get("/admin/departments"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("dept-name-link")))
                .andExpect(content().string(containsString(
                        "/admin/departments/" + dept.getId() + "/edit")));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void edit_form_shows_info_and_history_tabs() throws Exception {
        Department dept = departmentRepository.findAll().stream()
                .filter(d -> "CNTT".equals(d.getCode()))
                .findFirst().orElseThrow();

        mockMvc.perform(get("/admin/departments/" + dept.getId() + "/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/departments-form"))
                .andExpect(model().attribute("activeDetailTab", "info"))
                .andExpect(content().string(containsString("Thông tin chung")))
                .andExpect(content().string(containsString("Lịch sử cập nhật")))
                .andExpect(content().string(containsString("dept-status-toggle")));

        mockMvc.perform(get("/admin/departments/" + dept.getId() + "/edit")
                        .param("tab", "history"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("activeDetailTab", "history"))
                .andExpect(model().attributeExists("activitiesPage"))
                .andExpect(content().string(containsString("Lịch sử cập nhật bộ môn")));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void list_returns_403_for_non_admin() throws Exception {
        mockMvc.perform(get("/admin/departments"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void create_persists_unique_department() throws Exception {
        mockMvc.perform(post("/admin/departments").with(csrf())
                        .param("name", "Khoa học máy tính")
                        .param("code", "khmt")
                        .param("description", "Test")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/departments"))
                .andExpect(flash().attributeExists("flashSuccess"));

        Department saved = departmentRepository.findAll().stream()
                .filter(d -> "KHMT".equals(d.getCode()))
                .findFirst().orElseThrow();
        assertThat(saved.getName()).isEqualTo("Khoa học máy tính");
        assertThat(saved.isActive()).isTrue();

        assertThat(activityRepository.findAll()).anyMatch(a ->
                saved.getId().equals(a.getDepartmentId())
                        && DepartmentActivity.TYPE_CREATED.equals(a.getType()));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void create_duplicate_code_rejected() throws Exception {
        mockMvc.perform(post("/admin/departments").with(csrf())
                        .param("name", "Duplicate CNTT")
                        .param("code", "CNTT")
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/departments-form"))
                .andExpect(model().attributeExists("flashError"));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void create_blank_name_field_error() throws Exception {
        mockMvc.perform(post("/admin/departments").with(csrf())
                        .param("name", "")
                        .param("code", "NEWX")
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/departments-form"))
                .andExpect(model().attributeHasFieldErrors("form", "name"));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void assign_lecturer_promotes_to_head() throws Exception {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        Department dept = departmentRepository.findAll().stream()
                .filter(d -> "KT".equals(d.getCode()))
                .findFirst().orElseThrow();

        mockMvc.perform(post("/admin/departments/" + dept.getId() + "/edit").with(csrf())
                        .param("name", dept.getName())
                        .param("code", dept.getCode())
                        .param("description", dept.getDescription() == null ? "" : dept.getDescription())
                        .param("active", "true")
                        .param("headUserId", String.valueOf(lecturer.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/departments/*/edit?tab=info"))
                .andExpect(flash().attributeExists("flashSuccess"));

        Department updated = departmentRepository.findById(dept.getId()).orElseThrow();
        User promoted = userRepository.findById(lecturer.getId()).orElseThrow();
        assertThat(updated.getHeadUserId()).isEqualTo(lecturer.getId());
        assertThat(promoted.getRole()).isEqualTo(Role.HEAD);
        assertThat(promoted.getDepartmentId()).isEqualTo(dept.getId());
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void reject_student_as_head() throws Exception {
        User student = userRepository.findByEmailIgnoreCase("student@ulp.edu.vn").orElseThrow();
        Department dept = departmentRepository.findAll().stream()
                .filter(d -> "NN".equals(d.getCode()))
                .findFirst().orElseThrow();
        Long previousHead = dept.getHeadUserId();

        mockMvc.perform(post("/admin/departments/" + dept.getId() + "/edit").with(csrf())
                        .param("name", dept.getName())
                        .param("code", dept.getCode())
                        .param("active", "true")
                        .param("headUserId", String.valueOf(student.getId())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/departments-form"))
                .andExpect(model().attributeExists("flashError"));

        Department unchanged = departmentRepository.findById(dept.getId()).orElseThrow();
        assertThat(unchanged.getHeadUserId()).isEqualTo(previousHead);
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void replace_head_demotes_previous() throws Exception {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        User head = userRepository.findByEmailIgnoreCase("head@ulp.edu.vn").orElseThrow();
        Department dept = departmentRepository.findAll().stream()
                .filter(d -> "CNTT".equals(d.getCode()))
                .findFirst().orElseThrow();

        // Ensure head is currently head of CNTT (seed may already set this).
        mockMvc.perform(post("/admin/departments/" + dept.getId() + "/edit").with(csrf())
                        .param("name", dept.getName())
                        .param("code", dept.getCode())
                        .param("active", "true")
                        .param("headUserId", String.valueOf(head.getId())))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/admin/departments/" + dept.getId() + "/edit").with(csrf())
                        .param("name", dept.getName())
                        .param("code", dept.getCode())
                        .param("active", "true")
                        .param("headUserId", String.valueOf(lecturer.getId())))
                .andExpect(status().is3xxRedirection());

        User previous = userRepository.findById(head.getId()).orElseThrow();
        User next = userRepository.findById(lecturer.getId()).orElseThrow();
        Department updated = departmentRepository.findById(dept.getId()).orElseThrow();
        assertThat(updated.getHeadUserId()).isEqualTo(lecturer.getId());
        assertThat(next.getRole()).isEqualTo(Role.HEAD);
        // Previous head demoted only if not head of any other dept.
        if (!departmentRepository.existsByHeadUserId(previous.getId())) {
            assertThat(previous.getRole()).isEqualTo(Role.LECTURER);
        }
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void unassign_head_demotes_when_no_other_dept() throws Exception {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        Department dept = departmentRepository.findAll().stream()
                .filter(d -> "CK".equals(d.getCode()))
                .findFirst().orElseThrow();

        mockMvc.perform(post("/admin/departments/" + dept.getId() + "/edit").with(csrf())
                        .param("name", dept.getName())
                        .param("code", dept.getCode())
                        .param("active", "true")
                        .param("headUserId", String.valueOf(lecturer.getId())))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/admin/departments/" + dept.getId() + "/edit").with(csrf())
                        .param("name", dept.getName())
                        .param("code", dept.getCode())
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection());

        Department updated = departmentRepository.findById(dept.getId()).orElseThrow();
        User demoted = userRepository.findById(lecturer.getId()).orElseThrow();
        assertThat(updated.getHeadUserId()).isNull();
        assertThat(demoted.getRole()).isEqualTo(Role.LECTURER);
        assertThat(demoted.getDepartmentId()).isEqualTo(dept.getId());
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void toggle_deactivates_department() throws Exception {
        Department dept = departmentRepository.findAll().stream()
                .filter(d -> "DDT".equals(d.getCode()))
                .findFirst().orElseThrow();
        boolean wasActive = dept.isActive();

        mockMvc.perform(post("/admin/departments/" + dept.getId() + "/toggle").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        Department updated = departmentRepository.findById(dept.getId()).orElseThrow();
        assertThat(updated.isActive()).isEqualTo(!wasActive);
    }
}
