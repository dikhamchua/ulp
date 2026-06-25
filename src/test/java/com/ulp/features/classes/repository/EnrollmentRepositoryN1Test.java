package com.ulp.features.classes.repository;

import com.ulp.entities.Enrollment;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link EnrollmentRepository#findAllByClassIdAndStatusOrderByJoinedAtDesc}
 * loads the associated {@code user} in the SAME SQL statement (JOIN FETCH) instead
 * of triggering one additional SELECT per row (the classic N+1 problem).
 *
 * <p>Test relies on Hibernate {@link Statistics#getPrepareStatementCount()} —
 * after seeding N enrollments and clearing the persistence context + statistics,
 * the count of prepared statements for the find + property access must be 1.
 *
 * <p>Runs against the real MySQL configured in {@code application.properties}
 * (same pattern as {@link ClassRepositoryTest}). Hibernate statistics are turned
 * on only for this test class via {@link TestPropertySource}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Transactional
class EnrollmentRepositoryN1Test {

    private static final int ENROLLMENT_COUNT = 5;

    @Autowired
    private EnrollmentRepository enrollmentRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    void find_all_by_class_status_uses_join_fetch_for_user() {
        // â”€â”€ Seed: 1 fresh class + N enrollments using already-seeded students â”€â”€
        long lecturerId = lookupUserId("lecturer@ulp.edu.vn");
        long classId = insertClass(lecturerId);

        long[] studentIds = new long[ENROLLMENT_COUNT];
        for (int i = 0; i < ENROLLMENT_COUNT; i++) {
            studentIds[i] = lookupUserId(String.format("sv0%d@ulp.edu.vn", i + 1));
            insertEnrollment(studentIds[i], classId);
        }
        em.flush();
        em.clear();

        // â”€â”€ Reset statistics so we only count the queries we care about â”€â”€
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.clear();
        long before = stats.getPrepareStatementCount();

        // â”€â”€ Execute + touch the user fields that would normally trigger N+1 â”€â”€
        List<Enrollment> rows = enrollmentRepository
                .findAllByClassIdAndStatusOrderByJoinedAtDesc(classId, Enrollment.STATUS_ACTIVE);

        // Force property access so a lazy/EAGER-without-fetch impl would have to SELECT user
        for (Enrollment e : rows) {
            assertThat(e.getUser()).isNotNull();
            assertThat(e.getUser().getFullName()).isNotBlank();
            assertThat(e.getUser().getEmail()).isNotBlank();
        }

        long after = stats.getPrepareStatementCount();
        long executed = after - before;

        assertThat(rows).hasSize(ENROLLMENT_COUNT);
        assertThat(executed)
                .as("Expected a single JOIN FETCH query, but Hibernate issued %d statements", executed)
                .isEqualTo(1L);
    }

    @Test
    void find_all_returns_user_initialized_with_accessible_fields() {
        // Smaller smoke check: even without measuring statements, ensure user fields are usable.
        long lecturerId = lookupUserId("lecturer@ulp.edu.vn");
        long classId = insertClass(lecturerId);
        long studentId = lookupUserId("sv01@ulp.edu.vn");
        insertEnrollment(studentId, classId);
        em.flush();
        em.clear();

        List<Enrollment> rows = enrollmentRepository
                .findAllByClassIdAndStatusOrderByJoinedAtDesc(classId, Enrollment.STATUS_ACTIVE);

        assertThat(rows).hasSize(1);
        Enrollment e = rows.get(0);
        // These must not throw — JOIN FETCH guarantees user is initialized.
        assertThat(e.getUser().getId()).isEqualTo(studentId);
        assertThat(e.getUser().getFullName()).isNotBlank();
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private long lookupUserId(String email) {
        Number id = (Number) em.createNativeQuery("SELECT id FROM users WHERE email = :e")
                .setParameter("e", email)
                .getSingleResult();
        return id.longValue();
    }

    /**
     * Insert a class via native SQL so we don't depend on ClassRepository internals.
     * Returns the generated id.
     */
    private long insertClass(long lecturerId) {
        String randomCode = randomCode();
        em.createNativeQuery(
                        "INSERT INTO classes (code, name, lecturer_id, created_by, status, max_students, is_deleted) " +
                                "VALUES (:code, :name, :lec, :lec, 'UPCOMING', 100, 0)")
                .setParameter("code", randomCode)
                .setParameter("name", "N1Test-" + randomCode)
                .setParameter("lec", lecturerId)
                .executeUpdate();
        em.flush();
        Number id = (Number) em.createNativeQuery("SELECT id FROM classes WHERE code = :code")
                .setParameter("code", randomCode)
                .getSingleResult();
        return id.longValue();
    }

    private void insertEnrollment(long userId, long classId) {
        em.createNativeQuery(
                        "INSERT INTO enrollments (user_id, class_id, status, joined_via, joined_at) " +
                                "VALUES (:u, :c, 'ACTIVE', 'MANUAL', NOW())")
                .setParameter("u", userId)
                .setParameter("c", classId)
                .executeUpdate();
    }

    private static String randomCode() {
        // 5 chars upper-case alnum, matches V7 pattern (validation lives at code-generator).
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(5);
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < 5; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }
}
