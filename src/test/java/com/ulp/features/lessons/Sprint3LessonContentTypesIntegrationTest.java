package com.ulp.features.lessons;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.dto.LessonDtos.LessonRow;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.lessons.service.LessonsPublishService;
import com.ulp.features.lessons.service.LessonsService;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 3 integration test for the content-type extension to lessons-crud.
 *
 * <p>Drives the four canonical flows end-to-end via MockMvc so the full
 * filter chain, controller layering, multipart pipeline, and student-side
 * rendering all participate. Verifies the V16 migration applied during
 * test bootstrap (implicit — if the schema were missing the create call
 * below would fail).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Sprint3LessonContentTypesIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private LessonsService lessonsService;
    @Autowired private LessonsPublishService publishService;
    @Autowired private EnrollmentRepository enrollmentRepository;

    private User lecturer;
    private User student;
    private ClassEntity clazz;
    private Section section;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        student = ensureUser("student-s3lct@ulp.edu.vn", "Student S3LCT", Role.STUDENT);
        clazz = saveClass("S3 content-type class", "S3LCT01", lecturer.getId());
        section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
        // Enroll the student so they can view the student detail page.
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                student, clazz.getId(), Enrollment.JoinedVia.CODE, null));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_uploads_pdf_and_student_can_view_pdf_lesson() throws Exception {
        LessonRow row = lessonsService.create(clazz.getId(), section.getId(),
                "PDF lesson", "DRAFT", "", lecturer.getId(), Role.LECTURER);

        // Upload main PDF via the content endpoint.
        String url = pdfUrl(row.id());
        mockMvc.perform(multipart(url)
                        .file(new MockMultipartFile("file", "main.pdf", "application/pdf",
                                pdfBytes()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").exists());

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getPdfAttachmentId()).isNotNull();

        // Submit the edit form to switch the type to PDF.
        mockMvc.perform(post(editUrl(row.id()))
                        .with(csrf())
                        .param("title", "PDF lesson")
                        .param("status", "PUBLISHED")
                        .param("contentType", "PDF")
                        .param("contentHtml", ""))
                .andExpect(status().is3xxRedirection());

        Lesson published = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(published.getContentType()).isEqualTo("PDF");
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_sets_youtube_url_via_content_endpoint() throws Exception {
        LessonRow row = lessonsService.create(clazz.getId(), section.getId(),
                "YT lesson", "DRAFT", "", lecturer.getId(), Role.LECTURER);

        mockMvc.perform(post(videoUrlUrl(row.id()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("provider", "YOUTUBE")
                        .param("url", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoProvider").value("YOUTUBE"));

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getVideoProvider()).isEqualTo("YOUTUBE");
        assertThat(reloaded.getVideoUrl()).contains("dQw4w9WgXcQ");
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void content_video_url_rejects_non_provider_url() throws Exception {
        LessonRow row = lessonsService.create(clazz.getId(), section.getId(),
                "Bad URL", "DRAFT", "", lecturer.getId(), Role.LECTURER);

        mockMvc.perform(post(videoUrlUrl(row.id()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("provider", "YOUTUBE")
                        .param("url", "https://malicious.example/x"))
                .andExpect(status().isBadRequest());

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getVideoUrl()).isNull();
    }

    @Test
    @WithUserDetails(value = "student-s3lct@ulp.edu.vn",
            setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void enrolled_student_sees_pdf_viewer_markup_when_lesson_is_pdf() throws Exception {
        LessonRow row = lessonsService.create(clazz.getId(), section.getId(),
                "Student PDF view", "DRAFT", "", lecturer.getId(), Role.LECTURER);
        // Switch the lesson to PDF + publish via the service path so we
        // do not depend on the lecturer MockMvc session inside this test.
        com.ulp.entities.LessonAttachment att =
                attachmentRepoSave(row.id(), "viewer.pdf", "application/pdf");
        Lesson lesson = lessonRepository.findById(row.id()).orElseThrow();
        lesson.setPdfAttachmentId(att.getId());
        lesson.switchContentTypeTo("PDF");
        lessonRepository.saveAndFlush(lesson);
        publishService.publish(clazz.getId(), section.getId(), row.id(),
                lecturer.getId(), Role.LECTURER);

        mockMvc.perform(get("/my/classes/" + clazz.getId() + "/lessons"
                        + "?section=" + section.getId() + "&lesson=" + row.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lesson-pdf-viewer")))
                .andExpect(content().string(containsString("Tải PDF xuống")));
    }

    @Test
    @WithUserDetails(value = "student-s3lct@ulp.edu.vn",
            setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void enrolled_student_sees_youtube_iframe_when_lesson_is_video() throws Exception {
        LessonRow row = lessonsService.create(clazz.getId(), section.getId(),
                "Student YT view", "DRAFT", "", lecturer.getId(), Role.LECTURER);
        lessonsService.setExternalVideo(clazz.getId(), section.getId(), row.id(),
                "YOUTUBE", "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                lecturer.getId(), Role.LECTURER);
        Lesson lesson = lessonRepository.findById(row.id()).orElseThrow();
        lesson.switchContentTypeTo("VIDEO");
        lessonRepository.saveAndFlush(lesson);
        publishService.publish(clazz.getId(), section.getId(), row.id(),
                lecturer.getId(), Role.LECTURER);

        mockMvc.perform(get("/my/classes/" + clazz.getId() + "/lessons"
                        + "?section=" + section.getId() + "&lesson=" + row.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lesson-video-viewer")))
                .andExpect(content().string(containsString("youtube.com/embed/dQw4w9WgXcQ")));
    }

    // ── Stream controller tests (Sprint 3) ─────────────────────────────

    @Test
    @WithUserDetails(value = "student-s3lct@ulp.edu.vn",
            setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void stream_returns_404_when_lesson_is_youtube_not_upload() throws Exception {
        // External video providers don't go through the stream endpoint;
        // only UPLOAD does. Anything else collapses to 404 so the endpoint
        // does not leak the existence of YouTube/Vimeo lessons.
        LessonRow row = lessonsService.create(clazz.getId(), section.getId(),
                "YT lesson", "DRAFT", "", lecturer.getId(), Role.LECTURER);
        lessonsService.setExternalVideo(clazz.getId(), section.getId(), row.id(),
                "YOUTUBE", "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                lecturer.getId(), Role.LECTURER);
        Lesson l = lessonRepository.findById(row.id()).orElseThrow();
        l.switchContentTypeTo("VIDEO");
        lessonRepository.saveAndFlush(l);
        publishService.publish(clazz.getId(), section.getId(), row.id(),
                lecturer.getId(), Role.LECTURER);

        mockMvc.perform(get("/api/lessons/" + row.id() + "/video/stream"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails(value = "student-s3lct@ulp.edu.vn",
            setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void stream_returns_404_for_student_not_enrolled_in_class() throws Exception {
        // Build a SECOND class the student is NOT enrolled in, plant an
        // UPLOAD video lesson there, then verify the student gets 404 when
        // they request its stream — even though their other class makes them
        // an authenticated user.
        ClassEntity otherClass = saveClass("Other S3 class", "S3OTH9", lecturer.getId());
        Section otherSection = sectionRepository.saveAndFlush(
                new Section(otherClass.getId(), "Other chapter", (short) 0, lecturer.getId()));
        LessonRow row = lessonsService.create(otherClass.getId(), otherSection.getId(),
                "Locked video", "DRAFT", "", lecturer.getId(), Role.LECTURER);
        Lesson l = lessonRepository.findById(row.id()).orElseThrow();
        l.setVideoProvider("UPLOAD");
        l.setVideoUrl("lessons/" + row.id() + "/video/fake.mp4");
        l.switchContentTypeTo("VIDEO");
        lessonRepository.saveAndFlush(l);
        publishService.publish(otherClass.getId(), otherSection.getId(), row.id(),
                lecturer.getId(), Role.LECTURER);

        mockMvc.perform(get("/api/lessons/" + row.id() + "/video/stream"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails(value = "student-s3lct@ulp.edu.vn",
            setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void stream_returns_404_when_lesson_is_draft() throws Exception {
        // Even when the student is enrolled in the class, DRAFT lessons
        // remain lecturer-private and the stream must not leak the MP4.
        LessonRow row = lessonsService.create(clazz.getId(), section.getId(),
                "Draft video", "DRAFT", "", lecturer.getId(), Role.LECTURER);
        Lesson l = lessonRepository.findById(row.id()).orElseThrow();
        l.setVideoProvider("UPLOAD");
        l.setVideoUrl("lessons/" + row.id() + "/video/draft.mp4");
        l.switchContentTypeTo("VIDEO");
        lessonRepository.saveAndFlush(l);
        // Intentionally NOT publishing — status stays DRAFT.

        mockMvc.perform(get("/api/lessons/" + row.id() + "/video/stream"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails(value = "student-s3lct@ulp.edu.vn",
            setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void stream_returns_404_when_file_missing_on_disk() throws Exception {
        // Happy-path authz but the recorded MP4 path points at nothing on
        // disk — the endpoint must collapse to 404 without leaking the
        // filesystem error.
        LessonRow row = lessonsService.create(clazz.getId(), section.getId(),
                "Missing file", "DRAFT", "", lecturer.getId(), Role.LECTURER);
        Lesson l = lessonRepository.findById(row.id()).orElseThrow();
        l.setVideoProvider("UPLOAD");
        l.setVideoUrl("lessons/" + row.id() + "/video/ghost.mp4");
        l.switchContentTypeTo("VIDEO");
        lessonRepository.saveAndFlush(l);
        publishService.publish(clazz.getId(), section.getId(), row.id(),
                lecturer.getId(), Role.LECTURER);

        mockMvc.perform(get("/api/lessons/" + row.id() + "/video/stream"))
                .andExpect(status().isNotFound());
    }

    @Autowired
    private com.ulp.features.upload.LessonVideoStorageService videoStorage;

    // ── Helpers ───────────────────────────────────────────────────────

    @Autowired private com.ulp.features.lessons.repository.LessonAttachmentRepository attachmentRepository;

    private com.ulp.entities.LessonAttachment attachmentRepoSave(Long lessonId, String name, String mime) {
        return attachmentRepository.saveAndFlush(new com.ulp.entities.LessonAttachment(
                lessonId, name, "stored/" + name, mime, 512L, lecturer.getId()));
    }

    private static byte[] pdfBytes() {
        return new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37};
    }

    private String pdfUrl(Long lessonId) {
        return "/lecturer/classes/" + clazz.getId() + "/sections/" + section.getId()
                + "/lessons/" + lessonId + "/content/pdf";
    }

    private String videoUrlUrl(Long lessonId) {
        return "/lecturer/classes/" + clazz.getId() + "/sections/" + section.getId()
                + "/lessons/" + lessonId + "/content/video-url";
    }

    private String editUrl(Long lessonId) {
        return "/lecturer/classes/" + clazz.getId() + "/sections/" + section.getId()
                + "/lessons/" + lessonId + "/edit";
    }

    private ClassEntity saveClass(String name, String code, Long lecturerId) {
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

    private User ensureUser(String email, String name, Role role) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = UserFactory.newAdminCreated(email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    name, role, true, null, null);
            return userRepository.saveAndFlush(u);
        });
    }
}
