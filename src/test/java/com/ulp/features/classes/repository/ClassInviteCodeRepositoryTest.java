package com.ulp.features.classes.repository;

import com.ulp.entities.ClassInviteCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository integration test for {@link ClassInviteCodeRepository}
 * running against the real MySQL schema (V1â€“V12 applied via
 * Flyway). Each test runs inside a rolled-back transaction.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class ClassInviteCodeRepositoryTest {

    @Autowired
    private ClassInviteCodeRepository repository;

    @PersistenceContext
    private EntityManager em;

    @Test
    void find_by_class_id_and_type_returns_active_only() {
        long lecturerId = lookupUserId("lecturer@ulp.edu.vn");
        long classId = insertClass(lecturerId);

        ClassInviteCode active = new ClassInviteCode(classId, "ACT" + uniqueShortSuffix(), "CODE", lecturerId);
        ClassInviteCode disabled = new ClassInviteCode(classId, "OLD" + uniqueShortSuffix(), "CODE", lecturerId);
        disabled.disable();
        repository.saveAndFlush(disabled);
        repository.saveAndFlush(active);

        Optional<ClassInviteCode> found =
                repository.findByClassIdAndTypeAndActiveTrue(classId, "CODE");

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo(active.getCode());
        assertThat(found.get().isActive()).isTrue();
    }

    @Test
    void find_by_code_and_active_returns_match_only_when_active() {
        long lecturerId = lookupUserId("lecturer@ulp.edu.vn");
        long classId = insertClass(lecturerId);
        String value = "AB" + uniqueShortSuffix();

        ClassInviteCode token = new ClassInviteCode(classId, value, "CODE", lecturerId);
        repository.saveAndFlush(token);

        assertThat(repository.findByCodeAndActiveTrue(value)).isPresent();

        token.disable();
        repository.saveAndFlush(token);
        em.flush();
        em.clear();

        assertThat(repository.findByCodeAndActiveTrue(value)).isEmpty();
        assertThat(repository.findByCode(value)).isPresent();
    }

    @Test
    void find_by_code_for_update_returns_active_row() {
        long lecturerId = lookupUserId("lecturer@ulp.edu.vn");
        long classId = insertClass(lecturerId);
        String value = "CD" + uniqueShortSuffix();
        repository.saveAndFlush(new ClassInviteCode(classId, value, "CODE", lecturerId));

        Optional<ClassInviteCode> locked = repository.findByCodeForUpdate(value);

        assertThat(locked).isPresent();
        assertThat(locked.get().getCode()).isEqualTo(value);
    }

    @Test
    void duplicate_code_violates_unique_index() {
        long lecturerId = lookupUserId("lecturer@ulp.edu.vn");
        long classId = insertClass(lecturerId);
        String value = "DUP" + uniqueShortSuffix();

        repository.saveAndFlush(new ClassInviteCode(classId, value, "CODE", lecturerId));

        assertThatThrownBy(() ->
                repository.saveAndFlush(new ClassInviteCode(classId, value, "CODE", lecturerId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("idx_ic_code");
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
                .setParameter("name", "ICR-" + code)
                .setParameter("lec", lecturerId)
                .executeUpdate();
        em.flush();
        Number id = (Number) em.createNativeQuery("SELECT id FROM classes WHERE code = :code")
                .setParameter("code", code)
                .getSingleResult();
        return id.longValue();
    }

    private static String randomClassCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(5);
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < 5; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static String uniqueShortSuffix() {
        // 4-char random suffix â†’ combined with prefix yields <=10 chars
        // so we fit comfortably under the column length 20.
        return Long.toString(System.nanoTime() % 100000, 36).toUpperCase();
    }
}
