package com.ulp.features.subjects;

import com.ulp.features.subjects.dto.SubjectDtos.SubjectOption;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.service.SubjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the outcome of V50, which retired the per-department 'UNASSIGNED'
 * placeholder seeded by V46 and re-seeded by V49.
 *
 * <p>Queries go through {@link JdbcTemplate} rather than the repository on
 * purpose: {@code Subject} carries {@code @SQLRestriction("is_deleted = 0")},
 * so JPA cannot see the soft-deleted placeholder at all and would report a
 * vacuous pass whether or not the migration reassigned anything.
 */
@SpringBootTest
@Transactional
class UnassignedSubjectRetirementIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SubjectService subjectService;

    private long count(String sql) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }

    @Test
    void no_live_placeholder_remains_for_departments_with_a_real_subject() {
        // A live placeholder is only tolerated where the department has no real
        // subject to inherit its rows — V50 skips exactly those departments.
        long stragglers = count("""
                SELECT COUNT(*)
                  FROM subjects ph
                 WHERE ph.code = 'UNASSIGNED'
                   AND ph.is_deleted = 0
                   AND EXISTS (SELECT 1 FROM subjects r
                                WHERE r.department_id = ph.department_id
                                  AND r.is_deleted = 0
                                  AND r.is_active = 1
                                  AND r.code <> 'UNASSIGNED')
                """);

        assertThat(stragglers).isZero();
    }

    @Test
    void a_surviving_placeholder_is_never_active() {
        long activePlaceholders = count("""
                SELECT COUNT(*) FROM subjects
                 WHERE code = 'UNASSIGNED' AND is_deleted = 0 AND is_active = 1
                """);

        assertThat(activePlaceholders).isZero();
    }

    @Test
    void no_class_points_at_a_retired_placeholder() {
        long orphans = count("""
                SELECT COUNT(*)
                  FROM classes c
                  JOIN subjects s ON s.id = c.subject_id
                 WHERE s.code = 'UNASSIGNED' AND s.is_deleted = 1
                """);

        assertThat(orphans).isZero();
    }

    @Test
    void no_bank_item_points_at_a_retired_placeholder() {
        // question_bank_items.subject_id is NOT NULL since V49, so reassignment
        // was the only legal move — this also proves nothing was nulled instead.
        long orphans = count("""
                SELECT COUNT(*)
                  FROM question_bank_items q
                  JOIN subjects s ON s.id = q.subject_id
                 WHERE s.code = 'UNASSIGNED' AND s.is_deleted = 1
                """);

        assertThat(orphans).isZero();

        long nulled = count("SELECT COUNT(*) FROM question_bank_items WHERE subject_id IS NULL");
        assertThat(nulled).isZero();
    }

    @Test
    void reassigned_bank_items_carry_no_stale_chapter() {
        // V50 cleared chapter_id while moving items: a chapter of the placeholder
        // would otherwise describe a different subject than its item.
        long mismatched = count("""
                SELECT COUNT(*)
                  FROM question_bank_items q
                  JOIN subject_chapters ch ON ch.id = q.chapter_id
                 WHERE ch.subject_id <> q.subject_id
                """);

        assertThat(mismatched).isZero();
    }

    @Test
    void active_options_never_contain_the_placeholder() {
        List<SubjectOption> options = subjectService.listActiveOptions();

        assertThat(options).isNotEmpty();
        assertThat(options)
                .noneMatch(o -> Subject.CODE_UNASSIGNED.equalsIgnoreCase(o.code()));
        assertThat(options).noneMatch(o -> o.label().contains("Chưa phân môn"));
    }
}
