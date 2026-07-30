package com.ulp.features.tests;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.tests.dto.LecturerTestDtos.ClassOption;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamFilter;
import com.ulp.features.tests.dto.LecturerTestDtos.LecturerExamRow;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.repository.TestRepository;
import com.ulp.features.tests.service.LecturerExamQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.ulp.common.IConstant.ATTR_EXAMS_PAGE;
import static com.ulp.common.IConstant.ATTR_EXAM_CLASS_OPTIONS;
import static com.ulp.common.IConstant.ATTR_EXAM_FILTER;
import static com.ulp.common.IConstant.ATTR_PAGER_PARAMS;
import static com.ulp.common.IConstant.DEFAULT_EXAM_PAGE_SIZE;
import static com.ulp.common.IConstant.EXAM_FILTER_ALL;
import static com.ulp.common.IConstant.EXAM_SORT_RECENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for the owner-scoped exam list at
 * {@code /lecturer/tests}: keyword / status / type / class narrowing, the sort
 * keys, page slicing, sanitisation of hostile query params, and the two
 * authorization outcomes (STUDENT forbidden, ADMIN empty rather than 500).
 *
 * <p>Unlike the class-scoped tab, this list spans everything the lecturer owns,
 * so demo exams seeded by migrations may already be present. Counts are
 * therefore asserted as deltas against a baseline, and every seeded title
 * carries {@link #TAG} so keyword-scoped counts stay exact.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LecturerOwnedExamFilterIntegrationTest {

    private static final String LECTURER = "lecturer@ulp.edu.vn";
    private static final String STUDENT = "student@ulp.edu.vn";
    private static final String ADMIN = "admin@ulp.edu.vn";
    private static final String HEAD = "head@ulp.edu.vn";

    private static final String LIST_URL = "/lecturer/tests";

    /** Marks every exam this test seeds, isolating it from migration demo data. */
    private static final String TAG = "ZQX";

    /** 30 exams > two pages of 12, so page 0/1/2 all differ. */
    private static final int SEEDED = 30;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private TestRepository testRepository;
    @Autowired private LecturerExamQueryService examQueryService;

    private Long classA;
    private Long classB;

    /** Exams the lecturer already owned before this test seeded anything. */
    private long baseline;

    @BeforeEach
    void setUp() {
        User lecturer = userRepository.findByEmailIgnoreCase(LECTURER).orElseThrow();
        // Baseline is read straight from the repository, not through MockMvc: the
        // security context differs per test (LECTURER / ADMIN) and the count must
        // not depend on which one is active while @BeforeEach runs.
        baseline = examQueryService.listOwned(lecturer.getId(), 0).getTotalElements();
        classA = createClass(lecturer.getId(), "Owned exams A");
        classB = createClass(lecturer.getId(), "Owned exams B");
        seedExams(lecturer.getId());
    }

    // ── Pagination ──────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void tagged_first_page_is_capped_at_the_page_size() throws Exception {
        mockMvc.perform(get(LIST_URL))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists(ATTR_EXAM_FILTER, ATTR_EXAM_CLASS_OPTIONS));

        Page<LecturerExamRow> page = pageAt(LIST_URL + "?q=" + TAG);
        assertThat(page.getTotalElements()).isEqualTo(SEEDED);
        assertThat(page.getContent()).hasSize(DEFAULT_EXAM_PAGE_SIZE);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.isFirst()).isTrue();
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void the_unfiltered_total_grows_by_exactly_the_seeded_count() throws Exception {
        assertThat(totalAt(LIST_URL)).isEqualTo(baseline + SEEDED);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void last_page_holds_the_remainder_and_shares_no_row_with_the_first() throws Exception {
        String tagged = LIST_URL + "?q=" + TAG;
        List<Long> firstIds = idsOf(pageAt(tagged + "&page=0"));
        Page<LecturerExamRow> last = pageAt(tagged + "&page=2");

        assertThat(last.getContent()).hasSize(SEEDED - 2 * DEFAULT_EXAM_PAGE_SIZE);
        assertThat(last.isLast()).isTrue();
        assertThat(idsOf(last)).doesNotContainAnyElementsOf(firstIds);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void page_beyond_the_last_yields_an_empty_slice_not_an_error() throws Exception {
        Page<LecturerExamRow> page = pageAt(LIST_URL + "?q=" + TAG + "&page=99");

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(SEEDED);
    }

    // ── Filter dimensions ───────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void keyword_matches_the_title_case_insensitively() throws Exception {
        // Seeded titles alternate "<TAG> Java #n" / "<TAG> SQL #n"; lower-cased
        // input must still match, so the filter is not case-sensitive.
        Page<LecturerExamRow> page = pageWith(get(LIST_URL)
                .param("q", TAG.toLowerCase() + " java"));

        assertThat(page.getTotalElements()).isEqualTo(SEEDED / 2);
        assertThat(page.getContent()).allMatch(r -> r.title().contains("Java"));
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void status_and_type_narrow_independently_and_combine() throws Exception {
        // Status cycles on i%2 (15 DRAFT), type on i%3 (10 MOCK); the cycles are
        // coprime so the intersection is a strict subset of either alone.
        String tagged = LIST_URL + "?q=" + TAG;
        assertThat(totalAt(tagged + "&status=DRAFT")).isEqualTo(15);
        assertThat(totalAt(tagged + "&type=MOCK")).isEqualTo(10);
        assertThat(totalAt(tagged + "&status=DRAFT&type=MOCK")).isEqualTo(5);
        // Java titles are all PUBLISHED, so this combination must match nothing.
        assertThat(pageWith(get(LIST_URL).param("q", TAG + " Java").param("status", "DRAFT"))
                .getTotalElements()).isZero();
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void class_narrows_independently_and_combines_with_the_other_dimensions() throws Exception {
        // Class cycles on i%2: 15 exams in A, 15 in B. Status also cycles on i%2
        // and is aligned with it, so A holds every PUBLISHED row and no DRAFT one.
        String tagged = LIST_URL + "?q=" + TAG;
        assertThat(totalAt(tagged + "&classId=" + classA)).isEqualTo(15);
        assertThat(totalAt(tagged + "&classId=" + classB)).isEqualTo(15);
        assertThat(totalAt(tagged + "&classId=" + classA + "&status=PUBLISHED")).isEqualTo(15);
        assertThat(totalAt(tagged + "&classId=" + classA + "&status=DRAFT")).isZero();

        Page<LecturerExamRow> inA = pageAt(tagged + "&classId=" + classA);
        assertThat(inA.getContent()).allMatch(r -> "Owned exams A".equals(r.className()));
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void a_class_only_filter_is_not_empty_so_the_reset_link_shows() throws Exception {
        ExamFilter filter = filterAt(LIST_URL + "?classId=" + classA);

        assertThat(filter.classId()).isEqualTo(classA);
        assertThat(filter.isEmpty()).isFalse();
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void the_class_dropdown_offers_every_class_the_lecturer_leads() throws Exception {
        var model = modelAt(LIST_URL);

        @SuppressWarnings("unchecked")
        List<ClassOption> options = (List<ClassOption>) model.get(ATTR_EXAM_CLASS_OPTIONS);
        assertThat(options).extracting(ClassOption::id).contains(classA, classB);
    }

    // ── Sorting ─────────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void title_sort_orders_alphabetically_while_recent_sort_does_not() throws Exception {
        String tagged = LIST_URL + "?q=" + TAG;
        List<String> byTitle = titlesAt(tagged + "&sort=title");
        assertThat(byTitle).isSorted();

        // Recency is the default and the seeded titles descend by index, so the
        // two orderings must differ.
        assertThat(titlesAt(tagged + "&sort=recent")).isNotEqualTo(byTitle);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void closing_sort_surfaces_the_deadline_less_exams_first() throws Exception {
        // MySQL orders NULL first on ASC; the "—" rows are expected up front.
        List<LecturerExamRow> rows = pageAt(LIST_URL + "?q=" + TAG + "&sort=closing").getContent();

        assertThat(rows.get(0).endAt()).isNull();
        assertThat(rows.get(rows.size() - 1).endAt()).isNotNull();
    }

    // ── Sanitisation ────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void hostile_filter_params_are_sanitised_to_their_defaults() throws Exception {
        var model = mockMvc.perform(get(LIST_URL)
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
        assertThat(filter.classId()).isNull();
        assertThat(filter.isEmpty()).isTrue();

        @SuppressWarnings("unchecked")
        Page<LecturerExamRow> page = (Page<LecturerExamRow>) model.get(ATTR_EXAMS_PAGE);
        assertThat(page.getNumber()).isZero();
        assertThat(page.getTotalElements()).isEqualTo(baseline + SEEDED);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void a_class_the_lecturer_does_not_lead_degrades_to_all_classes() throws Exception {
        // A foreign class must never widen scope: the id is dropped, not honoured.
        Long foreign = createForeignClass();

        ExamFilter filter = filterAt(LIST_URL + "?classId=" + foreign);

        assertThat(filter.classId()).isNull();
        assertThat(filter.isEmpty()).isTrue();
        assertThat(totalAt(LIST_URL + "?classId=" + foreign)).isEqualTo(baseline + SEEDED);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void a_non_numeric_class_id_degrades_to_all_classes_instead_of_500() throws Exception {
        // Binding classId as Long would raise a type-mismatch that the catch-all
        // advice renders as a 500; the param is text and sanitised instead.
        var model = mockMvc.perform(get(LIST_URL).param("classId", "abc"))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel();

        assertThat(((ExamFilter) model.get(ATTR_EXAM_FILTER)).classId()).isNull();

        @SuppressWarnings("unchecked")
        Page<LecturerExamRow> page = (Page<LecturerExamRow>) model.get(ATTR_EXAMS_PAGE);
        assertThat(page.getTotalElements()).isEqualTo(baseline + SEEDED);
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void the_pager_params_carry_the_sanitised_filter_without_the_page() throws Exception {
        var model = modelAt(LIST_URL + "?q=" + TAG + "&status=DRAFT&classId=" + classB + "&page=1");

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) model.get(ATTR_PAGER_PARAMS);
        assertThat(params).containsEntry("q", TAG)
                .containsEntry("status", "DRAFT")
                .containsEntry("type", EXAM_FILTER_ALL)
                .containsEntry("sort", EXAM_SORT_RECENT)
                .containsEntry("classId", classB)
                .doesNotContainKey("page");
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(LECTURER)
    void the_pager_params_omit_the_class_when_the_filter_spans_every_class() throws Exception {
        // Map.of would reject a null value, so the key must be absent instead.
        var model = modelAt(LIST_URL);

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) model.get(ATTR_PAGER_PARAMS);
        assertThat(params).doesNotContainKey("classId");
    }

    // ── Authorization ───────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    @WithUserDetails(STUDENT)
    void student_cannot_reach_the_lecturer_exam_list() throws Exception {
        mockMvc.perform(get(LIST_URL)).andExpect(status().isForbidden());
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(ADMIN)
    void admin_sees_an_empty_list_instead_of_a_server_error() throws Exception {
        // The query is owner-scoped (created_by / led classes with a -1 sentinel),
        // so an account owning nothing gets an empty page — never an exception.
        // This is the anti-regression proof for the question-bank 500 bug class.
        Page<LecturerExamRow> page = pageAt(LIST_URL);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        assertThat(filterAt(LIST_URL).isEmpty()).isTrue();
    }

    @org.junit.jupiter.api.Test
    @WithUserDetails(ADMIN)
    void admin_gets_an_empty_class_dropdown_rather_than_every_class() throws Exception {
        var model = modelAt(LIST_URL);

        @SuppressWarnings("unchecked")
        List<ClassOption> options = (List<ClassOption>) model.get(ATTR_EXAM_CLASS_OPTIONS);
        assertThat(options).isEmpty();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Map<String, Object> modelAt(String url) throws Exception {
        return modelWith(get(url));
    }

    /**
     * Renders a request built with {@code .param(...)}. Preferred over a raw
     * query string whenever a value contains a space: MockMvc does not decode
     * {@code +} back into a space, so "a+b" would be matched literally.
     */
    private Map<String, Object> modelWith(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel();
    }

    /** Fetches the exam page rendered by {@code url}. */
    @SuppressWarnings("unchecked")
    private Page<LecturerExamRow> pageAt(String url) throws Exception {
        return (Page<LecturerExamRow>) modelAt(url).get(ATTR_EXAMS_PAGE);
    }

    /** Fetches the exam page for a request built with {@code .param(...)}. */
    @SuppressWarnings("unchecked")
    private Page<LecturerExamRow> pageWith(MockHttpServletRequestBuilder request) throws Exception {
        return (Page<LecturerExamRow>) modelWith(request).get(ATTR_EXAMS_PAGE);
    }

    private ExamFilter filterAt(String url) throws Exception {
        return (ExamFilter) modelAt(url).get(ATTR_EXAM_FILTER);
    }

    private long totalAt(String url) throws Exception {
        return pageAt(url).getTotalElements();
    }

    private List<String> titlesAt(String url) throws Exception {
        return pageAt(url).getContent().stream().map(LecturerExamRow::title).toList();
    }

    private static List<Long> idsOf(Page<LecturerExamRow> page) {
        return page.getContent().stream().map(LecturerExamRow::id).toList();
    }

    private Long createClass(Long lecturerId, String name) {
        ClassEntity clazz = new ClassEntity(name, lecturerId, lecturerId, null, null, null, 100);
        clazz.setCode("OWNX" + (int) (Math.random() * 900_000 + 100_000));
        return classRepository.saveAndFlush(clazz).getId();
    }

    /** A class led by the seeded HEAD account, i.e. not by the acting lecturer. */
    private Long createForeignClass() {
        Long headId = userRepository.findByEmailIgnoreCase(HEAD).orElseThrow().getId();
        return createClass(headId, "Foreign class");
    }

    /**
     * Seeds {@link #SEEDED} exams with cycles chosen so every filter dimension
     * splits the set differently: title on i%2, status on i%2, class on i%2,
     * type on i%3, deadline on i%5. Every title starts with {@link #TAG} so a
     * keyword filter isolates them from migration demo data.
     */
    private void seedExams(Long lecturerId) {
        LocalDateTime base = LocalDateTime.now();
        for (int i = 0; i < SEEDED; i++) {
            Test exam = new Test(lecturerId, typeOf(i));
            exam.setTitle(TAG + " " + (i % 2 == 0 ? "Java" : "SQL") + " #" + (100 - i));
            exam.setClassId(i % 2 == 0 ? classA : classB);
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
