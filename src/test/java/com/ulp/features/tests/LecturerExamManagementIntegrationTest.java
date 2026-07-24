package com.ulp.features.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamForm;
import com.ulp.features.tests.dto.LecturerTestDtos.OptionForm;
import com.ulp.features.tests.dto.LecturerTestDtos.QuestionForm;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.TestAttempt;
import com.ulp.features.tests.entity.TestResponse;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.repository.TestAttemptRepository;
import com.ulp.features.tests.repository.TestRepository;
import com.ulp.features.tests.repository.TestResponseRepository;
import com.ulp.features.tests.service.LecturerExamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for lecturer exam management: owner list/edit, JSON save
 * (create + validation rejects), class-ownership 403 on monitor data, and the
 * submissions overview with search.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LecturerExamManagementIntegrationTest {

    private static final String LECTURER = "lecturer@ulp.edu.vn";
    private static final String OTHER_LECTURER = "head@ulp.edu.vn"; // LECTURER+ but non-owner

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private LecturerExamService lecturerExamService;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private TestRepository testRepository;
    @Autowired private TestAttemptRepository attemptRepository;
    @Autowired private TestResponseRepository responseRepository;

    private Long lecturerId;
    private Long classId;
    private Long examId;

    @BeforeEach
    void setUp() {
        User lecturer = userRepository.findByEmailIgnoreCase(LECTURER).orElseThrow();
        lecturerId = lecturer.getId();
        ClassEntity clazz = saveClass(lecturer);
        classId = clazz.getId();
        examId = lecturerExamService.save(lecturerId, validForm(null, "Đề GV JUnit"));
    }

    @Test
    @WithUserDetails(LECTURER)
    void owner_sees_exam_in_list_and_edit_form_loads() throws Exception {
        mockMvc.perform(get("/lecturer/tests"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đề GV JUnit")));
        mockMvc.perform(get("/lecturer/tests/" + examId + "/edit"))
                .andExpect(status().isOk());
    }

    @Test
    @WithUserDetails(LECTURER)
    void owner_preview_shows_student_style_read_only_view() throws Exception {
        mockMvc.perform(get("/lecturer/tests/" + examId + "/preview"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chế độ xem trước")))
                .andExpect(content().string(containsString("Đề GV JUnit")));
    }

    @Test
    @WithUserDetails(OTHER_LECTURER)
    void non_owner_preview_is_forbidden() throws Exception {
        mockMvc.perform(get("/lecturer/tests/" + examId + "/preview"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails(LECTURER)
    void save_creates_exam_for_owner() throws Exception {
        mockMvc.perform(post("/lecturer/tests/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validForm(null, "Đề mới"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.id").isNumber());
    }

    @Test
    @WithUserDetails(LECTURER)
    void save_rejects_mcq_with_two_correct_options() throws Exception {
        QuestionForm badMcq = new QuestionForm(null, "MCQ", "Chọn 1?", null,
                new BigDecimal("1.00"), List.of(
                new OptionForm(null, "A", true),
                new OptionForm(null, "B", true))); // two correct → invalid for MCQ
        ExamForm form = formWith(badMcq);
        mockMvc.perform(post("/lecturer/tests/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(form)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(LECTURER)
    void save_rejects_question_with_single_option() throws Exception {
        QuestionForm oneOption = new QuestionForm(null, "MCQ", "Thiếu lựa chọn?", null,
                new BigDecimal("1.00"), List.of(new OptionForm(null, "A", true)));
        ExamForm form = formWith(oneOption);
        mockMvc.perform(post("/lecturer/tests/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(form)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(LECTURER)
    void owner_monitor_data_returns_json() throws Exception {
        mockMvc.perform(get("/lecturer/tests/" + examId + "/monitor/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examStatus").value("PUBLISHED"));
    }

    @Test
    @WithUserDetails(OTHER_LECTURER)
    void non_owner_monitor_data_is_forbidden() throws Exception {
        mockMvc.perform(get("/lecturer/tests/" + examId + "/monitor/data"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails(LECTURER)
    void legacy_submissions_url_redirects_to_detail_tab() throws Exception {
        // The standalone /submissions screen is now the "submissions" tab on the
        // exam detail page; the old URL 302-redirects (preserving the q search term).
        mockMvc.perform(get("/lecturer/tests/" + examId + "/submissions").param("q", "khong-ton-tai"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/tests/" + examId
                        + "/edit?tab=submissions&q=khong-ton-tai"));
    }

    @Test
    @WithUserDetails(LECTURER)
    void legacy_monitor_url_redirects_to_detail_tab() throws Exception {
        mockMvc.perform(get("/lecturer/tests/" + examId + "/monitor"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/tests/" + examId + "/edit?tab=monitor"));
    }

    @Test
    @WithUserDetails(LECTURER)
    void detail_tabs_render_directly() throws Exception {
        for (String tab : new String[]{"info", "monitor", "submissions", "history"}) {
            mockMvc.perform(get("/lecturer/tests/" + examId + "/edit").param("tab", tab))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @WithUserDetails(LECTURER)
    void history_tab_shows_created_activity() throws Exception {
        // save() in setUp() appends a CREATED (+PUBLISHED) audit row for this exam.
        mockMvc.perform(get("/lecturer/tests/" + examId + "/edit").param("tab", "history"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đề GV JUnit")));
    }

    @Test
    @WithUserDetails(LECTURER)
    void save_with_media_keeps_questions_when_responses_exist() throws Exception {
        List<Question> before = questionRepository.findByTestIdOrderBySortOrderAscIdAsc(examId);
        Question q = before.get(0);
        TestAttempt attempt = attemptRepository.save(new TestAttempt(examId, lecturerId));
        responseRepository.save(new TestResponse(attempt.getId(), q.getId(), "[1]"));

        String mediaUrl = "https://youtu.be/8Pu0AM6BvEw?si=h0Wkdo9QivQNTJEb";
        ExamForm form = validForm(examId, "Đề GV JUnit", "YOUTUBE", mediaUrl);
        mockMvc.perform(post("/lecturer/tests/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(form)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        com.ulp.features.tests.entity.Test saved = testRepository.findById(examId).orElseThrow();
        assertEquals("YOUTUBE", saved.getMediaType());
        assertEquals(mediaUrl, saved.getMediaUrl());
        List<Question> after = questionRepository.findByTestIdOrderBySortOrderAscIdAsc(examId);
        assertEquals(before.size(), after.size());
        assertEquals(before.get(0).getId(), after.get(0).getId());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private ExamForm validForm(Long id, String title) {
        return validForm(id, title, null, null);
    }

    private ExamForm validForm(Long id, String title, String mediaType, String mediaUrl) {
        List<OptionForm> mcq = List.of(
                new OptionForm(null, "Đúng", true),
                new OptionForm(null, "Sai", false));
        List<OptionForm> mr = List.of(
                new OptionForm(null, "A", true),
                new OptionForm(null, "B", true),
                new OptionForm(null, "C", false));
        List<QuestionForm> questions = List.of(
                new QuestionForm(null, "MCQ", "1+1=2?", null, new BigDecimal("2.00"), mcq),
                new QuestionForm(null, "MR", "Chọn A và B", null, new BigDecimal("3.00"), mr));
        return new ExamForm(id, title, "mô tả", classId, "MOCK", "PUBLISHED",
                "FIXED_WINDOW", null, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1), new BigDecimal("1.00"), false, false,
                mediaType, mediaUrl, questions, false);
    }

    /** A valid form but with a single (possibly invalid) question, for reject tests. */
    private ExamForm formWith(QuestionForm question) {
        return new ExamForm(null, "Đề lỗi", "mô tả", classId, "MOCK", "PUBLISHED",
                "FIXED_WINDOW", null, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1), new BigDecimal("1.00"), false, false,
                null, null, List.of(question), false);
    }

    private ClassEntity saveClass(User lecturer) {
        ClassEntity entity = new ClassEntity("Lecturer exam class", lecturer.getId(),
                lecturer.getId(), null, null, null, 100);
        entity.setCode("LECEXM");
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode("LECEX" + (int) (Math.random() * 9));
            return classRepository.saveAndFlush(entity);
        }
    }
}
