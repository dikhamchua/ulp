package com.ulp.classes.repository;

import com.ulp.classes.entity.Enrollment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Sprint 2.3 query methods added to
 * {@link EnrollmentRepository} — finding by (user, class), listing
 * a user's ACTIVE enrollments, and counting ACTIVE rows per class.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class EnrollmentRepositoryJoinQueriesTest {

    @Autowired
    private EnrollmentRepository repository;

    @PersistenceContext
    private EntityManager em;

    @Test
    void find_by_user_and_class_returns_existing_row() {
        long lecturerId = lookupUserId("lecturer@ulp.edu.vn");
        long studentId = lookupUserId("sv01@ulp.edu.vn");
        long classId = insertClass(lecturerId);
        insertEnrollment(studentId, classId, "ACTIVE");
        em.flush();
        em.clear();

        Optional<Enrollment> found = repository.findByUserIdAndClassId(studentId, classId);

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void find_by_user_and_class_returns_empty_when_no_row() {
        long lecturerId = lookupUserId("lecturer@ulp.edu.vn");
        long studentId = lookupUserId("sv01@ulp.edu.vn");
        long classId = insertClass(lecturerId);
        em.flush();
        em.clear();

        Optional<Enrollment> found = repository.findByUserIdAndClassId(studentId, classId);

        assertThat(found).isEmpty();
    }

    @Test
    void list_by_user_and_status_returns_only_active() {
        long lecturerId = lookupUserId("lecturer@ulp.edu.vn");
        long studentId = lookupUserId("sv02@ulp.edu.vn");
        long activeClass = insertClass(lecturerId);
        long removedClass = insertClass(lecturerId);
        insertEnrollment(studentId, activeClass, "ACTIVE");
        insertEnrollment(studentId, removedClass, "REMOVED");
        em.flush();
        em.clear();

        List<Enrollment> rows = repository.findAllByUserIdAndStatusOrderByJoinedAtDesc(
                studentId, Enrollment.STATUS_ACTIVE);

        // Defensive against state contamination from prior committed
        // test runs (concurrency / backfill use the same student):
        // assert only the rows whose classes we created in THIS test.
        List<Enrollment> mine = rows.stream()
                .filter(e -> e.getClassId() == activeClass || e.getClassId() == removedClass)
                .toList();
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getClassId()).isEqualTo(activeClass);
    }

    @Test
    void count_active_by_class_id_returns_active_count() {
        long lecturerId = lookupUserId("lecturer@ulp.edu.vn");
        long classId = insertClass(lecturerId);
        insertEnrollment(lookupUserId("sv01@ulp.edu.vn"), classId, "ACTIVE");
        insertEnrollment(lookupUserId("sv02@ulp.edu.vn"), classId, "ACTIVE");
        insertEnrollment(lookupUserId("sv03@ulp.edu.vn"), classId, "REMOVED");
        em.flush();
        em.clear();

        assertThat(repository.countActiveByClassId(classId)).isEqualTo(2L);
    }

    // ─────────── helpers ───────────

    private long lookupUserId(String email) {
        Number id = (Number) em.createNativeQuery("SELECT id FROM users WHERE email = :e")
                .setParameter("e", email)
                .getSingleResult();
        return id.longValue();
    }

    private long insertClass(long lecturerId) {
        String code = randomClassCode();
        em.createNativeQuery(
                        "INSERT INTO classes (code, name, lecturer_id, created_by, status, max_students, is_deleted) " +
                                "VALUES (:code, :name, :lec, :lec, 'UPCOMING', 100, 0)")
                .setParameter("code", code)
                .setParameter("name", "ERJ-" + code)
                .setParameter("lec", lecturerId)
                .executeUpdate();
        em.flush();
        Number id = (Number) em.createNativeQuery("SELECT id FROM classes WHERE code = :code")
                .setParameter("code", code)
                .getSingleResult();
        return id.longValue();
    }

    private void insertEnrollment(long userId, long classId, String status) {
        em.createNativeQuery(
                        "INSERT INTO enrollments (user_id, class_id, status, joined_via, joined_at) " +
                                "VALUES (:u, :c, :s, 'CODE', NOW())")
                .setParameter("u", userId)
                .setParameter("c", classId)
                .setParameter("s", status)
                .executeUpdate();
    }

    private static String randomClassCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(5);
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < 5; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }
}
