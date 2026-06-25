package com.ulp.features.classes.repository;

import com.ulp.entities.ClassEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ClassRepository} integration test chay tren MySQL that
 * (theo {@code application.properties}). {@code @DataJpaTest} + Flyway
 * bao dam schema V1â€“V7 san sang truoc moi test.
 *
 * <p>Moi method la 1 transaction roll-back, du lieu khong ro ri sang test ke.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class ClassRepositoryTest {

    @Autowired
    private ClassRepository classRepository;

    @PersistenceContext
    private EntityManager em;

    /** Seeded lecturer id (V5__seed_test_users.sql: lecturer@ulp.edu.vn). */
    private static final long SEED_LECTURER_EMAIL_ID = lookupSeedLecturerId();

    @AfterEach
    @Rollback
    void cleanup() {
        // @Transactional + default rollback handles cleanup.
    }

    @Test
    void save_and_find_by_code_returns_correct_row() {
        ClassEntity entity = newClass("Java CB", "AAAA1", 1L);
        classRepository.saveAndFlush(entity);

        Optional<ClassEntity> found = classRepository.findByCode("AAAA1");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Java CB");
    }

    @Test
    void duplicate_code_throws_unique_violation() {
        classRepository.saveAndFlush(newClass("A", "DUP01", 1L));

        ClassEntity dup = newClass("B", "DUP01", 1L);

        assertThatThrownBy(() -> classRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_classes_code");
    }

    @Test
    void find_by_lecturer_id_filters_to_owner() {
        Long lec1 = lookupUserId("lecturer@ulp.edu.vn");
        Long lec2 = lookupUserId("head@ulp.edu.vn"); // HEAD also seeded

        long before = classRepository.findAllByLecturerIdOrderByCreatedAtDesc(lec1).size();

        classRepository.saveAndFlush(newClass("L1-A", "L1A01", lec1));
        classRepository.saveAndFlush(newClass("L1-B", "L1B01", lec1));
        classRepository.saveAndFlush(newClass("L2-A", "L2A01", lec2));

        List<ClassEntity> own = classRepository.findAllByLecturerIdOrderByCreatedAtDesc(lec1);

        // Defensive against any leaked committed rows (manual smoke etc.):
        // assert delta = 2 instead of absolute size.
        assertThat(own).hasSize((int) (before + 2));
        assertThat(own).allSatisfy(c -> assertThat(c.getLecturerId()).isEqualTo(lec1));
    }

    @Test
    void sql_restriction_filters_soft_deleted_rows() {
        ClassEntity entity = newClass("ToDelete", "DELT1", 1L);
        classRepository.saveAndFlush(entity);

        entity.softDelete();
        classRepository.saveAndFlush(entity);

        em.clear(); // force reload from DB

        Optional<ClassEntity> found = classRepository.findByCode("DELT1");

        assertThat(found).as("soft-deleted row must be excluded by @SQLRestriction").isEmpty();
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private ClassEntity newClass(String name, String code, long lecturerId) {
        ClassEntity e = new ClassEntity(name, lecturerId, lecturerId, null, null, null, 100);
        e.setCode(code);
        return e;
    }

    private Long lookupUserId(String email) {
        return (Long) em.createNativeQuery("SELECT id FROM users WHERE email = :e")
                .setParameter("e", email)
                .getSingleResult();
    }

    private static long lookupSeedLecturerId() {
        return 0L; // placeholder; actual lookup in test bodies
    }
}
