package com.ulp.features.library.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.lessons.dto.LessonDtos.LessonRow;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.lessons.service.LessonsService;
import com.ulp.features.library.dto.LibraryDtos.LibraryAssetRow;
import com.ulp.features.library.service.LibraryService;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * MockMvc coverage for library page authz, upload, picker API, and bind endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LibraryControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LibraryService libraryService;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonsService lessonsService;
    @Autowired private LessonAttachmentRepository attachmentRepository;

    private User lecturer;
    private Long classId;
    private Long sectionId;
    private Long lessonId;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        ClassEntity clazz = saveClass("Lib MVC class", lecturer.getId());
        classId = clazz.getId();
        Section section = sectionRepository.saveAndFlush(
                new Section(classId, "Ch", (short) 0, lecturer.getId()));
        sectionId = section.getId();
        LessonRow lesson = lessonsService.create(
                classId, sectionId, "Bài lib", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        lessonId = lesson.id();
    }

    private static byte[] pdfBytes() {
        return new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, 0x0A};
    }

    private ClassEntity saveClass(String name, Long lecturerId) {
        String code = "M" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        ClassEntity entity = new ClassEntity(name, lecturerId, lecturerId,
                null, null, null, 100);
        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "X");
            return classRepository.saveAndFlush(entity);
        }
    }

    @Test
    void anonymous_library_redirects_to_login() throws Exception {
        mockMvc.perform(get("/lecturer/library"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_is_forbidden() throws Exception {
        mockMvc.perform(get("/lecturer/library"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_library_page_ok_and_nav_present() throws Exception {
        mockMvc.perform(get("/lecturer/library"))
                .andExpect(status().isOk())
                .andExpect(view().name("library/index"))
                .andExpect(content().string(containsString("Kho học liệu")));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void upload_and_api_list() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "handout.pdf", "application/pdf", pdfBytes());
        mockMvc.perform(multipart("/lecturer/library/upload").file(file).with(csrf())
                        .param("kind", "DOCUMENT"))
                .andExpect(status().is3xxRedirection())
                // Successful upload lands on the asset's kind tab (DOCUMENT).
                .andExpect(redirectedUrl("/lecturer/library?kind=DOCUMENT"));

        mockMvc.perform(get("/lecturer/library/api").param("kind", "DOCUMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].originalFilename").value("handout.pdf"))
                .andExpect(jsonPath("$.items[0].kind").value("DOCUMENT"));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void bind_pdf_from_library_sets_fk() throws Exception {
        LibraryAssetRow asset = libraryService.upload(
                lecturer.getId(),
                new MockMultipartFile("file", "main.pdf", "application/pdf", pdfBytes()),
                "DOCUMENT");

        mockMvc.perform(post("/lecturer/classes/{cid}/sections/{sid}/lessons/{lid}/content/pdf-from-library",
                        classId, sectionId, lessonId)
                        .param("assetId", String.valueOf(asset.id()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pdfAttachmentId").isNumber());

        var rows = attachmentRepository.findByLessonIdOrderByUploadedAtAsc(lessonId);
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).getLibraryAssetId()).isEqualTo(asset.id());
        assertThat(rows.get(0).getStoredPath()).startsWith("library/");
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void classic_attachment_upload_keeps_null_library_fk() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "extra.pdf", "application/pdf", pdfBytes());
        mockMvc.perform(multipart(
                        "/lecturer/classes/{cid}/sections/{sid}/lessons/{lid}/attachments",
                        classId, sectionId, lessonId)
                        .file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());

        var rows = attachmentRepository.findByLessonIdOrderByUploadedAtAsc(lessonId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLibraryAssetId()).isNull();
        assertThat(rows.get(0).getStoredPath()).startsWith("lessons/");
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void delete_blocked_when_referenced() throws Exception {
        LibraryAssetRow asset = libraryService.upload(
                lecturer.getId(),
                new MockMultipartFile("file", "used.pdf", "application/pdf", pdfBytes()),
                "DOCUMENT");
        mockMvc.perform(post("/lecturer/classes/{cid}/sections/{sid}/lessons/{lid}/attachments/from-library",
                        classId, sectionId, lessonId)
                        .param("assetId", String.valueOf(asset.id()))
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/lecturer/library/{id}/delete", asset.id()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/library"));

        assertThat(libraryService.list(lecturer.getId(), "used", "DOCUMENT", 0, 20).page().getContent())
                .anyMatch(i -> i.id().equals(asset.id()));
    }
}
