package com.ulp.features.tests.service;

import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.tests.dto.LecturerTestDtos.ClassOption;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamFilter;
import com.ulp.features.tests.dto.LecturerTestDtos.LecturerExamRow;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.repository.TestRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ulp.common.IConstant.DEFAULT_EXAM_PAGE_SIZE;
import static com.ulp.common.IConstant.EXAM_SORT_CLOSING;
import static com.ulp.common.IConstant.EXAM_SORT_TITLE;

/**
 * Read-only exam listing for the lecturer screens: the global
 * {@code /lecturer/tests} list and the class-detail "Bài test" tab, plus the
 * class picker feeding both the filter dropdown and the authoring form.
 *
 * <p>Split out of {@code LecturerExamService} (which owns the write paths) to
 * keep both files within the project's file-size guidance. Authorization is the
 * caller's responsibility — see the per-method notes.
 */
@Service
public class LecturerExamQueryService {

    private final TestRepository testRepository;
    private final ClassRepository classRepository;

    public LecturerExamQueryService(TestRepository testRepository,
                                    ClassRepository classRepository) {
        this.testRepository = testRepository;
        this.classRepository = classRepository;
    }

    /** One page of exams the lecturer owns (created or leads the class). */
    @Transactional(readOnly = true)
    public Page<LecturerExamRow> listOwned(Long userId, int page) {
        return listOwned(userId, page, ExamFilter.of(null, null, null, null));
    }

    /**
     * One page of exams the lecturer owns, narrowed by {@code filter} (title
     * keyword, status, type, class) and ordered by the filter's sort key.
     *
     * <p>Ownership is the query's own scope: only rows the lecturer created or
     * whose class they lead can match. A role that owns nothing — ADMIN has no
     * led classes — therefore gets an empty page rather than an exception, so
     * the screen degrades to an empty state instead of a 500.
     */
    @Transactional(readOnly = true)
    public Page<LecturerExamRow> listOwned(Long userId, int page, ExamFilter filter) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), DEFAULT_EXAM_PAGE_SIZE,
                sortOf(filter.sort()));
        return toRows(testRepository.searchOwnedByLecturer(userId, ledClassIds(userId),
                filter.classIdOrNull(), filter.keyword(),
                filter.statusOrNull(), filter.typeOrNull(), pageable));
    }

    /**
     * How many exams the lecturer owns, for the Library rail's "Bài test" badge.
     *
     * <p>Deliberately routed through the same unfiltered {@link #listOwned} call
     * the rail itself uses rather than a fresh count query: the ownership
     * predicate — and the {@code -1L} sentinel that keeps its JPQL {@code IN}
     * clause valid for a lecturer with no class — then stays defined in exactly
     * one place. A role that owns nothing gets {@code 0}, not an exception.
     */
    @Transactional(readOnly = true)
    public long countOwned(Long userId) {
        return listOwned(userId, 0).getTotalElements();
    }

    /**
     * One page of exams belonging to a single class. Class-level authorization
     * is the caller's responsibility: {@code ClassDetailController} gates the
     * tests tab with {@code classesService.getViewable(...)} (LECTURER-owns /
     * HEAD / ADMIN), mirroring every other class-detail tab. This method trusts
     * that gate and only queries.
     */
    @Transactional(readOnly = true)
    public Page<LecturerExamRow> listForClass(Long classId, int page) {
        return listForClass(classId, page, ExamFilter.of(null, null, null, null));
    }

    /**
     * One page of exams in a class narrowed by {@code filter} (title keyword,
     * status, type) and ordered by the filter's sort key. Authorization is the
     * caller's responsibility — see {@link #listForClass(Long, int)}.
     */
    @Transactional(readOnly = true)
    public Page<LecturerExamRow> listForClass(Long classId, int page, ExamFilter filter) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), DEFAULT_EXAM_PAGE_SIZE,
                sortOf(filter.sort()));
        return toRows(testRepository.searchByClass(classId, filter.keyword(),
                filter.statusOrNull(), filter.typeOrNull(), pageable));
    }

    /** Classes the lecturer leads (the exam class picker + filter dropdown). */
    @Transactional(readOnly = true)
    public List<ClassOption> ledClasses(Long userId) {
        List<ClassOption> options = new ArrayList<>();
        for (ClassEntity c : classRepository.findAllByLecturerId(userId)) {
            options.add(new ClassOption(c.getId(), c.getName()));
        }
        return options;
    }

    /** Maps a whitelisted sort key to its JPA {@link Sort}; unknown keys sort by recency. */
    private static Sort sortOf(String sortKey) {
        return switch (sortKey) {
            case EXAM_SORT_TITLE -> Sort.by(Sort.Direction.ASC, "title");
            // Nulls-last is left to the DB: MySQL orders NULL first on ASC, so
            // exams without a deadline surface before the scheduled ones.
            case EXAM_SORT_CLOSING -> Sort.by(Sort.Direction.ASC, "endAt");
            default -> Sort.by(Sort.Direction.DESC, "updatedAt");
        };
    }

    /** Maps a page of exams to list rows, resolving class names in one batch. */
    private Page<LecturerExamRow> toRows(Page<Test> tests) {
        Map<Long, String> classNames = resolveClassNames(tests.getContent());
        return tests.map(t -> new LecturerExamRow(t.getId(), t.getTitle(), t.getType(),
                t.getStatus(), classNames.get(t.getClassId()),
                t.getTotalQuestions() == null ? 0 : t.getTotalQuestions(), t.getEndAt()));
    }

    private List<Long> ledClassIds(Long userId) {
        List<Long> ids = new ArrayList<>();
        classRepository.findAllByLecturerId(userId).forEach(c -> ids.add(c.getId()));
        // Sentinel keeps the JPQL IN clause valid when the lecturer leads no class.
        if (ids.isEmpty()) ids.add(-1L);
        return ids;
    }

    private Map<Long, String> resolveClassNames(List<Test> tests) {
        Map<Long, String> names = new HashMap<>();
        List<Long> ids = tests.stream().map(Test::getClassId)
                .filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) return names;
        for (ClassEntity c : classRepository.findAllById(ids)) {
            names.put(c.getId(), c.getName());
        }
        return names;
    }
}
