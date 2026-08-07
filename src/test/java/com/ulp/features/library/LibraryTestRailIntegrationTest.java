package com.ulp.features.library;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.LibraryAsset;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.library.repository.LibraryAssetRepository;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamFilter;
import com.ulp.features.tests.dto.LecturerTestDtos.LecturerExamRow;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.QuestionOption;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.entity.TestAttempt;
import com.ulp.features.tests.repository.QuestionOptionRepository;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.repository.TestAttemptRepository;
import com.ulp.features.tests.repository.TestRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.ulp.common.IConstant.ATTR_EXAMS_PAGE;
import static com.ulp.common.IConstant.ATTR_EXAM_FILTER;
import static com.ulp.common.IConstant.ATTR_LIBRARY_DOCUMENT_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_EXAM_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_TAB;
import static com.ulp.common.IConstant.ATTR_LIBRARY_TEMPLATE_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_TOTAL_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_VIDEO_COUNT;
import static com.ulp.common.IConstant.ATTR_PREVIEW_BACK_URL;
import static com.ulp.common.IConstant.LIBRARY_TAB_TESTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for the Library "Bài test" rail at
 * {@code /lecturer/library/tests}: the listing plus its five sidebar badges,
 * the two-layer authorization outcomes, exam clone, and exam soft delete.
 *
 * <p>Every write assertion counts DB rows rather than trusting the HTTP status:
 * a blocked POST can still return 2xx while writing nothing, so only the row
 * count distinguishes "blocked" from "succeeded".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LibraryTestRailIntegrationTest {

    private static final String LECTURER = "lecturer@ulp.edu.vn";
    private static final String STUDENT = "student@ulp.edu.vn";
    private static final String ADMIN = "admin@ulp.edu.vn";
    private static final String HEAD = "head@ulp.edu.vn";

    private static final String RAIL_URL = "/lecturer/library/tests";

    /** Marks everything this test seeds, isolating it from migration demo data. */
    private static final String TAG = "RAILQZ";

    /** Questions on the exam used for the deep-copy assertions. */
    private static final int QUESTIONS = 20;

    /** Options per question; total options = QUESTIONS * OPTIONS_PER_QUESTION. */
    private static final int OPTIONS_PER_QUESTION = 4;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private LibraryAssetRepository assetRepository;
    @Autowired private TestRepository testRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuestionOptionRepository optionRepository;
    @Autowired private TestAttemptRepository attemptRepository;
    @Autowired private EntityManager entityManager;

    private Long lecturerId;
    private Long sourceClass;
    private Long targetClass;
    private Long richExamId;
    private Long classlessExamId;

    @BeforeEach
    void setUp() {
        User lecturer = userRepository.findByEmailIgnoreCase(LECTURER).orElseThrow();
        lecturerId = lecturer.getId();
        sourceClass = createClass(lecturerId, TAG + " nguồn");
        targetClass = createClass(lecturerId, TAG + " đích");
        seedLibraryAsset(LibraryAsset.KIND_DOCUMENT, TAG + "-doc.pdf", "application/pdf");
        seedLibraryAsset(LibraryAsset.KIND_VIDEO, TAG + "-clip.mp4", "video/mp4");
        seedLesson(sourceClass);
        richExamId = seedExamWithQuestions(sourceClass, TAG + " Java cơ bản");
        classlessExamId = seedBareExam(null, TAG + " không lớp");
    }

    // ── Rail listing + badges ───────────────────────────────────────────

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void lecturer_opens_the_rail_and_every_badge_count_is_populated() throws Exception {
        Map<String, Object> model = modelAt(RAIL_URL);

        assertThat(model.get(ATTR_LIBRARY_TAB)).isEqualTo(LIBRARY_TAB_TESTS);
        // All five badges belong to the serving controller. Omitting any one
        // renders that badge as 0, misreporting the lecturer's library.
        assertThat((Long) model.get(ATTR_LIBRARY_TOTAL_COUNT)).isPositive();
        assertThat((Long) model.get(ATTR_LIBRARY_DOCUMENT_COUNT)).isPositive();
        assertThat((Long) model.get(ATTR_LIBRARY_VIDEO_COUNT)).isPositive();
        assertThat((Long) model.get(ATTR_LIBRARY_TEMPLATE_COUNT)).isPositive();
        assertThat((Long) model.get(ATTR_LIBRARY_EXAM_COUNT)).isPositive();
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(ADMIN)
    void admin_gets_an_empty_state_at_200_rather_than_a_500() throws Exception {
        // The acceptance criterion is explicitly "200, not 500": an ADMIN leads
        // no class and created no exam, so the query's own ownership predicate
        // yields an empty page instead of throwing.
        Map<String, Object> model = mockMvc.perform(get(RAIL_URL))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel();

        assertThat(examPageOf(model).getTotalElements()).isZero();
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void the_four_older_rails_report_the_real_exam_count_too() throws Exception {
        // Every rail renders the same shell, so each serving controller owns all
        // five badges. A rail that leaves libraryExamCount unset renders 0 for a
        // lecturer who owns exams — the same data-integrity lie in reverse.
        for (String url : List.of("/lecturer/library",
                "/lecturer/library?kind=DOCUMENT",
                "/lecturer/library?kind=VIDEO",
                "/lecturer/library/templates")) {
            assertThat((Long) modelAt(url).get(ATTR_LIBRARY_EXAM_COUNT))
                    .as("libraryExamCount on %s", url)
                    .isPositive();
        }
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void filtering_by_class_narrows_the_rail_to_that_class() throws Exception {
        // A second exam in the other class the lecturer leads, so the filter has
        // something to exclude rather than trivially matching everything.
        Long otherExam = seedBareExam(targetClass, TAG + " ở lớp đích");

        Map<String, Object> model = modelAt(RAIL_URL + "?classId=" + targetClass);

        List<Long> listed = examPageOf(model).getContent().stream()
                .map(LecturerExamRow::id).toList();
        assertThat(listed).contains(otherExam)
                .doesNotContain(richExamId, classlessExamId);
        // The dropdown must echo the active class back, or paging/reload would
        // silently drop the filter the lecturer set.
        assertThat(((ExamFilter) model.get(ATTR_EXAM_FILTER)).classIdOrNull())
                .isEqualTo(targetClass);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(STUDENT)
    void student_typing_the_rail_url_is_rejected() throws Exception {
        mockMvc.perform(get(RAIL_URL)).andExpect(status().isForbidden());
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(STUDENT)
    void student_cannot_reach_the_clone_endpoint_and_writes_no_row() throws Exception {
        long testsBefore = physicalTestRowCount();
        long questionsBefore = questionRepository.count();
        long optionsBefore = optionRepository.count();

        mockMvc.perform(post(RAIL_URL + "/" + richExamId + "/clone").with(csrf())
                        .param("targetClassId", String.valueOf(targetClass)))
                .andExpect(status().isForbidden());

        // The role gate is layer 1, but only the row counts prove it fired
        // before any write — a blocked POST can still return 2xx.
        assertThat(physicalTestRowCount()).isEqualTo(testsBefore);
        assertThat(questionRepository.count()).isEqualTo(questionsBefore);
        assertThat(optionRepository.count()).isEqualTo(optionsBefore);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(STUDENT)
    void student_cannot_reach_the_delete_endpoint_and_the_exam_stays_undeleted()
            throws Exception {
        mockMvc.perform(post(RAIL_URL + "/" + richExamId + "/delete").with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(isDeletedFlag(richExamId)).isFalse();
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void an_exam_with_no_class_still_appears_in_the_rail_with_no_class_name() throws Exception {
        LecturerExamRow row = rowFor(classlessExamId);

        // The template renders "—" precisely when className() is null.
        assertThat(row.className()).isNull();
    }

    // ── Clone ───────────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void cloning_a_twenty_question_exam_copies_every_question_and_option() throws Exception {
        mockMvc.perform(post(RAIL_URL + "/" + richExamId + "/clone").with(csrf())
                        .param("targetClassId", String.valueOf(targetClass)))
                .andExpect(status().isOk());

        Test copy = onlyExamIn(targetClass);
        assertThat(copy.getId()).isNotEqualTo(richExamId);

        List<Question> copied =
                questionRepository.findByTestIdOrderBySortOrderAscIdAsc(copy.getId());
        assertThat(copied).hasSize(QUESTIONS);

        List<Question> sources =
                questionRepository.findByTestIdOrderBySortOrderAscIdAsc(richExamId);
        for (int i = 0; i < QUESTIONS; i++) {
            Question src = sources.get(i);
            Question dst = copied.get(i);
            assertThat(dst.getSortOrder()).isEqualTo(src.getSortOrder());
            assertThat(dst.getContent()).isEqualTo(src.getContent());
            assertThat(dst.getQuestionType()).isEqualTo(src.getQuestionType());
            assertThat(dst.getExplanation()).isEqualTo(src.getExplanation());
            assertThat(dst.getPoints()).isEqualByComparingTo(src.getPoints());

            List<QuestionOption> srcOpts =
                    optionRepository.findByQuestionIdOrderBySortOrderAscIdAsc(src.getId());
            List<QuestionOption> dstOpts =
                    optionRepository.findByQuestionIdOrderBySortOrderAscIdAsc(dst.getId());
            assertThat(dstOpts).hasSize(OPTIONS_PER_QUESTION);
            for (int j = 0; j < OPTIONS_PER_QUESTION; j++) {
                assertThat(dstOpts.get(j).isCorrect()).isEqualTo(srcOpts.get(j).isCorrect());
                assertThat(dstOpts.get(j).getSortOrder()).isEqualTo(srcOpts.get(j).getSortOrder());
                assertThat(dstOpts.get(j).getContent()).isEqualTo(srcOpts.get(j).getContent());
            }
        }
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void the_clone_lands_as_an_unscheduled_draft_carrying_the_exam_settings() throws Exception {
        mockMvc.perform(post(RAIL_URL + "/" + richExamId + "/clone").with(csrf())
                        .param("targetClassId", String.valueOf(targetClass)))
                .andExpect(status().isOk());

        Test source = testRepository.findById(richExamId).orElseThrow();
        Test copy = onlyExamIn(targetClass);

        // Reset: a copy inheriting PUBLISHED + the source window would be live
        // in the target class the instant it was created.
        assertThat(copy.getStatus()).isEqualTo(Test.STATUS_DRAFT);
        assertThat(copy.getStartAt()).isNull();
        assertThat(copy.getEndAt()).isNull();
        assertThat(copy.getClassId()).isEqualTo(targetClass);
        assertThat(copy.getCreatedBy()).isEqualTo(lecturerId);

        // Carried over verbatim.
        assertThat(copy.getType()).isEqualTo(source.getType());
        assertThat(copy.getDescription()).isEqualTo(source.getDescription());
        assertThat(copy.getDurationMinutes()).isEqualTo(source.getDurationMinutes());
        assertThat(copy.getPassingScore()).isEqualByComparingTo(source.getPassingScore());
        assertThat(copy.getTotalQuestions()).isEqualTo(source.getTotalQuestions());
        assertThat(copy.isShuffleQuestions()).isEqualTo(source.isShuffleQuestions());
        assertThat(copy.isShuffleOptions()).isEqualTo(source.isShuffleOptions());
        assertThat(copy.getTimeMode()).isEqualTo(source.getTimeMode());
        assertThat(copy.getMediaType()).isEqualTo(source.getMediaType());
        assertThat(copy.getMediaUrl()).isEqualTo(source.getMediaUrl());
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void an_exam_with_no_class_clones_normally_into_a_target_class() throws Exception {
        mockMvc.perform(post(RAIL_URL + "/" + classlessExamId + "/clone").with(csrf())
                        .param("targetClassId", String.valueOf(targetClass)))
                .andExpect(status().isOk());

        assertThat(onlyExamIn(targetClass).getClassId()).isEqualTo(targetClass);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void cloning_into_an_unowned_class_writes_no_row_at_all() throws Exception {
        Long foreign = createClass(headId(), TAG + " lớp người khác");
        long testsBefore = physicalTestRowCount();
        long questionsBefore = questionRepository.count();
        long optionsBefore = optionRepository.count();

        mockMvc.perform(post(RAIL_URL + "/" + richExamId + "/clone").with(csrf())
                        .param("targetClassId", String.valueOf(foreign)))
                .andExpect(status().isForbidden());

        // Status alone cannot prove nothing was written — the row counts can.
        // They stay equal only because the target check precedes the first save.
        assertThat(physicalTestRowCount()).isEqualTo(testsBefore);
        assertThat(questionRepository.count()).isEqualTo(questionsBefore);
        assertThat(optionRepository.count()).isEqualTo(optionsBefore);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void cloning_an_exam_that_does_not_exist_returns_404() throws Exception {
        // An id far past anything seeded, so it can never collide with a real row.
        mockMvc.perform(post(RAIL_URL + "/" + Long.MAX_VALUE + "/clone").with(csrf())
                        .param("targetClassId", String.valueOf(targetClass)))
                .andExpect(status().isNotFound());
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void cloning_an_already_soft_deleted_exam_returns_404() throws Exception {
        mockMvc.perform(post(RAIL_URL + "/" + richExamId + "/delete").with(csrf()))
                .andExpect(status().isOk());

        // A soft-deleted source must read as absent, not as an empty clone: the
        // entity's @SQLRestriction hides it, so the resolver reports 404.
        mockMvc.perform(post(RAIL_URL + "/" + richExamId + "/clone").with(csrf())
                        .param("targetClassId", String.valueOf(targetClass)))
                .andExpect(status().isNotFound());
    }

    // ── Soft delete ─────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void deleting_an_exam_that_has_attempts_is_hard_blocked() throws Exception {
        attemptRepository.saveAndFlush(new TestAttempt(richExamId, studentId()));
        long testsBefore = physicalTestRowCount();

        mockMvc.perform(post(RAIL_URL + "/" + richExamId + "/delete").with(csrf()))
                .andExpect(status().isBadRequest());

        // Assert on row state, not the status code: a student's graded work must
        // survive, so is_deleted has to still be 0.
        assertThat(isDeletedFlag(richExamId)).isFalse();
        assertThat(physicalTestRowCount()).isEqualTo(testsBefore);
        assertThat(rowFor(richExamId)).isNotNull();
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void deleting_an_exam_with_no_attempts_soft_deletes_it_and_keeps_its_questions() throws Exception {
        long testsBefore = physicalTestRowCount();
        long questionsBefore = questionRepository.count();
        long optionsBefore = optionRepository.count();

        mockMvc.perform(post(RAIL_URL + "/" + richExamId + "/delete").with(csrf()))
                .andExpect(status().isOk());

        // Soft, never hard: the row survives with only is_deleted flipped.
        assertThat(isDeletedFlag(richExamId)).isTrue();
        assertThat(physicalTestRowCount()).isEqualTo(testsBefore);
        // Children are untouched, so a future restore would find the exam intact.
        assertThat(questionRepository.count()).isEqualTo(questionsBefore);
        assertThat(optionRepository.count()).isEqualTo(optionsBefore);
        // @SQLRestriction("is_deleted = 0") removes it from the rail with no
        // extra filter in the query.
        assertThat(rowFor(richExamId)).isNull();
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void a_lecturer_cannot_delete_an_exam_they_do_not_own() throws Exception {
        // Created by another user, in a class that user leads — so neither arm
        // of the ownership predicate (createdBy / leads the class) matches.
        Long foreignExam = seedForeignExam();

        mockMvc.perform(post(RAIL_URL + "/" + foreignExam + "/delete").with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(isDeletedFlag(foreignExam)).isFalse();
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void preview_reached_from_the_rail_offers_a_back_link_to_the_rail() throws Exception {
        mockMvc.perform(get("/lecturer/tests/" + richExamId + "/preview").param("from", "library"))
                .andExpect(status().isOk())
                .andExpect(model().attribute(ATTR_PREVIEW_BACK_URL, RAIL_URL));
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void preview_without_an_origin_still_returns_to_the_edit_screen() throws Exception {
        mockMvc.perform(get("/lecturer/tests/" + richExamId + "/preview"))
                .andExpect(status().isOk())
                .andExpect(model().attribute(ATTR_PREVIEW_BACK_URL,
                        "/lecturer/tests/" + richExamId + "/edit?tab=info"));
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void preview_ignores_an_unknown_origin_rather_than_redirecting_to_it() throws Exception {
        // The origin is a whitelisted token, never a URL — an attacker-supplied
        // value must fall back to the edit screen, not steer the back-link.
        mockMvc.perform(get("/lecturer/tests/" + richExamId + "/preview")
                        .param("from", "https://evil.example.com"))
                .andExpect(status().isOk())
                .andExpect(model().attribute(ATTR_PREVIEW_BACK_URL,
                        "/lecturer/tests/" + richExamId + "/edit?tab=info"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Map<String, Object> modelAt(String url) throws Exception {
        return mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel();
    }

    @SuppressWarnings("unchecked")
    private static Page<LecturerExamRow> examPageOf(Map<String, Object> model) {
        return (Page<LecturerExamRow>) model.get(ATTR_EXAMS_PAGE);
    }

    /** The rail row for an exam, or null when the rail no longer lists it. */
    private LecturerExamRow rowFor(Long examId) throws Exception {
        Map<String, Object> model = modelAt(RAIL_URL + "?q=" + TAG + "&size=100");
        assertThat(model).containsKey(ATTR_EXAM_FILTER);
        return examPageOf(model).getContent().stream()
                .filter(r -> examId.equals(r.id()))
                .findFirst().orElse(null);
    }

    /** The single exam this test cloned into the (initially empty) target class. */
    private Test onlyExamIn(Long classId) {
        entityManager.flush();
        entityManager.clear();
        List<Test> found = testRepository.findAll().stream()
                .filter(t -> classId.equals(t.getClassId()))
                .toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    /**
     * Reads {@code is_deleted} with native SQL: the entity's
     * {@code @SQLRestriction} hides soft-deleted rows from every JPA query, so
     * a repository lookup could not distinguish "soft-deleted" from "gone".
     */
    private boolean isDeletedFlag(Long examId) {
        entityManager.flush();
        // TINYINT(1) comes back as Boolean on MySQL and as a Number on some
        // drivers — normalise both rather than depending on the mapping.
        Object flag = entityManager
                .createNativeQuery("SELECT is_deleted FROM tests WHERE id = :id")
                .setParameter("id", examId)
                .getSingleResult();
        return flag instanceof Number n ? n.intValue() != 0 : Boolean.TRUE.equals(flag);
    }

    /**
     * Physical row count of {@code tests}, soft-deleted rows included.
     * {@code testRepository.count()} cannot be used to prove "no hard delete":
     * the entity's {@code @SQLRestriction} hides soft-deleted rows, so a soft
     * delete and a hard delete both lower it by one.
     */
    private long physicalTestRowCount() {
        entityManager.flush();
        Object count = entityManager
                .createNativeQuery("SELECT COUNT(*) FROM tests")
                .getSingleResult();
        return ((Number) count).longValue();
    }

    private Long headId() {
        return userRepository.findByEmailIgnoreCase(HEAD).orElseThrow().getId();
    }

    private Long studentId() {
        return userRepository.findByEmailIgnoreCase(STUDENT).orElseThrow().getId();
    }

    private Long createClass(Long lecturerId, String name) {
        ClassEntity clazz = new ClassEntity(name, lecturerId, lecturerId, null, null, null, 100);
        clazz.setCode("RAIL" + (int) (Math.random() * 900_000 + 100_000));
        return classRepository.saveAndFlush(clazz).getId();
    }

    private void seedLibraryAsset(String kind, String filename, String mime) {
        assetRepository.saveAndFlush(new LibraryAsset(lecturerId, filename, filename,
                "library/" + lecturerId + "/" + filename, mime, 1024L, kind));
    }

    /** A live lesson so the "Bài giảng" badge is non-zero for this lecturer. */
    private void seedLesson(Long classId) {
        Section section = sectionRepository.saveAndFlush(
                new Section(classId, TAG + " chương", (short) 1, lecturerId));
        lessonRepository.saveAndFlush(
                new Lesson(section.getId(), TAG + " bài", (short) 1, lecturerId));
    }

    /** A published exam with a full schedule and non-default settings to copy. */
    private Long seedExamWithQuestions(Long classId, String title) {
        Test exam = new Test(lecturerId, Test.TYPE_MODULE);
        exam.setTitle(title);
        exam.setDescription(TAG + " mô tả");
        exam.setClassId(classId);
        exam.setStatus(Test.STATUS_PUBLISHED);
        exam.setDurationMinutes(75);
        exam.setPassingScore(new BigDecimal("6.50"));
        exam.setTotalQuestions(QUESTIONS);
        exam.setShuffleQuestions(true);
        exam.setShuffleOptions(true);
        exam.setTimeMode(Test.TIME_MODE_INDIVIDUAL);
        exam.setMediaType(Test.MEDIA_TYPE_YOUTUBE);
        exam.setMediaUrl("https://www.youtube.com/watch?v=" + TAG);
        exam.setStartAt(LocalDateTime.now().minusDays(1));
        exam.setEndAt(LocalDateTime.now().plusDays(7));
        Test saved = testRepository.saveAndFlush(exam);

        for (int q = 0; q < QUESTIONS; q++) {
            Question question = questionRepository.saveAndFlush(new Question(
                    saved.getId(), Question.TYPE_MCQ,
                    "<p>" + TAG + " câu " + q + " <img src=\"/uploads/exams/x" + q + ".png\"></p>",
                    TAG + " giải thích " + q, new BigDecimal("1.00"), q));
            for (int o = 0; o < OPTIONS_PER_QUESTION; o++) {
                // Exactly one correct option per question, rotating by index.
                optionRepository.saveAndFlush(new QuestionOption(
                        question.getId(), TAG + " đáp án " + q + "-" + o,
                        o == q % OPTIONS_PER_QUESTION, o));
            }
        }
        testRepository.flush();
        return saved.getId();
    }

    /** An exam created by, and in a class led by, someone other than LECTURER. */
    private Long seedForeignExam() {
        Long otherOwner = headId();
        Test exam = new Test(otherOwner, Test.TYPE_MOCK);
        exam.setTitle(TAG + " bài của người khác");
        exam.setClassId(createClass(otherOwner, TAG + " lớp người khác (xoá)"));
        exam.setStatus(Test.STATUS_DRAFT);
        exam.setTotalQuestions(0);
        return testRepository.saveAndFlush(exam).getId();
    }

    /** An exam with no questions; {@code classId} may be null by design. */
    private Long seedBareExam(Long classId, String title) {
        Test exam = new Test(lecturerId, Test.TYPE_MOCK);
        exam.setTitle(title);
        exam.setClassId(classId);
        exam.setStatus(Test.STATUS_DRAFT);
        exam.setTotalQuestions(0);
        return testRepository.saveAndFlush(exam).getId();
    }
}
