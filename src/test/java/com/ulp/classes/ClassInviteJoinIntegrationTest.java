package com.ulp.classes;

import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import com.ulp.classes.dto.ClassesDtos.ClassForm;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.entity.ClassInviteCode;
import com.ulp.classes.entity.Enrollment;
import com.ulp.classes.repository.ClassInviteCodeRepository;
import com.ulp.classes.repository.ClassRepository;
import com.ulp.classes.repository.EnrollmentRepository;
import com.ulp.classes.service.ClassesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 2.3 sprint-level integration test covering scenarios across
 * the three capabilities:
 * <ul>
 *   <li>{@code class-invite-codes} — provision-on-create,
 *       regenerate happy / non-owner / invalid type /
 *       soft-deleted-class</li>
 *   <li>{@code student-classes} — join by CODE happy / lowercase /
 *       wrong-length / unknown / disabled / over-max / already-
 *       active / revive-REMOVED / reject-COMPLETED / class-
 *       CANCELLED / class-full / leave-happy / leave-not-enrolled /
 *       leave-already-removed / leave-COMPLETED / link redirect
 *       when anonymous / malformed-token-404</li>
 *   <li>{@code lecturer-classes} — Members tab displays active
 *       invite tokens</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ClassInviteJoinIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ClassRepository classRepository;
    @Autowired private ClassInviteCodeRepository inviteRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassesService classesService;
    @PersistenceContext private EntityManager em;

    private User lecturer;
    private User head;
    private User student;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        head = userRepository.findByEmailIgnoreCase("head@ulp.edu.vn").orElseThrow();
        student = userRepository.findByEmailIgnoreCase("student@ulp.edu.vn").orElseThrow();
    }

    // ───────────────── Provision on create ─────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void create_class_provisions_one_active_code_and_one_active_link() throws Exception {
        mockMvc.perform(post("/lecturer/classes").with(csrf())
                        .param("name", "ProvCheck")
                        .param("maxStudents", "100"))
                .andExpect(status().is3xxRedirection());

        ClassEntity saved = classRepository.findAllByLecturerIdOrderByCreatedAtDesc(lecturer.getId())
                .stream().filter(c -> "ProvCheck".equals(c.getName())).findFirst().orElseThrow();

        List<ClassInviteCode> tokens = inviteRepository.findAllByClassIdOrderByIdAsc(saved.getId());
        assertThat(tokens).hasSize(2);
        assertThat(tokens).allMatch(ClassInviteCode::isActive);
        assertThat(tokens).extracting(ClassInviteCode::getType)
                .containsExactlyInAnyOrder("CODE", "LINK");
        ClassInviteCode codeToken = tokens.stream()
                .filter(t -> t.getType().equals("CODE")).findFirst().orElseThrow();
        ClassInviteCode linkToken = tokens.stream()
                .filter(t -> t.getType().equals("LINK")).findFirst().orElseThrow();
        assertThat(codeToken.getCode()).matches("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$");
        assertThat(linkToken.getCode()).matches("^[A-Za-z0-9_-]{32}$");
    }

    // ───────────────── Regenerate ─────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void regenerate_by_owner_rotates_active_token() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "RegenOwner");
        String original = activeCode(c.getId());

        mockMvc.perform(post("/lecturer/classes/" + c.getId() + "/invite/regenerate")
                        .with(csrf()).param("type", "CODE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/classes/" + c.getId() + "/settings"));

        em.flush();
        em.clear();

        String newCode = activeCode(c.getId());
        assertThat(newCode).isNotEqualTo(original);
        // Old row retained for audit
        long total = inviteRepository.findAllByClassIdOrderByIdAsc(c.getId()).stream()
                .filter(t -> t.getType().equals("CODE")).count();
        assertThat(total).isGreaterThanOrEqualTo(2);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void regenerate_by_head_on_other_lecturers_class_succeeds() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "HeadRegen");

        mockMvc.perform(post("/lecturer/classes/" + c.getId() + "/invite/regenerate")
                        .with(csrf()).param("type", "LINK"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void regenerate_with_invalid_type_returns_400() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "BadType");

        mockMvc.perform(post("/lecturer/classes/" + c.getId() + "/invite/regenerate")
                        .with(csrf()).param("type", "FOO"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void regenerate_on_soft_deleted_class_returns_404() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "GoneClass");
        c.softDelete();
        classRepository.saveAndFlush(c);
        em.flush(); em.clear();

        mockMvc.perform(post("/lecturer/classes/" + c.getId() + "/invite/regenerate")
                        .with(csrf()).param("type", "CODE"))
                .andExpect(status().isNotFound());
    }

    // ───────────────── Settings tab (invite panel moved here in Sprint 2.4) ─────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void settings_tab_displays_active_code_and_link_url() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "SettingsDisplay");
        String activeCode = activeCode(c.getId());
        String activeLink = activeLink(c.getId());

        // Sprint 2.4 detail-page redesign: the invite panel lives on the
        // ?tab=invite sub-tab; default (info) renders the form instead.
        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/settings")
                        .param("tab", "invite"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mã mời")))
                .andExpect(content().string(containsString(activeCode)))
                .andExpect(content().string(containsString("Liên kết mời")))
                .andExpect(content().string(containsString("/j/" + activeLink)));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void settings_default_tab_renders_info_form() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "SettingsInfo");

        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/settings"))
                .andExpect(status().isOk())
                // Info card title + form fields
                .andExpect(content().string(containsString("Thông tin lớp")))
                .andExpect(content().string(containsString("id=\"nameInput\"")))
                .andExpect(content().string(containsString("id=\"descriptionInput\"")))
                // Toolbar actions (Quay lại / Lưu / Xóa lớp)
                .andExpect(content().string(containsString("detail-toolbar")))
                .andExpect(content().string(containsString("toolbar-back")))
                .andExpect(content().string(containsString("toolbar-save")))
                .andExpect(content().string(containsString("toolbar-delete")))
                .andExpect(content().string(containsString("Xóa lớp")))
                // Tab pill strip with info active
                .andExpect(content().string(containsString("detail-tabs")))
                .andExpect(content().string(containsString("Thông tin lớp")))
                .andExpect(content().string(containsString("Mời sinh viên")))
                // Invite panel is NOT rendered on the info tab. Match the
                // concrete <section class="detail-panel invite-panel">
                // rather than the bare token "invite-panel" so HTML comments
                // mentioning the word don't trigger a false positive.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("class=\"detail-panel invite-panel\""))));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void settings_tab_invite_renders_invite_panel() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "SettingsInvite");
        String code = activeCode(c.getId());

        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/settings")
                        .param("tab", "invite"))
                .andExpect(status().isOk())
                // Invite panel is rendered as <section class="detail-panel invite-panel">
                .andExpect(content().string(containsString("class=\"detail-panel invite-panel\"")))
                .andExpect(content().string(containsString(code)))
                // The info-tab form fields are NOT rendered on the invite tab
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("id=\"nameInput\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("id=\"descriptionInput\""))));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void settings_invalid_tab_falls_back_to_info() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "SettingsBadTab");

        mockMvc.perform(get("/lecturer/classes/" + c.getId() + "/settings")
                        .param("tab", "garbage"))
                .andExpect(status().isOk())
                // Whitelist falls back to info → form fields rendered
                .andExpect(content().string(containsString("id=\"nameInput\"")))
                .andExpect(content().string(containsString("id=\"descriptionInput\"")));
    }

    // ───────────────── Join by CODE ─────────────────

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void join_by_code_happy_path_creates_active_enrollment() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "JoinHappy");
        String code = activeCode(c.getId());

        mockMvc.perform(post("/my/classes/join").with(csrf()).param("code", code))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my/classes"));

        Optional<Enrollment> row = enrollmentRepository.findByUserIdAndClassId(student.getId(), c.getId());
        assertThat(row).isPresent();
        assertThat(row.get().getStatus()).isEqualTo("ACTIVE");
        assertThat(row.get().getJoinedVia()).isEqualTo("CODE");
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void join_by_code_lowercase_input_is_normalized() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "JoinLower");
        String code = activeCode(c.getId());

        mockMvc.perform(post("/my/classes/join").with(csrf()).param("code", code.toLowerCase()))
                .andExpect(status().is3xxRedirection());

        assertThat(enrollmentRepository.findByUserIdAndClassId(student.getId(), c.getId())).isPresent();
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void join_with_wrong_length_rerenders_with_field_error() throws Exception {
        mockMvc.perform(post("/my/classes/join").with(csrf()).param("code", "ABC"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mã phải có 6 ký tự")));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void join_with_unknown_code_rerenders_with_inline_error() throws Exception {
        mockMvc.perform(post("/my/classes/join").with(csrf()).param("code", "ZZZZZZ"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mã không hợp lệ")));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void join_with_disabled_token_rerenders_with_inline_error() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "DisabledJoin");
        ClassInviteCode token = inviteRepository
                .findByClassIdAndTypeAndActiveTrue(c.getId(), "CODE").orElseThrow();
        token.disable();
        inviteRepository.saveAndFlush(token);
        em.flush(); em.clear();

        mockMvc.perform(post("/my/classes/join").with(csrf()).param("code", token.getCode()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mã đã hết hiệu lực")));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void join_when_already_active_short_circuits_with_info_toast() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "DupJoin");
        String code = activeCode(c.getId());

        mockMvc.perform(post("/my/classes/join").with(csrf()).param("code", code))
                .andExpect(status().is3xxRedirection());

        long useCountAfterFirst = inviteRepository.findByCode(code).orElseThrow().getUseCount();

        mockMvc.perform(post("/my/classes/join").with(csrf()).param("code", code))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my/classes"));

        long useCountAfterSecond = inviteRepository.findByCode(code).orElseThrow().getUseCount();
        assertThat(useCountAfterSecond).isEqualTo(useCountAfterFirst);
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void re_join_after_removed_revives_existing_row() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "ReviveJoin");
        String code = activeCode(c.getId());

        mockMvc.perform(post("/my/classes/join").with(csrf()).param("code", code))
                .andExpect(status().is3xxRedirection());

        Enrollment row = enrollmentRepository
                .findByUserIdAndClassId(student.getId(), c.getId()).orElseThrow();
        Long rowId = row.getId();

        // Leave the class.
        mockMvc.perform(post("/my/classes/" + c.getId() + "/leave").with(csrf()))
                .andExpect(status().is3xxRedirection());

        em.flush(); em.clear();

        // Re-join.
        mockMvc.perform(post("/my/classes/join").with(csrf()).param("code", code))
                .andExpect(status().is3xxRedirection());

        em.flush(); em.clear();

        Enrollment revived = enrollmentRepository
                .findByUserIdAndClassId(student.getId(), c.getId()).orElseThrow();
        assertThat(revived.getId()).isEqualTo(rowId);
        assertThat(revived.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void joining_cancelled_class_rerenders_with_error() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "CancelClass");
        em.createNativeQuery("UPDATE classes SET status = 'CANCELLED' WHERE id = :id")
                .setParameter("id", c.getId()).executeUpdate();
        em.flush(); em.clear();
        String code = activeCode(c.getId());

        mockMvc.perform(post("/my/classes/join").with(csrf()).param("code", code))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lớp không nhận thành viên mới")));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void joining_full_class_rerenders_with_class_full_error() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "FullClass");
        em.createNativeQuery("UPDATE classes SET max_students = 1 WHERE id = :id")
                .setParameter("id", c.getId()).executeUpdate();
        // Fill the only slot with another seeded student.
        long otherUid = lookupUserId("sv01@ulp.edu.vn");
        em.createNativeQuery("INSERT INTO enrollments(user_id, class_id, status, joined_via) VALUES (:u, :c, 'ACTIVE', 'CODE')")
                .setParameter("u", otherUid).setParameter("c", c.getId()).executeUpdate();
        em.flush(); em.clear();
        String code = activeCode(c.getId());

        mockMvc.perform(post("/my/classes/join").with(csrf()).param("code", code))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lớp đã đầy")));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void over_max_uses_token_rerenders_with_error() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "OverMax");
        em.createNativeQuery("UPDATE class_invite_codes SET max_uses = 1, use_count = 1 WHERE class_id = :id AND type='CODE'")
                .setParameter("id", c.getId()).executeUpdate();
        em.flush(); em.clear();
        String code = activeCode(c.getId());

        mockMvc.perform(post("/my/classes/join").with(csrf()).param("code", code))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mã đã đạt giới hạn lượt dùng")));
    }

    // ───────────────── Leave ─────────────────

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void leave_happy_path_marks_removed() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "LeaveHappy");
        String code = activeCode(c.getId());
        mockMvc.perform(post("/my/classes/join").with(csrf()).param("code", code))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/my/classes/" + c.getId() + "/leave").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my/classes"));

        em.flush(); em.clear();
        Enrollment row = enrollmentRepository
                .findByUserIdAndClassId(student.getId(), c.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("REMOVED");
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void leave_when_not_enrolled_returns_404() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "LeaveNoRow");
        mockMvc.perform(post("/my/classes/" + c.getId() + "/leave").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ───────────────── Anonymous redirects ─────────────────

    @Test
    void anonymous_my_classes_redirects_to_login() throws Exception {
        mockMvc.perform(get("/my/classes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void anonymous_link_redirects_to_login_via_request_cache() throws Exception {
        mockMvc.perform(get("/j/" + "a".repeat(32)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    /**
     * BUG-1 regression: the full 3-hop deep-link flow.
     *
     * <p>An anonymous visitor following a class invite link must:
     * <ol>
     *   <li>be bounced to {@code /login} with the original URI captured by
     *       Spring Security's {@code HttpSessionRequestCache};</li>
     *   <li>upon successful form login, be redirected automatically back to
     *       {@code /j/{token}} (the saved request) instead of the home page;</li>
     *   <li>have the join completed and land on {@code /my/classes}
     *       with an {@code ACTIVE} enrollment marked {@code joined_via=LINK}.</li>
     * </ol>
     *
     * <p>This exercises the {@code defaultSuccessUrl("/", false)} setting in
     * {@code SecurityConfig#filterChain}; with {@code alwaysUse=true} hop 2
     * would silently drop the saved request and redirect to {@code "/"}.
     */
    @Test
    void anonymous_link_completes_join_after_login() throws Exception {
        // Setup: lecturer creates the class + LINK token.
        ClassEntity c = createClassViaController(lecturer.getId(), "DeepLink-AfterLogin");
        String linkToken = activeLink(c.getId());

        MockHttpSession session = new MockHttpSession();

        // Hop 1: anonymous GET /j/{token} → 302 to /login, saved request stored
        // in the same session.
        mockMvc.perform(get("/j/" + linkToken).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        // Hop 2: POST /login with the same session → Spring resumes the saved
        // request and redirects back to /j/{token}. The login form's username
        // field is named "username" (Spring Security default; see login.html).
        // Spring Security 6 appends a "?continue" marker to the saved URL so
        // RequestCacheAwareFilter can recognize the replay on the next hop;
        // the pattern below accepts either with or without that marker.
        mockMvc.perform(post("/login")
                        .session(session)
                        .with(csrf())
                        .param("username", "student@ulp.edu.vn")
                        .param("password", "password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/j/" + linkToken + "*"));

        // Hop 3: GET /j/{token}?continue with the now-authenticated session →
        // RequestCacheAwareFilter consumes the saved request, the controller
        // resolves the token, the join completes, and the user is sent to
        // /my/classes.
        mockMvc.perform(get("/j/" + linkToken).param("continue", "")
                        .session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my/classes"));

        em.flush();
        em.clear();

        Enrollment enrollment = enrollmentRepository
                .findByUserIdAndClassId(student.getId(), c.getId())
                .orElseThrow();
        assertThat(enrollment.getStatus()).isEqualTo("ACTIVE");
        assertThat(enrollment.getJoinedVia()).isEqualTo("LINK");
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void malformed_link_token_returns_404() throws Exception {
        mockMvc.perform(get("/j/foo"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void link_join_records_joined_via_link() throws Exception {
        ClassEntity c = createClassViaController(lecturer.getId(), "LinkJoin");
        String linkToken = activeLink(c.getId());

        mockMvc.perform(get("/j/" + linkToken))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my/classes"));

        Enrollment row = enrollmentRepository
                .findByUserIdAndClassId(student.getId(), c.getId()).orElseThrow();
        assertThat(row.getJoinedVia()).isEqualTo("LINK");
    }

    // ───────────────── Lecturer can use student routes ─────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_can_access_join_form() throws Exception {
        mockMvc.perform(get("/my/classes/join"))
                .andExpect(status().isOk());
    }

    // ───────────────── helpers ───────────────────

    private ClassEntity createClassViaController(Long lecturerId, String name) {
        ClassForm form = new ClassForm(name, null, null, null, 100);
        ClassEntity saved = classesService.create(form, lecturerId);
        em.flush();
        em.clear();
        return classRepository.findById(saved.getId()).orElseThrow();
    }

    private String activeCode(Long classId) {
        return inviteRepository.findByClassIdAndTypeAndActiveTrue(classId, "CODE")
                .orElseThrow().getCode();
    }

    private String activeLink(Long classId) {
        return inviteRepository.findByClassIdAndTypeAndActiveTrue(classId, "LINK")
                .orElseThrow().getCode();
    }

    private long lookupUserId(String email) {
        Number id = (Number) em.createNativeQuery("SELECT id FROM users WHERE email = :e")
                .setParameter("e", email).getSingleResult();
        return id.longValue();
    }
}
