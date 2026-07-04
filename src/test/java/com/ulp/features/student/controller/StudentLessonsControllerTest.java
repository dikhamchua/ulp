package com.ulp.features.student.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.Lesson;
import com.ulp.entities.LessonAttachment;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.progress.repository.LearningProgressRepository;
import com.ulp.features.progress.service.LearningProgressService;
import com.ulp.features.student.dto.StudentLessonsDtos.ClassLessonsView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for {@link StudentLessonsController}. Exercises the
 * security filter chain, controller wiring, and template selection
 * end-to-end via MockMvc.
 *
 * <p>Uses pre-seeded users from migrations (V5 for {@code student@ulp.edu.vn}
 * and V8 for {@code sv01@ulp.edu.vn}) because {@code @WithUserDetails}
 * resolves the principal before {@code @BeforeEach} runs, so users
 * created in setup are not visible to {@code UserDetailsService}.
 *
 * <p>The single-template refactor folded the standalone lesson-detail
 * view into the 3-column list template; tests here also cover the
 * inline viewer dispatch per {@code contentType}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentLessonsControllerTest {

    // Seeded by V5 — the enrolled-student account for tests.
    private static final String STUDENT_EMAIL = "student@ulp.edu.vn";
    // Seeded by V8 — the not-enrolled student account.
    private static final String OUTSIDER_EMAIL = "sv01@ulp.edu.vn";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private LessonAttachmentRepository lessonAttachmentRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private LearningProgressRepository progressRepository;

    // Spy (not mock): STUDENT tests keep the real progress write; only the
    // moderator test verifies recordOpened is never invoked (D7 guard).
    @MockitoSpyBean private LearningProgressService learningProgressService;

    private User lecturer;
    private User student;
    private ClassEntity clazz;
    private Section section1;
    private Lesson defaultLesson;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        student = userRepository.findByEmailIgnoreCase(STUDENT_EMAIL).orElseThrow();
        clazz = saveClass("Controller class", lecturer.getId(), "STCTLC");
        section1 = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
        Lesson l = new Lesson(section1.getId(), "Bài 1", (short) 0, lecturer.getId());
        l.updateContent("<p>Body</p>");
        l.publish();
        defaultLesson = lessonRepository.saveAndFlush(l);
        // Enroll the seeded primary student into this fresh class.
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                student, clazz.getId(), Enrollment.JoinedVia.CODE, null));
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void get_as_enrolled_student_returns_200_with_view_and_model() throws Exception {
        var result = mockMvc.perform(get(url(clazz.getId(), null)))
                .andExpect(status().isOk())
                .andExpect(view().name("student/class-lessons"))
                .andExpect(model().attributeExists("view"))
                .andExpect(model().attributeExists("activeSectionId"))
                .andReturn();

        ClassLessonsView view = (ClassLessonsView) result.getModelAndView()
                .getModel().get("view");
        assertThat(view).isNotNull();
        assertThat(view.classId()).isEqualTo(clazz.getId());
        assertThat(view.sections()).hasSize(1);
        assertThat(view.sections().get(0).lessons()).hasSize(1);
    }

    @Test
    @WithUserDetails(OUTSIDER_EMAIL)
    void get_as_not_enrolled_student_returns_404() throws Exception {
        mockMvc.perform(get(url(clazz.getId(), null)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    void get_as_anonymous_redirects_to_login() throws Exception {
        mockMvc.perform(get(url(clazz.getId(), null)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void get_with_invalid_section_renders_with_default_active_section() throws Exception {
        var result = mockMvc.perform(get(url(clazz.getId(), 999999L)))
                .andExpect(status().isOk())
                .andExpect(view().name("student/class-lessons"))
                .andReturn();

        // Invalid section param falls back to the first section (D7).
        Long active = (Long) result.getModelAndView().getModel().get("activeSectionId");
        assertThat(active).isEqualTo(section1.getId());
    }

    // ── Inline lesson detail (single-template refactor) ───────────────

    /** RICHTEXT lessons render the sanitised body inside the article wrapper. */
    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void class_lessons_renders_richtext_viewer_when_type_is_RICHTEXT() throws Exception {
        mockMvc.perform(get(urlWithLesson(clazz.getId(), section1.getId(), defaultLesson.getId())))
                .andExpect(status().isOk())
                .andExpect(view().name("student/class-lessons"))
                .andExpect(model().attributeExists("lessonDetail"))
                // Article wrapper is the contract used by the RICHTEXT branch.
                .andExpect(content().string(containsString("<article class=\"student-lesson-detail-content\"")))
                // Body must be unescaped (th:utext) — proven by the raw <p> tag.
                .andExpect(content().string(containsString("<p>Body</p>")));
    }

    /** PDF lessons render a PDF.js &lt;iframe&gt; plus a download fallback link. */
    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void class_lessons_renders_pdf_viewer_when_type_is_PDF() throws Exception {
        Lesson pdfLesson = new Lesson(section1.getId(), "Bài PDF", (short) 1, lecturer.getId());
        pdfLesson.publish();
        pdfLesson = lessonRepository.saveAndFlush(pdfLesson);
        LessonAttachment main = lessonAttachmentRepository.saveAndFlush(new LessonAttachment(
                pdfLesson.getId(), "main.pdf", "stored/main.pdf",
                "application/pdf", 4096L, lecturer.getId()));
        pdfLesson.switchContentTypeTo("PDF");
        pdfLesson.setPdfAttachmentId(main.getId());
        pdfLesson = lessonRepository.saveAndFlush(pdfLesson);

        // Fallback download link still points at the raw stream endpoint.
        String expectedDownloadUrl = "/api/lessons/" + pdfLesson.getId()
                + "/attachments/" + main.getId() + "/download";
        mockMvc.perform(get(urlWithLesson(clazz.getId(), section1.getId(), pdfLesson.getId())))
                .andExpect(status().isOk())
                // Viewer is now a PDF.js iframe, not a browser-native <embed>.
                .andExpect(content().string(containsString("class=\"lesson-pdf-iframe\"")))
                // iframe src is the file-viewer page carrying the real PDF filename.
                .andExpect(content().string(containsString("/file-viewer?type=pdf")))
                .andExpect(content().string(containsString("main.pdf")))
                .andExpect(content().string(containsString(expectedDownloadUrl)));
    }

    /** VIDEO/YOUTUBE lessons render an iframe pointing at the embed URL. */
    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void class_lessons_renders_video_iframe_when_provider_is_YOUTUBE() throws Exception {
        Lesson videoLesson = new Lesson(section1.getId(), "Bài YT", (short) 2, lecturer.getId());
        videoLesson.publish();
        videoLesson = lessonRepository.saveAndFlush(videoLesson);
        videoLesson.switchContentTypeTo("VIDEO");
        videoLesson.setVideoProvider("YOUTUBE");
        videoLesson.setVideoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        videoLesson = lessonRepository.saveAndFlush(videoLesson);

        mockMvc.perform(get(urlWithLesson(clazz.getId(), section1.getId(), videoLesson.getId())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<iframe")))
                .andExpect(content().string(containsString("youtube.com/embed/dQw4w9WgXcQ")));
    }

    // ── Moderator opens a lesson: no progress side-effect (design D7) ──

    /**
     * A moderator (owning lecturer, admitted via the widened D7 gate but not
     * enrolled) may open the lesson to moderate its thread, yet must NOT
     * accrue a learning-progress row — and the open must not emit a WARN.
     */
    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void moderator_open_records_no_progress_and_no_warn() throws Exception {
        mockMvc.perform(get(urlWithLesson(clazz.getId(), section1.getId(), defaultLesson.getId())))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("lessonDetail"));

        // The D7 guard short-circuits before recordOpened for non-students.
        // This is the assertion that locks the guard: reverting it lets the
        // controller call recordOpened for the moderator and fails here.
        verify(learningProgressService, never()).recordOpened(anyLong(), anyLong(), anyLong());

        // Invariant kept for defence in depth: even if recordOpened were
        // reached, its gate throws before persisting, so no row exists.
        assertThat(progressRepository
                .findByUserIdAndLessonId(lecturer.getId(), defaultLesson.getId()))
                .isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static String url(Long classId, Long sectionParam) {
        String base = "/my/classes/" + classId + "/lessons";
        return sectionParam == null ? base : base + "?section=" + sectionParam;
    }

    private static String urlWithLesson(Long classId, Long sectionId, Long lessonId) {
        return "/my/classes/" + classId + "/lessons"
                + "?section=" + sectionId + "&lesson=" + lessonId;
    }

    private ClassEntity saveClass(String name, Long lecturerId, String code) {
        ClassEntity entity = new ClassEntity(name, lecturerId, lecturerId,
                null, null, null, 100);
        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "x");
            return classRepository.saveAndFlush(entity);
        }
    }
}
