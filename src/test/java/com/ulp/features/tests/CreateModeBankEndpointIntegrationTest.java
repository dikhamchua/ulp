package com.ulp.features.tests;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.repository.SubjectRepository;
import com.ulp.features.tests.repository.QuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint-level guarantees for the class-scoped bank picker that exam create
 * mode uses: the leading lecturer reads it, everyone else gets 403 (never a
 * catch-all 500), and there is no class-scoped write path at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CreateModeBankEndpointIntegrationTest {

    private static final String LECTURER = "lecturer@ulp.edu.vn";
    private static final String OTHER_LECTURER = "head@ulp.edu.vn"; // LECTURER+ but leads no such class
    private static final String ADMIN = "admin@ulp.edu.vn";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private QuestionRepository questionRepository;

    private Long classId;

    @BeforeEach
    void setUp() {
        User lecturer = userRepository.findByEmailIgnoreCase(LECTURER).orElseThrow();
        Long departmentId = lecturer.getDepartmentId();
        ClassEntity clazz = classRepository.findAllByLecturerId(lecturer.getId()).stream()
                .findFirst().orElseThrow();
        Subject subject = subjectRepository.findAllByDepartmentIdOrderByCodeAsc(departmentId).stream()
                .filter(Subject::isActive)
                .findFirst().orElseThrow();
        clazz.setSubjectId(subject.getId());
        clazz.setDepartmentId(departmentId);
        classRepository.save(clazz);
        classId = clazz.getId();
    }

    @Test
    @WithUserDetails(LECTURER)
    void get_class_search_returns_200_for_the_owning_lecturer() throws Exception {
        mockMvc.perform(get("/lecturer/classes/" + classId + "/question-bank/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.scope.classId").value(classId));
    }

    @Test
    @WithUserDetails(LECTURER)
    void get_class_chapters_returns_200_for_the_owning_lecturer() throws Exception {
        mockMvc.perform(get("/lecturer/classes/" + classId + "/question-bank/chapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    @WithUserDetails(OTHER_LECTURER)
    void get_class_search_returns_403_for_a_foreign_lecturer() throws Exception {
        mockMvc.perform(get("/lecturer/classes/" + classId + "/question-bank/search"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails(ADMIN)
    void get_class_search_returns_403_for_admin() throws Exception {
        // Regression recorded in .claude/rules/authorization-check.md: a role that
        // clears layer 1 but has no data scope must land on 403, not 500.
        mockMvc.perform(get("/lecturer/classes/" + classId + "/question-bank/search"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails(LECTURER)
    void post_class_insert_writes_nothing_because_it_is_not_mapped() throws Exception {
        long before = questionRepository.count();

        // Pins the decision that the write path stays testId-only: create mode
        // inserts client-side and persists through the normal exam save.
        int statusCode = mockMvc.perform(post("/lecturer/classes/" + classId + "/question-bank/insert")
                        .with(csrf()))
                .andReturn().getResponse().getStatus();

        // Row count, not status code, is what proves nothing was written —
        // .claude/rules/authorization-check.md. Assert it first so it always runs.
        assertThat(questionRepository.count()).isEqualTo(before);

        // The app-wide catch-all in GlobalExceptionHandler swallows Spring's
        // no-handler exception, so every unmapped URL answers 500 rather than
        // 404/405. Pre-existing global behaviour, not something this route added;
        // asserted here only so a future change to it is a deliberate decision.
        assertThat(statusCode).isEqualTo(500);
    }
}
