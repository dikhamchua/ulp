package com.ulp.classes;

import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import com.ulp.classes.entity.ClassActivity;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.repository.ClassActivityRepository;
import com.ulp.classes.repository.ClassRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 2 integration test cho Lecturer Classes CRUD.
 *
 * <p>Phu kich ban tu {@code specs/lecturer-classes/spec.md}: list role-scope,
 * create + validation, edit + authz, soft-delete + audit.
 *
 * <p>Seed users tu V5__seed_test_users.sql:
 * lecturer@ulp.edu.vn, head@ulp.edu.vn, admin@ulp.edu.vn, student@ulp.edu.vn.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Sprint2ClassesIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ClassRepository classRepository;
    @Autowired private ClassActivityRepository activityRepository;
    @Autowired private UserRepository userRepository;
    @PersistenceContext private EntityManager em;

    private User lecturer;
    private User otherLecturer;
    private User head;
    private User admin;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmail("lecturer@ulp.edu.vn").orElseThrow();
        head = userRepository.findByEmail("head@ulp.edu.vn").orElseThrow();
        admin = userRepository.findByEmail("admin@ulp.edu.vn").orElseThrow();
        // We don't have a 2nd LECTURER seeded — simulate "other" via head id only when needed.
        otherLecturer = head;
    }

    // ───────────────────── List ─────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void list_lecturer_sees_only_own_classes() throws Exception {
        ClassEntity own = saveClass("Lect-Own", lecturer.getId(), "OWN01");
        ClassEntity other = saveClass("Head-Own", head.getId(), "HDA01");

        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lect-Own")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Head-Own"))));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void list_head_sees_all() throws Exception {
        saveClass("By-Lect", lecturer.getId(), "BYL01");
        saveClass("By-Head", head.getId(), "BYH01");

        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("By-Lect")))
                .andExpect(content().string(containsString("By-Head")));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void list_admin_sees_all() throws Exception {
        saveClass("Admin-See-1", lecturer.getId(), "ADM01");
        saveClass("Admin-See-2", head.getId(), "ADM02");

        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Admin-See-1")))
                .andExpect(content().string(containsString("Admin-See-2")));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void list_student_forbidden() throws Exception {
        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_anonymous_redirects_to_login() throws Exception {
        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void list_empty_state_when_no_classes() throws Exception {
        // Defensive: purge any leaked (committed) classes for this lecturer so the
        // empty-state assertion is not flaky against manual-smoke leftovers.
        classRepository.findAllByLecturerIdOrderByCreatedAtDesc(lecturer.getId()).forEach(c -> {
            c.softDelete();
            classRepository.saveAndFlush(c);
        });
        em.flush();
        em.clear();

        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa có lớp học nào")));
    }

    // ───────────────────── Create ─────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void create_happy_path_persists_and_logs_activity() throws Exception {
        long before = classRepository.count();
        long activityBefore = activityRepository.count();

        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Java cơ bản")
                        .param("description", "Khoá nhập môn")
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-12-31")
                        .param("maxStudents", "50"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/classes"));

        assertThat(classRepository.count()).isEqualTo(before + 1);
        assertThat(activityRepository.count()).isEqualTo(activityBefore + 1);

        ClassEntity saved = classRepository.findAllByLecturerIdOrderByCreatedAtDesc(lecturer.getId())
                .stream().filter(c -> "Java cơ bản".equals(c.getName())).findFirst().orElseThrow();
        assertThat(saved.getCode()).hasSize(5);
        assertThat(saved.getCode()).matches("[A-HJ-NP-Z2-9]+");
        assertThat(saved.getStatus()).isEqualTo("UPCOMING");
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void create_with_blank_name_rerenders_with_inline_error() throws Exception {
        long before = classRepository.count();

        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "")
                        .param("description", "preserved-input-marker"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tên lớp")))
                // input preservation: description value still rendered in textarea
                .andExpect(content().string(containsString("preserved-input-marker")));

        assertThat(classRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void create_with_end_before_start_rerenders_with_date_error() throws Exception {
        long before = classRepository.count();

        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Test")
                        .param("startDate", "2026-08-01")
                        .param("endDate", "2026-07-15"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ngày kết thúc phải sau ngày bắt đầu")));

        assertThat(classRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void create_with_end_equal_start_rerenders_with_date_error() throws Exception {
        long before = classRepository.count();

        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Test")
                        .param("startDate", "2026-08-01")
                        .param("endDate", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ngày kết thúc phải sau ngày bắt đầu")));

        assertThat(classRepository.count()).isEqualTo(before);
    }

    // ───────────────────── Edit ─────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void edit_by_owner_updates_and_logs_activity() throws Exception {
        ClassEntity entity = saveClass("Old", lecturer.getId(), "OLDED");
        long activityBefore = activityRepository.count();

        mockMvc.perform(post("/lecturer/classes/" + entity.getId()).with(csrf())
                        .param("name", "New")
                        .param("description", "Updated"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/classes"));

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("New");

        List<ClassActivity> all = activityRepository.findAll();
        assertThat(activityRepository.count()).isEqualTo(activityBefore + 1);
        ClassActivity latest = all.get(all.size() - 1);
        assertThat(latest.getType()).isEqualTo(ClassActivity.TYPE_UPDATED);
        assertThat(latest.getMetadata()).contains("Old").contains("New");
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void edit_by_non_owner_lecturer_returns_403() throws Exception {
        ClassEntity entity = saveClass("Owned by HEAD", head.getId(), "HDOWN");
        long activityBefore = activityRepository.count();

        mockMvc.perform(post("/lecturer/classes/" + entity.getId()).with(csrf())
                        .param("name", "Hijacked"))
                .andExpect(status().isForbidden());

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Owned by HEAD");
        assertThat(activityRepository.count()).isEqualTo(activityBefore);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void edit_by_head_succeeds_for_any_class() throws Exception {
        ClassEntity entity = saveClass("Lect class", lecturer.getId(), "LCEDT");

        mockMvc.perform(post("/lecturer/classes/" + entity.getId()).with(csrf())
                        .param("name", "Head edited"))
                .andExpect(status().is3xxRedirection());

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Head edited");
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void edit_by_admin_succeeds_for_any_class() throws Exception {
        ClassEntity entity = saveClass("Lect class admin", lecturer.getId(), "ADMED");

        mockMvc.perform(post("/lecturer/classes/" + entity.getId()).with(csrf())
                        .param("name", "Admin edited"))
                .andExpect(status().is3xxRedirection());

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Admin edited");
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void edit_nonexistent_returns_404() throws Exception {
        mockMvc.perform(get("/lecturer/classes/9999999/edit"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void edit_soft_deleted_returns_404() throws Exception {
        ClassEntity entity = saveClass("Gone", lecturer.getId(), "GONE1");
        entity.softDelete();
        classRepository.saveAndFlush(entity);
        // Clear L1 cache so @SQLRestriction is applied on the next read
        em.flush();
        em.clear();

        mockMvc.perform(get("/lecturer/classes/" + entity.getId() + "/edit"))
                .andExpect(status().isNotFound());
    }

    // ───────────────────── Soft-delete ─────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void delete_by_owner_marks_deleted_and_omits_from_list() throws Exception {
        ClassEntity entity = saveClass("ToRemove", lecturer.getId(), "REMOV");
        long activityBefore = activityRepository.count();

        mockMvc.perform(post("/lecturer/classes/" + entity.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/classes"));

        // Soft-deleted row excluded from list view
        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("ToRemove"))));

        assertThat(activityRepository.count()).isEqualTo(activityBefore + 1);
        List<ClassActivity> all = activityRepository.findAll();
        assertThat(all.get(all.size() - 1).getType()).isEqualTo(ClassActivity.TYPE_DELETED);
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void delete_by_non_owner_lecturer_returns_403() throws Exception {
        ClassEntity entity = saveClass("Owned head", head.getId(), "HDDEL");

        mockMvc.perform(post("/lecturer/classes/" + entity.getId() + "/delete").with(csrf()))
                .andExpect(status().isForbidden());

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.isDeleted()).isFalse();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void delete_by_admin_succeeds_on_any_class() throws Exception {
        ClassEntity entity = saveClass("Admin will delete", lecturer.getId(), "ADMDL");

        mockMvc.perform(post("/lecturer/classes/" + entity.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection());

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.isDeleted()).isTrue();
    }

    // ───────────────────── Authz: STUDENT denied on all write endpoints ─────────

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void create_form_student_forbidden() throws Exception {
        mockMvc.perform(get("/lecturer/classes/new"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void post_create_student_forbidden() throws Exception {
        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Hack"))
                .andExpect(status().isForbidden());
    }

    // ───────────────────── Validation: missing edge cases ─────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void create_with_too_short_name_rerenders_with_error() throws Exception {
        long before = classRepository.count();
        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "ab"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tên lớp 3")));
        assertThat(classRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void create_with_max_students_zero_rerenders_with_error() throws Exception {
        long before = classRepository.count();
        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Valid name")
                        .param("maxStudents", "0"))
                .andExpect(status().isOk());
        assertThat(classRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void create_with_max_students_omitted_defaults_to_100() throws Exception {
        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Default max test"))
                .andExpect(status().is3xxRedirection());

        ClassEntity saved = classRepository.findAllByLecturerIdOrderByCreatedAtDesc(lecturer.getId())
                .stream().filter(c -> "Default max test".equals(c.getName())).findFirst().orElseThrow();
        assertThat(saved.getMaxStudents()).isEqualTo(100);
    }

    // ───────────────────── HEAD creates → lecturer_id = HEAD ──────────────

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void create_by_head_assigns_head_as_lecturer() throws Exception {
        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "Created by head"))
                .andExpect(status().is3xxRedirection());

        ClassEntity saved = classRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> "Created by head".equals(c.getName())).findFirst().orElseThrow();
        assertThat(saved.getLecturerId()).isEqualTo(head.getId());
    }

    // ───────────────────── Edit: code immutable + delete-twice 404 ────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void edit_does_not_change_code() throws Exception {
        ClassEntity entity = saveClass("CodeStays", lecturer.getId(), "CDST1");
        String originalCode = entity.getCode();

        mockMvc.perform(post("/lecturer/classes/" + entity.getId()).with(csrf())
                        .param("name", "Renamed"))
                .andExpect(status().is3xxRedirection());

        ClassEntity reloaded = classRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.getCode()).isEqualTo(originalCode);
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void delete_already_deleted_returns_404() throws Exception {
        ClassEntity entity = saveClass("DelTwice", lecturer.getId(), "DELT2");
        entity.softDelete();
        classRepository.saveAndFlush(entity);
        em.flush();
        em.clear();

        mockMvc.perform(post("/lecturer/classes/" + entity.getId() + "/delete").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ───────────────────── Class detail tabs ─────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void detail_root_redirects_to_board() throws Exception {
        ClassEntity c = saveClass("DetailRoot", lecturer.getId(), "DTRT1");
        mockMvc.perform(get("/lecturer/classes/" + c.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/classes/" + c.getId() + "/board"));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void detail_members_renders_empty_state() throws Exception {
        ClassEntity c = saveClass("DetailMem", lecturer.getId(), "DTMM1");
        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/members"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Thành viên lớp học")))
                .andExpect(content().string(containsString("Chưa có học sinh nào")));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void detail_settings_renders_form_prefilled() throws Exception {
        ClassEntity c = saveClass("DetailSet", lecturer.getId(), "DTSS1");
        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cài đặt lớp học")))
                .andExpect(content().string(containsString("DetailSet")));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void detail_placeholder_tab_renders_label() throws Exception {
        ClassEntity c = saveClass("DetailPh", lecturer.getId(), "DTPH1");
        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/assignments"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bài tập")));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void detail_non_owner_lecturer_returns_403() throws Exception {
        ClassEntity c = saveClass("OwnedByHead", head.getId(), "OWNHD");
        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/board"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void detail_student_forbidden() throws Exception {
        ClassEntity c = saveClass("StudCheck", lecturer.getId(), "STUDC");
        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/members"))
                .andExpect(status().isForbidden());
    }

    private ClassEntity saveClass(String name, Long lecturerId, String code) {
        ClassEntity e = new ClassEntity(name, lecturerId, lecturerId, null, null, null, 100);
        e.setCode(code);
        try {
            return classRepository.saveAndFlush(e);
        } catch (DataIntegrityViolationException ex) {
            // Fall back: regenerate code (defensive, in case a prior leaked row exists)
            e.setCode(code + "x");
            return classRepository.saveAndFlush(e);
        }
    }
}
