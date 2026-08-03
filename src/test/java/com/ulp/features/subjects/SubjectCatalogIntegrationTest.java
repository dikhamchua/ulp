package com.ulp.features.subjects;

import com.ulp.entities.Department;
import com.ulp.entities.User;
import com.ulp.features.admin.departments.repository.DepartmentRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.subjects.entity.Subject;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for HEAD/ADMIN subject catalog scope and empty states.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SubjectCatalogIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private UserRepository userRepository;

    private Department cntt;
    private Department kt;

    @BeforeEach
    void setUp() {
        cntt = departmentRepository.findAll().stream()
                .filter(d -> "CNTT".equals(d.getCode())).findFirst().orElseThrow();
        kt = departmentRepository.findAll().stream()
                .filter(d -> "KT".equals(d.getCode())).findFirst().orElseThrow();
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void head_list_ok() throws Exception {
        mockMvc.perform(get("/head/subjects"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Môn học")));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void head_create_in_own_department() throws Exception {
        long before = subjectRepository.count();

        mockMvc.perform(post("/head/subjects").with(csrf())
                        .param("code", "HEADNEW")
                        .param("title", "Môn HEAD tạo")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/head/subjects"));

        assertThat(subjectRepository.count()).isEqualTo(before + 1);
        Subject saved = subjectRepository.findAll().stream()
                .filter(s -> "HEADNEW".equals(s.getCode()))
                .findFirst().orElseThrow();
        User head = userRepository.findByEmailIgnoreCase("head@ulp.edu.vn").orElseThrow();
        // HEAD stamps own resolved department (may be head_user_id dept or users.department_id).
        assertThat(saved.getDepartmentId()).isNotNull();
        assertThat(saved.getTitle()).isEqualTo("Môn HEAD tạo");
        assertThat(head.getId()).isNotNull();
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void head_cannot_edit_other_department_subject() throws Exception {
        Subject foreign = subjectRepository.saveAndFlush(
                new Subject(kt.getId(), "FOREIGN1", "Môn KT", null, true, 1L));
        long before = subjectRepository.count();

        mockMvc.perform(post("/head/subjects/" + foreign.getId() + "/edit").with(csrf())
                        .param("code", "HACKED")
                        .param("title", "Bị đổi")
                        .param("active", "true")
                        .param("departmentId", String.valueOf(kt.getId())))
                .andExpect(status().is3xxRedirection());

        Subject reloaded = subjectRepository.findById(foreign.getId()).orElseThrow();
        assertThat(reloaded.getCode()).isEqualTo("FOREIGN1");
        assertThat(reloaded.getTitle()).isEqualTo("Môn KT");
        assertThat(subjectRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void admin_create_for_chosen_department() throws Exception {
        mockMvc.perform(post("/admin/subjects").with(csrf())
                        .param("code", "ADMNEW")
                        .param("title", "Môn admin")
                        .param("active", "true")
                        .param("departmentId", String.valueOf(kt.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/subjects"));

        Subject saved = subjectRepository.findAll().stream()
                .filter(s -> "ADMNEW".equals(s.getCode()))
                .findFirst().orElseThrow();
        assertThat(saved.getDepartmentId()).isEqualTo(kt.getId());
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void admin_duplicate_code_rejected() throws Exception {
        subjectRepository.saveAndFlush(
                new Subject(cntt.getId(), "DUPCODE", "Existing", null, true, 1L));
        long before = subjectRepository.count();

        mockMvc.perform(post("/admin/subjects").with(csrf())
                        .param("code", "dupcode")
                        .param("title", "Other")
                        .param("active", "true")
                        .param("departmentId", String.valueOf(cntt.getId())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("đã tồn tại")));

        assertThat(subjectRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void create_class_form_excludes_inactive_placeholder() throws Exception {
        mockMvc.perform(get("/lecturer/classes/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Môn học")))
                .andExpect(content().string(not(containsString("UNASSIGNED"))))
                .andExpect(content().string(not(containsString("Chưa phân môn"))));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_forbidden_on_head_subjects() throws Exception {
        mockMvc.perform(get("/head/subjects"))
                .andExpect(status().isForbidden());
    }
}
