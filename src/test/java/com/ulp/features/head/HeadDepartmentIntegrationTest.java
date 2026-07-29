package com.ulp.features.head;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Department;
import com.ulp.entities.User;
import com.ulp.features.admin.departments.repository.DepartmentRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for HEAD shell, dashboard, class approval queue, and report.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HeadDepartmentIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;

    private Department cntt;
    private User head;
    private User lecturer;

    @BeforeEach
    void setUp() {
        head = userRepository.findByEmailIgnoreCase("head@ulp.edu.vn").orElseThrow();
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        cntt = departmentRepository.findAll().stream()
                .filter(d -> "CNTT".equals(d.getCode()))
                .findFirst().orElseThrow();

        // Ensure HEAD resolution via head_user_id.
        cntt.assignHead(head.getId());
        departmentRepository.save(cntt);
        head.promoteToHead(cntt.getId());
        userRepository.save(head);

        lecturer.setDepartmentId(cntt.getId());
        userRepository.save(lecturer);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void dashboard_ok_for_head() throws Exception {
        mockMvc.perform(get("/head"))
                .andExpect(status().isOk())
                .andExpect(view().name("head/dashboard"))
                .andExpect(content().string(containsString("Dashboard bộ môn")));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void dashboard_403_for_student() throws Exception {
        mockMvc.perform(get("/head"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void dashboard_lists_only_department_classes() throws Exception {
        ClassEntity inDept = new ClassEntity(
                "Lớp CNTT Head", head.getId(), head.getId(),
                "desc", null, null, 50);
        inDept.setCode("HCN01");
        inDept.setDepartmentId(cntt.getId());
        classRepository.save(inDept);

        Department other = departmentRepository.findAll().stream()
                .filter(d -> "KT".equals(d.getCode()))
                .findFirst().orElseThrow();
        ClassEntity outDept = new ClassEntity(
                "Lớp KT Outside", lecturer.getId(), lecturer.getId(),
                "desc", null, null, 50);
        outDept.setCode("HKT01");
        outDept.setDepartmentId(other.getId());
        classRepository.save(outDept);

        mockMvc.perform(get("/head"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lớp CNTT Head")))
                .andExpect(content().string(not(containsString("Lớp KT Outside"))));
    }

    // ───────────────── Class approval queue ─────────────────

    /** Saves a class in the given department; it starts DRAFT per the entity constructor. */
    private ClassEntity draftClass(String name, String code, Long departmentId) {
        ClassEntity c = new ClassEntity(name, lecturer.getId(), lecturer.getId(),
                "desc", null, null, 50);
        c.setCode(code);
        c.setDepartmentId(departmentId);
        return classRepository.save(c);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void approvals_queue_lists_only_own_department_drafts() throws Exception {
        draftClass("Lớp Chờ Duyệt", "HAP01", cntt.getId());

        Department other = departmentRepository.findAll().stream()
                .filter(d -> "KT".equals(d.getCode()))
                .findFirst().orElseThrow();
        draftClass("Lớp Ngoài Bộ Môn", "HAP02", other.getId());

        // Already-approved class of the same department must not be listed.
        ClassEntity approved = draftClass("Lớp Đã Duyệt", "HAP03", cntt.getId());
        approved.approve(head.getId(), LocalDateTime.now());
        classRepository.save(approved);

        mockMvc.perform(get("/head/approvals"))
                .andExpect(status().isOk())
                .andExpect(view().name("head/approvals"))
                .andExpect(content().string(containsString("Lớp Chờ Duyệt")))
                .andExpect(content().string(not(containsString("Lớp Ngoài Bộ Môn"))))
                .andExpect(content().string(not(containsString("Lớp Đã Duyệt"))));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void approve_moves_draft_to_upcoming_and_records_reviewer() throws Exception {
        ClassEntity saved = draftClass("Lớp Duyệt", "HAV01", cntt.getId());
        assertThat(saved.getStatus()).isEqualTo(ClassEntity.STATUS_DRAFT);

        mockMvc.perform(post("/head/approvals/" + saved.getId() + "/approve").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        ClassEntity updated = classRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ClassEntity.STATUS_UPCOMING);
        assertThat(updated.getApprovedBy()).isEqualTo(head.getId());
        assertThat(updated.getApprovedAt()).isNotNull();
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void reject_records_note_and_is_terminal() throws Exception {
        ClassEntity saved = draftClass("Lớp Từ Chối", "HRJ01", cntt.getId());

        mockMvc.perform(post("/head/approvals/" + saved.getId() + "/reject").with(csrf())
                        .param("note", "Thiếu đề cương"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        ClassEntity updated = classRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ClassEntity.STATUS_REJECTED);
        assertThat(updated.getRejectionNote()).isEqualTo("Thiếu đề cương");
        assertThat(updated.getApprovedBy()).isEqualTo(head.getId());
        assertThat(updated.getApprovedAt()).isNotNull();
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void reject_with_blank_note_stores_null() throws Exception {
        ClassEntity saved = draftClass("Lớp Từ Chối Trống", "HRJ02", cntt.getId());

        mockMvc.perform(post("/head/approvals/" + saved.getId() + "/reject").with(csrf())
                        .param("note", "   "))
                .andExpect(status().is3xxRedirection());

        ClassEntity updated = classRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ClassEntity.STATUS_REJECTED);
        assertThat(updated.getRejectionNote()).isNull();
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void approve_cross_department_class_denied_and_status_unchanged() throws Exception {
        Department other = departmentRepository.findAll().stream()
                .filter(d -> "KT".equals(d.getCode()))
                .findFirst().orElseThrow();
        ClassEntity saved = draftClass("Lớp Bộ Môn Khác", "HFR01", other.getId());

        mockMvc.perform(post("/head/approvals/" + saved.getId() + "/approve").with(csrf()))
                .andExpect(status().isForbidden());

        ClassEntity unchanged = classRepository.findById(saved.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ClassEntity.STATUS_DRAFT);
        assertThat(unchanged.getApprovedBy()).isNull();
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void approving_non_draft_class_is_refused_as_invalid_transition() throws Exception {
        ClassEntity saved = draftClass("Lớp Duyệt Hai Lần", "HDB01", cntt.getId());
        saved.approve(head.getId(), LocalDateTime.now());
        classRepository.save(saved);

        // The second approval re-reads a non-DRAFT status and is refused.
        mockMvc.perform(post("/head/approvals/" + saved.getId() + "/approve").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));

        ClassEntity unchanged = classRepository.findById(saved.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ClassEntity.STATUS_UPCOMING);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void approving_rejected_class_is_refused() throws Exception {
        ClassEntity saved = draftClass("Lớp Đã Từ Chối", "HRD01", cntt.getId());
        saved.reject(head.getId(), "không đạt", LocalDateTime.now());
        classRepository.save(saved);

        mockMvc.perform(post("/head/approvals/" + saved.getId() + "/approve").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));

        ClassEntity unchanged = classRepository.findById(saved.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ClassEntity.STATUS_REJECTED);
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void approvals_queue_403_for_student() throws Exception {
        mockMvc.perform(get("/head/approvals"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void approvals_queue_403_for_lecturer() throws Exception {
        mockMvc.perform(get("/head/approvals"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void approve_and_reject_403_for_lecturer() throws Exception {
        ClassEntity saved = draftClass("Lớp GV Thử", "HLC01", cntt.getId());

        mockMvc.perform(post("/head/approvals/" + saved.getId() + "/approve").with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/head/approvals/" + saved.getId() + "/reject").with(csrf()))
                .andExpect(status().isForbidden());

        ClassEntity unchanged = classRepository.findById(saved.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ClassEntity.STATUS_DRAFT);
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void approve_403_for_student() throws Exception {
        mockMvc.perform(post("/head/approvals/1/approve").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void report_ok_and_scoped() throws Exception {
        ClassEntity inDept = new ClassEntity(
                "Lớp Report", head.getId(), head.getId(),
                "desc", null, null, 50);
        inDept.setCode("HRP01");
        inDept.setDepartmentId(cntt.getId());
        classRepository.save(inDept);

        mockMvc.perform(get("/head/report"))
                .andExpect(status().isOk())
                .andExpect(view().name("head/report"))
                .andExpect(content().string(containsString("Lớp Report")));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void empty_state_when_no_department() throws Exception {
        // Clear head assignment and department_id so resolver returns empty.
        for (Department d : departmentRepository.findAll()) {
            if (head.getId().equals(d.getHeadUserId())) {
                d.assignHead(null);
                departmentRepository.save(d);
            }
        }
        head.setDepartmentId(null);
        userRepository.save(head);

        mockMvc.perform(get("/head"))
                .andExpect(status().isOk())
                .andExpect(view().name("head/dashboard"))
                .andExpect(model().attribute("emptyDepartment", true))
                .andExpect(model().attribute("headDepartment", org.hamcrest.Matchers.nullValue()));
    }

    /**
     * Spec: "HEAD without a department" — the approval queue itself, not just the
     * dashboard, must render the empty-department state rather than throwing.
     */
    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void approvals_queue_empty_state_when_no_department() throws Exception {
        // A draft exists, but it must not leak to a HEAD with no department.
        draftClass("Lớp Không Thuộc Ai", "HNE01", cntt.getId());

        for (Department d : departmentRepository.findAll()) {
            if (head.getId().equals(d.getHeadUserId())) {
                d.assignHead(null);
                departmentRepository.save(d);
            }
        }
        head.setDepartmentId(null);
        userRepository.save(head);

        mockMvc.perform(get("/head/approvals"))
                .andExpect(status().isOk())
                .andExpect(view().name("head/approvals"))
                .andExpect(model().attribute("emptyDepartment", true))
                .andExpect(model().attribute("headDepartment", org.hamcrest.Matchers.nullValue()))
                .andExpect(content().string(not(containsString("Lớp Không Thuộc Ai"))));
    }
}
