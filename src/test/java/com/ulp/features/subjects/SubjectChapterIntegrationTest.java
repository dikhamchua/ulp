package com.ulp.features.subjects;

import com.ulp.entities.Department;
import com.ulp.entities.User;
import com.ulp.features.admin.departments.repository.DepartmentRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.head.service.HeadDepartmentResolver;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.entity.SubjectChapter;
import com.ulp.features.subjects.repository.SubjectChapterRepository;
import com.ulp.features.subjects.repository.SubjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for subject sample-chapter outline (tab Chương).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SubjectChapterIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private SubjectChapterRepository chapterRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HeadDepartmentResolver headDepartmentResolver;

    private Department kt;
    private Department headDept;
    private Subject headSubject;
    private Subject foreignSubject;
    private Subject adminSubject;

    @BeforeEach
    void setUp() {
        kt = departmentRepository.findAll().stream()
                .filter(d -> "KT".equals(d.getCode())).findFirst().orElseThrow();
        User head = userRepository.findByEmailIgnoreCase("head@ulp.edu.vn").orElseThrow();
        headDept = headDepartmentResolver.resolve(head.getId()).orElseThrow();

        headSubject = subjectRepository.saveAndFlush(
                new Subject(headDept.getId(), "CHP-OWN", "Môn HEAD outline", null, true, 1L));
        foreignSubject = subjectRepository.saveAndFlush(
                new Subject(kt.getId(), "CHP-FRN", "Môn KT outline", null, true, 1L));
        // Ensure foreign is not the same department as HEAD (pick another if needed).
        if (kt.getId().equals(headDept.getId())) {
            Department other = departmentRepository.findAll().stream()
                    .filter(d -> !d.getId().equals(headDept.getId()))
                    .findFirst().orElseThrow();
            foreignSubject = subjectRepository.saveAndFlush(
                    new Subject(other.getId(), "CHP-FR2", "Môn khác outline", null, true, 1L));
        }
        adminSubject = headSubject;
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void admin_opens_chapters_tab_empty_state() throws Exception {
        mockMvc.perform(get("/admin/subjects/" + adminSubject.getId() + "/edit")
                        .param("tab", "chapters"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Khung chương mẫu")))
                .andExpect(content().string(containsString("chưa áp dụng vào lớp")))
                .andExpect(content().string(containsString("Chưa có chương mẫu")));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void admin_creates_chapter_without_touching_sections() throws Exception {
        long chaptersBefore = chapterRepository.count();
        long sectionsBefore = sectionRepository.count();

        mockMvc.perform(post("/admin/subjects/" + adminSubject.getId() + "/chapters")
                        .with(csrf())
                        .param("title", "Chương mẫu 1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/admin/subjects/" + adminSubject.getId() + "/edit?tab=chapters"));

        assertThat(chapterRepository.count()).isEqualTo(chaptersBefore + 1);
        assertThat(sectionRepository.count()).isEqualTo(sectionsBefore);

        SubjectChapter saved = chapterRepository
                .findBySubjectIdOrderByDisplayOrderAsc(adminSubject.getId())
                .stream().findFirst().orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("Chương mẫu 1");
        assertThat(saved.getDisplayOrder()).isEqualTo((short) 0);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void head_opens_chapters_tab_on_own_subject() throws Exception {
        mockMvc.perform(get("/head/subjects/" + headSubject.getId() + "/edit")
                        .param("tab", "chapters"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Khung chương mẫu")));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void head_create_on_own_department_subject() throws Exception {
        long before = chapterRepository.count();

        mockMvc.perform(post("/head/subjects/" + headSubject.getId() + "/chapters")
                        .with(csrf())
                        .param("title", "HEAD chương"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/head/subjects/" + headSubject.getId() + "/edit?tab=chapters"));

        assertThat(chapterRepository.count()).isEqualTo(before + 1);
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void head_cannot_create_on_foreign_department_subject() throws Exception {
        long before = chapterRepository.count();

        mockMvc.perform(post("/head/subjects/" + foreignSubject.getId() + "/chapters")
                        .with(csrf())
                        .param("title", "Hack"))
                .andExpect(status().is3xxRedirection());

        assertThat(chapterRepository.count()).isEqualTo(before);
        assertThat(chapterRepository.findBySubjectIdOrderByDisplayOrderAsc(foreignSubject.getId()))
                .isEmpty();
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_forbidden_on_admin_chapter_create() throws Exception {
        long before = chapterRepository.count();

        mockMvc.perform(post("/admin/subjects/" + adminSubject.getId() + "/chapters")
                        .with(csrf())
                        .param("title", "No"))
                .andExpect(status().isForbidden());

        assertThat(chapterRepository.count()).isEqualTo(before);
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void admin_move_and_soft_delete() throws Exception {
        SubjectChapter a = chapterRepository.saveAndFlush(
                new SubjectChapter(adminSubject.getId(), "A", (short) 0, 1L));
        SubjectChapter b = chapterRepository.saveAndFlush(
                new SubjectChapter(adminSubject.getId(), "B", (short) 1, 1L));

        mockMvc.perform(post("/admin/subjects/" + adminSubject.getId()
                        + "/chapters/" + b.getId() + "/move")
                        .with(csrf())
                        .param("direction", "UP"))
                .andExpect(status().is3xxRedirection());

        SubjectChapter aReloaded = chapterRepository.findById(a.getId()).orElseThrow();
        SubjectChapter bReloaded = chapterRepository.findById(b.getId()).orElseThrow();
        assertThat(bReloaded.getDisplayOrder()).isEqualTo((short) 0);
        assertThat(aReloaded.getDisplayOrder()).isEqualTo((short) 1);

        mockMvc.perform(post("/admin/subjects/" + adminSubject.getId()
                        + "/chapters/" + a.getId() + "/delete")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(chapterRepository.findBySubjectIdOrderByDisplayOrderAsc(adminSubject.getId()))
                .extracting(SubjectChapter::getId)
                .containsExactly(b.getId());
    }
}
