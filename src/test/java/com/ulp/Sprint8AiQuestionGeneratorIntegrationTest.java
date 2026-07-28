package com.ulp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.entities.AiProvider;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.User;
import com.ulp.features.admin.settings.repository.AiProviderRepository;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.ConfirmRequest;
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
import com.ulp.features.tests.repository.TestResponseRepository;
import com.ulp.features.tests.service.LecturerExamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 8 integration test for the lecturer AI question generator (ULP-12.2).
 *
 * <p>Focuses on the guards that must hold before any provider is contacted: who may call
 * the endpoints, which exams accept generated questions, and what a caller can do with a
 * session id they did not obtain. The provider call itself and the reply parsing are
 * covered by {@code AiQuestionResponseParserTest}; asserting them again through MockMvc
 * would test a stub rather than the policy.
 *
 * <p>{@code ai_providers} is emptied in {@code @BeforeEach} so "no provider configured" is
 * a known starting state rather than a property of the developer's database. The delete
 * runs inside the rolled-back test transaction, so real rows come back afterwards.
 *
 * <p>Seed users from {@code V5__seed_test_users.sql}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Sprint8AiQuestionGeneratorIntegrationTest {

    private static final String LECTURER = "lecturer@ulp.edu.vn";
    /** LECTURER+ by role but not the owner of the fixture exam. */
    private static final String OTHER_LECTURER = "head@ulp.edu.vn";
    private static final String STUDENT = "student@ulp.edu.vn";

    /** Reserved TEST-NET-1 address (RFC 5737): never routes, so the call fails on connect. */
    private static final String UNREACHABLE_URL = "http://192.0.2.1:9";

    /** Provider name the one call-reaching test logs under; drives the @AfterEach cleanup. */
    private static final String LOG_FIXTURE_PROVIDER = "QuestionGenUnreachable";

    private static final String MATERIAL =
            "Cấu trúc dữ liệu hàng đợi (queue) hoạt động theo nguyên tắc FIFO.";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private LecturerExamService lecturerExamService;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private TestAttemptRepository attemptRepository;
    @Autowired private TestResponseRepository responseRepository;
    @Autowired private AiProviderRepository providerRepository;
    @Autowired private DataSource dataSource;

    private Long lecturerId;
    private Long classId;
    private Long examId;

    @BeforeEach
    void setUp() {
        providerRepository.deleteAll();
        providerRepository.flush();

        User lecturer = userRepository.findByEmailIgnoreCase(LECTURER).orElseThrow();
        lecturerId = lecturer.getId();
        classId = saveClass(lecturer).getId();
        examId = lecturerExamService.save(lecturerId, validForm());
    }

    /**
     * Removes log rows this class committed.
     *
     * <p>{@code AiRequestLogger} writes with {@code REQUIRES_NEW}, so its rows commit on
     * their own connection and outlive the rollback of the test transaction. Cleanup has to
     * happen on the same terms — its own connection, committed — and is scoped to the
     * fixture provider name so a real operator's history survives a local run.
     */
    @AfterEach
    void clearCommittedLogs() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM ai_request_logs WHERE provider_name = ?")) {
            statement.setString(1, LOG_FIXTURE_PROVIDER);
            statement.executeUpdate();
        }
    }

    // ───────── Access control ────────────────────────────────────────

    @Test
    @WithUserDetails(STUDENT)
    void student_cannot_generate_questions() throws Exception {
        mockMvc.perform(generateRequest(examId).param("text", MATERIAL))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails(STUDENT)
    void student_cannot_confirm_a_selection() throws Exception {
        mockMvc.perform(confirmRequest(examId, new ConfirmRequest(randomSessionId(), List.of(0))))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails(OTHER_LECTURER)
    void non_owner_lecturer_cannot_generate_for_someone_elses_exam() throws Exception {
        // A provider exists, so a missing-provider 400 cannot mask the ownership check.
        persistProvider();

        mockMvc.perform(generateRequest(examId).param("text", MATERIAL))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails(OTHER_LECTURER)
    void non_owner_lecturer_cannot_confirm_into_someone_elses_exam() throws Exception {
        mockMvc.perform(confirmRequest(examId, new ConfirmRequest(randomSessionId(), List.of(0))))
                .andExpect(status().isForbidden());
    }

    // ───────── Input guards ──────────────────────────────────────────

    /**
     * The generate endpoint is CSRF-protected, so the browser module must send the token.
     *
     * <p>Every other test here injects one via {@code .with(csrf())}, which would hide a
     * front-end that forgot the header. This one omits it on purpose: if the guard is ever
     * dropped from the endpoint or the token from the JS, this test fails instead of the
     * lecturer.
     */
    @Test
    @WithUserDetails(LECTURER)
    void generate_without_a_csrf_token_is_forbidden() throws Exception {
        mockMvc.perform(multipart("/lecturer/tests/" + examId + "/ai-questions/generate")
                        .param("count", "3")
                        .param("type", "MCQ")
                        .param("difficulty", "medium")
                        .param("text", MATERIAL))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails(LECTURER)
    void generate_without_a_file_or_text_is_rejected() throws Exception {
        mockMvc.perform(generateRequest(examId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(LECTURER)
    void generate_rejects_a_file_that_is_neither_pdf_nor_docx() throws Exception {
        // Named .pdf on purpose: the format check reads magic bytes, not the file name.
        MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf",
                "day khong phai la PDF".getBytes(StandardCharsets.UTF_8));
        persistProvider();

        mockMvc.perform(generateRequest(examId).file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(LECTURER)
    void generate_without_any_configured_provider_reports_a_vietnamese_error() throws Exception {
        mockMvc.perform(generateRequest(examId).param("text", MATERIAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Chưa cấu hình AI provider")));
    }

    /**
     * An exam students have already answered must refuse generated questions.
     *
     * <p>The guard is asserted before the provider call, not after: appending to a graded
     * exam is refused outright, and refusing it early also avoids spending tokens on a
     * result that can never be inserted.
     */
    @Test
    @WithUserDetails(LECTURER)
    void generate_is_blocked_once_students_have_answered() throws Exception {
        persistProvider();
        givenAStudentResponse();

        mockMvc.perform(generateRequest(examId).param("text", MATERIAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    // ───────── Session guards ────────────────────────────────────────

    @Test
    @WithUserDetails(LECTURER)
    void confirm_with_an_unknown_session_is_rejected() throws Exception {
        mockMvc.perform(confirmRequest(examId, new ConfirmRequest(randomSessionId(), List.of(0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(LECTURER)
    void confirm_without_a_body_is_rejected() throws Exception {
        mockMvc.perform(post("/lecturer/tests/" + examId + "/ai-questions/confirm").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(LECTURER)
    void confirm_leaves_the_exam_untouched_when_the_session_is_unknown() throws Exception {
        int before = questionRepository.findByTestIdOrderBySortOrderAscIdAsc(examId).size();

        mockMvc.perform(confirmRequest(examId, new ConfirmRequest(randomSessionId(), List.of(0, 1))))
                .andExpect(status().isBadRequest());

        org.junit.jupiter.api.Assertions.assertEquals(before,
                questionRepository.findByTestIdOrderBySortOrderAscIdAsc(examId).size(),
                "a rejected confirm must not append any question");
    }

    /**
     * A session id that is not a UUID at all must fail like any other stale session.
     *
     * <p>Binding {@code sessionId} as a UUID used to make Jackson reject the body before the
     * handler ran, so a stale browser got a 500 and an HTML error page — which the panel's
     * JSON client could not read, leaving the lecturer with a generic "try again" toast.
     * The assertion is on the envelope, not just the status: it is the JSON content type
     * that the front end depends on.
     */
    @Test
    @WithUserDetails(LECTURER)
    void confirm_with_a_malformed_session_id_is_rejected_as_json() throws Exception {
        int before = questionRepository.findByTestIdOrderBySortOrderAscIdAsc(examId).size();

        mockMvc.perform(confirmRequest(examId, new ConfirmRequest("khong-ton-tai-123", List.of(0, 1))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Phiên sinh câu hỏi đã hết hạn")));

        org.junit.jupiter.api.Assertions.assertEquals(before,
                questionRepository.findByTestIdOrderBySortOrderAscIdAsc(examId).size(),
                "a confirm with a malformed session id must not append any question");
    }

    /**
     * A configured but unreachable provider surfaces as a retryable 400, not a 500.
     *
     * <p>This is the only test here that lets the call reach the client, which is why the
     * committed log row it writes has to be cleaned up in {@code @AfterEach}.
     */
    @Test
    @WithUserDetails(LECTURER)
    void generate_reports_a_provider_outage_as_a_bad_request() throws Exception {
        persistProvider();

        mockMvc.perform(generateRequest(examId).param("text", MATERIAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
    generateRequest(Long testId) {
        var builder = multipart("/lecturer/tests/" + testId + "/ai-questions/generate");
        builder.with(csrf());
        builder.param("count", "3");
        builder.param("type", "MCQ");
        builder.param("difficulty", "medium");
        return builder;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
    confirmRequest(Long testId, ConfirmRequest payload) throws Exception {
        return post("/lecturer/tests/" + testId + "/ai-questions/confirm").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload));
    }

    /** A well-formed session id that was never issued, so the store cannot know it. */
    private static String randomSessionId() {
        return UUID.randomUUID().toString();
    }

    /** Registers one enabled provider pointing at an address that never routes. */
    private void persistProvider() {
        AiProvider provider = new AiProvider(LOG_FIXTURE_PROVIDER, UNREACHABLE_URL,
                "test-model", "sk-question-gen-test");
        provider.setEnabled(true);
        provider.setDisplayOrder((short) 1);
        providerRepository.saveAndFlush(provider);
    }

    /** Records one student answer so the exam counts as graded. */
    private void givenAStudentResponse() {
        Question question = questionRepository
                .findByTestIdOrderBySortOrderAscIdAsc(examId).get(0);
        TestAttempt attempt = attemptRepository.save(new TestAttempt(examId, lecturerId));
        responseRepository.save(new TestResponse(attempt.getId(), question.getId(), "[1]"));
    }

    private ExamForm validForm() {
        List<QuestionForm> questions = List.of(
                new QuestionForm(null, "MCQ", "1+1=2?", null, new BigDecimal("2.00"), List.of(
                        new OptionForm(null, "Đúng", true),
                        new OptionForm(null, "Sai", false))));
        return new ExamForm(null, "Đề AI JUnit", "mô tả", classId, "MOCK", "PUBLISHED",
                "FIXED_WINDOW", null, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1), new BigDecimal("1.00"), false, false,
                null, null, questions, false);
    }

    private ClassEntity saveClass(User lecturer) {
        ClassEntity entity = new ClassEntity("AI question gen class", lecturer.getId(),
                lecturer.getId(), null, null, null, 100);
        entity.setCode("AIQGEN");
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode("AIQG" + (int) (Math.random() * 99));
            return classRepository.saveAndFlush(entity);
        }
    }
}
