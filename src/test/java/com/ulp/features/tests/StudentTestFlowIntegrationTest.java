package com.ulp.features.tests;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamForm;
import com.ulp.features.tests.dto.LecturerTestDtos.OptionForm;
import com.ulp.features.tests.dto.LecturerTestDtos.QuestionForm;
import com.ulp.features.tests.dto.TestDtos.SubmitRequest;
import com.ulp.features.tests.dto.TestDtos.TakeView;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.QuestionOption;
import com.ulp.features.tests.repository.QuestionOptionRepository;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.service.LecturerExamService;
import com.ulp.features.tests.service.TestAttemptService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MockMvc integration tests for the student exam flow: list authz (404 no-leak),
 * start→submit→result→review, resume single-attempt, and late-submit → TIMED_OUT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentTestFlowIntegrationTest {

    private static final String STUDENT = "student@ulp.edu.vn";
    private static final String OUTSIDER = "sv01@ulp.edu.vn";
    private static final String OTHER = "sv02@ulp.edu.vn";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private LecturerExamService lecturerExamService;
    @Autowired private TestAttemptService attemptService;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuestionOptionRepository optionRepository;

    private Long lecturerId;
    private Long studentId;
    private Long examId;
    private Long lateExamId;

    @BeforeEach
    void setUp() {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        User student = userRepository.findByEmailIgnoreCase(STUDENT).orElseThrow();
        lecturerId = lecturer.getId();
        studentId = student.getId();
        ClassEntity clazz = saveClass(lecturer);
        enroll(student, clazz);

        examId = lecturerExamService.save(lecturerId, examForm(clazz.getId(),
                LocalDateTime.now().plusDays(1)));
        lateExamId = lecturerExamService.save(lecturerId, examForm(clazz.getId(),
                LocalDateTime.now().minusHours(1)));
    }

    @Test
    @WithUserDetails(STUDENT)
    void list_shows_enrolled_published_exam() throws Exception {
        mockMvc.perform(get("/my/tests"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đề kiểm tra JUnit")));
    }

    @Test
    @WithUserDetails(OUTSIDER)
    void outsider_take_returns_404() throws Exception {
        mockMvc.perform(get("/my/tests/" + examId + "/take"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails(STUDENT)
    void start_resume_submit_result_review_happy_path() throws Exception {
        Long attemptId = openAttempt(examId);
        // Resuming reuses the same open attempt.
        assertEquals(attemptId, openAttempt(examId));

        mockMvc.perform(post("/api/tests/attempts/" + attemptId + "/submit").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(allCorrectPayload(examId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));

        mockMvc.perform(get("/my/tests/" + examId + "/result/" + attemptId))
                .andExpect(status().isOk());
        mockMvc.perform(get("/my/tests/" + examId + "/review/" + attemptId))
                .andExpect(status().isOk());
    }

    @Test
    @WithUserDetails(STUDENT)
    void late_submit_is_timed_out_but_graded() throws Exception {
        Long attemptId = openAttempt(lateExamId);
        mockMvc.perform(post("/api/tests/attempts/" + attemptId + "/submit").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(allCorrectPayload(lateExamId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.status").value("TIMED_OUT"));
    }

    @Test
    @WithUserDetails(OTHER)
    void review_of_another_students_attempt_returns_404() throws Exception {
        // Create + submit an attempt owned by STUDENT (service uses explicit id).
        Long attemptId = attemptService.startOrResume(examId, studentId).attemptId();
        attemptService.submit(attemptId, studentId, new SubmitRequest(List.of()));

        mockMvc.perform(get("/my/tests/" + examId + "/review/" + attemptId))
                .andExpect(status().isNotFound());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Long openAttempt(Long id) throws Exception {
        TakeView take = (TakeView) mockMvc.perform(get("/my/tests/" + id + "/take"))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("take");
        return take.attemptId();
    }

    /** Builds a submit payload selecting the correct option(s) for every question. */
    private String allCorrectPayload(Long id) {
        List<Question> questions = questionRepository.findByTestIdOrderBySortOrderAscIdAsc(id);
        StringJoiner answers = new StringJoiner(",", "{\"answers\":[", "]}");
        for (Question q : questions) {
            List<Long> correct = new ArrayList<>();
            for (QuestionOption o : optionRepository.findByQuestionIdOrderBySortOrderAscIdAsc(q.getId())) {
                if (o.isCorrect()) correct.add(o.getId());
            }
            StringJoiner ids = new StringJoiner(",", "[", "]");
            correct.forEach(cid -> ids.add(String.valueOf(cid)));
            answers.add("{\"questionId\":" + q.getId() + ",\"selectedOptionIds\":" + ids + "}");
        }
        return answers.toString();
    }

    private ExamForm examForm(Long classId, LocalDateTime endAt) {
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
        return new ExamForm(null, "Đề kiểm tra JUnit", "mô tả", classId, "MOCK", "PUBLISHED",
                "FIXED_WINDOW", null, LocalDateTime.now().minusHours(1), endAt,
                new BigDecimal("1.00"), false, false, null, null, questions, false);
    }

    private void enroll(User u, ClassEntity clazz) {
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                u, clazz.getId(), Enrollment.JoinedVia.CODE, null));
    }

    private ClassEntity saveClass(User lecturer) {
        ClassEntity entity = new ClassEntity("Test flow class", lecturer.getId(),
                lecturer.getId(), null, null, null, 100);
        entity.setCode("TSTFLW");
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode("TSTFL" + (int) (Math.random() * 9));
            return classRepository.saveAndFlush(entity);
        }
    }
}
