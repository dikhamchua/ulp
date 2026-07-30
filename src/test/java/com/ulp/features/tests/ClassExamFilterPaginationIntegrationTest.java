package com.ulp.features.tests;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamFilter;
import com.ulp.features.tests.dto.LecturerTestDtos.LecturerExamRow;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.repository.TestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.ulp.common.IConstant.ATTR_EXAMS_PAGE;
import static com.ulp.common.IConstant.ATTR_EXAM_FILTER;
import static com.ulp.common.IConstant.DEFAULT_EXAM_PAGE_SIZE;
import static com.ulp.common.IConstant.EXAM_FILTER_ALL;
import static com.ulp.common.IConstant.EXAM_SORT_RECENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for the class-tests tab filter + pagination:
 * keyword / status / type narrowing, the sort keys, page slicing and
 * sanitisation of hostile query params.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ClassExamFilterPaginationIntegrationTest {

    private static final String LECTURER = "lecturer@ulp.edu.vn";
    private static final String STUDENT = "student@ulp.edu.vn";

    /** 30 exams > two pages of 12, so page 0/1/2 all differ. */
    private static final int SEEDED = 30;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private TestRepository testRepository;

    private Long classId;
    private String listUrl;

    @BeforeEach
    void setUp() {
        User lecturer = userRepository.findByEmailIgnoreCase(LECTURER).orElseThrow();
        ClassEntity clazz = new ClassEntity("Exam filter class", lecturer.getId(),
                lecturer.getId(), null, null, null, 100);
        clazz.setCode("EXMF" + (int) (Math.random() * 900 + 100));
        classId = classRepository.saveAndFlush(clazz).getId();
        listUrl = "/lecturer/classes/" + classId + "/tests";
        seedExams(lecturer.getId());
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void unfiltered_first_page_is_capped_at_the_page_size() throws Exception {
        mockMvc.perform(get(listUrl))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists(ATTR_EXAM_FILTER));

        Page<LecturerExamRow> page = pageAt(listUrl);
        assertThat(page.getTotalElements()).isEqualTo(SEEDED);
        assertThat(page.getContent()).hasSize(DEFAULT_EXAM_PAGE_SIZE);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.isFirst()).isTrue();
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void last_page_holds_the_remainder_and_shares_no_row_with_the_first() throws Exception {
        List<Long> firstIds = idsOf(pageAt(listUrl + "?page=0"));
        Page<LecturerExamRow> last = pageAt(listUrl + "?page=2");

        assertThat(last.getContent()).hasSize(SEEDED - 2 * DEFAULT_EXAM_PAGE_SIZE);
        assertThat(last.isLast()).isTrue();
        assertThat(idsOf(last)).doesNotContainAnyElementsOf(firstIds);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void page_beyond_the_last_yields_an_empty_slice_not_an_error() throws Exception {
        Page<LecturerExamRow> page = pageAt(listUrl + "?page=99");

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(SEEDED);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void keyword_matches_the_title_case_insensitively() throws Exception {
        // Seeded titles alternate "Đề Java #n" / "Đề SQL #n"; lower-cased input
        // must still match, so the filter is not case-sensitive.
        Page<LecturerExamRow> page = pageAt(listUrl + "?q=java");

        assertThat(page.getTotalElements()).isEqualTo(SEEDED / 2);
        assertThat(page.getContent()).allMatch(r -> r.title().contains("Java"));
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void status_and_type_narrow_independently_and_combine() throws Exception {
        // Status cycles on i%2 (15 DRAFT), type on i%3 (10 MOCK); the cycles are
        // coprime so the intersection is a strict subset of either alone.
        assertThat(pageAt(listUrl + "?status=DRAFT").getTotalElements()).isEqualTo(15);
        assertThat(pageAt(listUrl + "?type=MOCK").getTotalElements()).isEqualTo(10);
        assertThat(pageAt(listUrl + "?status=DRAFT&type=MOCK").getTotalElements()).isEqualTo(5);
        assertThat(pageAt(listUrl + "?q=Java&status=DRAFT").getTotalElements()).isZero();
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void title_sort_orders_alphabetically_while_recent_sort_does_not() throws Exception {
        List<String> byTitle = pageAt(listUrl + "?sort=title").getContent().stream()
                .map(LecturerExamRow::title).toList();
        assertThat(byTitle).isSorted();

        // Recency is the default and the seeded titles descend by index, so the
        // two orderings must differ.
        List<String> byRecent = pageAt(listUrl + "?sort=recent").getContent().stream()
                .map(LecturerExamRow::title).toList();
        assertThat(byRecent).isNotEqualTo(byTitle);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void closing_sort_surfaces_the_deadline_less_exams_first() throws Exception {
        // MySQL orders NULL first on ASC; the "—" rows are expected up front.
        List<LecturerExamRow> rows = pageAt(listUrl + "?sort=closing").getContent();

        assertThat(rows.get(0).endAt()).isNull();
        assertThat(rows.get(rows.size() - 1).endAt()).isNotNull();
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void hostile_filter_params_are_sanitised_to_their_defaults() throws Exception {
        var model = mockMvc.perform(get(listUrl)
                        .param("status", "NOPE")
                        .param("type", "<script>alert(1)</script>")
                        .param("sort", "hack")
                        .param("page", "-5"))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel();

        ExamFilter filter = (ExamFilter) model.get(ATTR_EXAM_FILTER);
        assertThat(filter.status()).isEqualTo(EXAM_FILTER_ALL);
        assertThat(filter.type()).isEqualTo(EXAM_FILTER_ALL);
        assertThat(filter.sort()).isEqualTo(EXAM_SORT_RECENT);
        assertThat(filter.isEmpty()).isTrue();

        @SuppressWarnings("unchecked")
        Page<LecturerExamRow> page = (Page<LecturerExamRow>) model.get(ATTR_EXAMS_PAGE);
        assertThat(page.getNumber()).isZero();
        assertThat(page.getTotalElements()).isEqualTo(SEEDED);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(STUDENT)
    void student_cannot_reach_the_lecturer_tests_tab() throws Exception {
        mockMvc.perform(get(listUrl)).andExpect(status().isForbidden());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Fetches the exam page rendered by {@code url}. */
    @SuppressWarnings("unchecked")
    private Page<LecturerExamRow> pageAt(String url) throws Exception {
        return (Page<LecturerExamRow>) mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get(ATTR_EXAMS_PAGE);
    }

    private static List<Long> idsOf(Page<LecturerExamRow> page) {
        return page.getContent().stream().map(LecturerExamRow::id).toList();
    }

    /**
     * Seeds {@link #SEEDED} exams with title / status / type cycles chosen so
     * every filter dimension splits the set differently: title on i%2, status on
     * i%2 (inverted), type on i%3, deadline on i%5.
     */
    private void seedExams(Long lecturerId) {
        LocalDateTime base = LocalDateTime.now();
        for (int i = 0; i < SEEDED; i++) {
            Test exam = new Test(lecturerId, typeOf(i));
            exam.setTitle("Đề " + (i % 2 == 0 ? "Java" : "SQL") + " #" + (100 - i));
            exam.setClassId(classId);
            exam.setStatus(i % 2 == 0 ? Test.STATUS_PUBLISHED : Test.STATUS_DRAFT);
            exam.setTotalQuestions(i);
            exam.setEndAt(i % 5 == 0 ? null : base.plusDays(i));
            testRepository.save(exam);
        }
        testRepository.flush();
    }

    private static String typeOf(int i) {
        return switch (i % 3) {
            case 0 -> Test.TYPE_MOCK;
            case 1 -> Test.TYPE_MODULE;
            default -> Test.TYPE_PRACTICE;
        };
    }
}
