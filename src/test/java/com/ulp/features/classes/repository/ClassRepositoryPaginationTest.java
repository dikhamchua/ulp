package com.ulp.features.classes.repository;

import com.ulp.entities.ClassEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the paginated query variants on {@link ClassRepository}
 * added by the {@code perf-services-cache-and-principal} change.
 *
 * <p>Covers spec scenarios on {@code specs/lecturer-classes/spec.md}:
 * <ul>
 *   <li>LECTURER pagination delta (own classes only, page size honoured).</li>
 *   <li>Soft-deleted rows are excluded from the paginated result.</li>
 * </ul>
 *
 * <p>Runs against the real MySQL database (Flyway-managed schema) inside a
 * rolled-back transaction so seed users persist but test data does not leak.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class ClassRepositoryPaginationTest {

    @Autowired
    private ClassRepository classRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    void find_all_by_lecturer_id_paginates_and_filters_to_owner() {
        Long lecturerId = lookupUserId("lecturer@ulp.edu.vn");
        long before = classRepository.findAllByLecturerId(lecturerId,
                PageRequest.of(0, 1000)).getTotalElements();

        // Seed 25 classes for the lecturer + 1 noise class for someone else.
        for (int i = 0; i < 25; i++) {
            classRepository.saveAndFlush(newClass(
                    "PG-Own-" + i, randomCode(), lecturerId));
        }
        Long noiseLecturer = lookupUserId("head@ulp.edu.vn");
        classRepository.saveAndFlush(newClass("PG-Noise", randomCode(), noiseLecturer));

        // Page 0 of size 20 â†’ 20 rows; page 1 of size 20 â†’ 5 rows.
        Page<ClassEntity> page0 = classRepository.findAllByLecturerId(
                lecturerId, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
        Page<ClassEntity> page1 = classRepository.findAllByLecturerId(
                lecturerId, PageRequest.of(1, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page0.getContent()).hasSize(20);
        assertThat(page0.getTotalElements()).isEqualTo(before + 25);
        // 25 - 20 = 5 remaining on page 1 (plus any pre-existing leaked rows
        // that would also tag onto this lecturer's count — assert >= 5).
        assertThat(page1.getContent().size()).isGreaterThanOrEqualTo(5);

        // Every result must belong to this lecturer (never the noise row).
        assertThat(page0.getContent())
                .allSatisfy(c -> assertThat(c.getLecturerId()).isEqualTo(lecturerId));
        assertThat(page1.getContent())
                .allSatisfy(c -> assertThat(c.getLecturerId()).isEqualTo(lecturerId));
    }

    @Test
    void find_all_by_lecturer_id_excludes_soft_deleted_rows() {
        Long lecturerId = lookupUserId("lecturer@ulp.edu.vn");

        ClassEntity live = newClass("PG-Live", randomCode(), lecturerId);
        ClassEntity gone = newClass("PG-Gone", randomCode(), lecturerId);
        classRepository.saveAndFlush(live);
        classRepository.saveAndFlush(gone);

        gone.softDelete();
        classRepository.saveAndFlush(gone);

        em.flush();
        em.clear();

        Page<ClassEntity> page = classRepository.findAllByLecturerId(
                lecturerId, PageRequest.of(0, 1000));

        assertThat(page.getContent())
                .extracting(ClassEntity::getName)
                .contains("PG-Live")
                .doesNotContain("PG-Gone");
    }

    @Test
    void find_all_by_paginates_across_all_lecturers_for_head_admin() {
        Long lec1 = lookupUserId("lecturer@ulp.edu.vn");
        Long lec2 = lookupUserId("head@ulp.edu.vn");

        // Inject a small mixed set so HEAD/ADMIN view sees both lecturers.
        classRepository.saveAndFlush(newClass("PG-All-A", randomCode(), lec1));
        classRepository.saveAndFlush(newClass("PG-All-B", randomCode(), lec2));

        Page<ClassEntity> page = classRepository.findAllBy(
                PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getContent()).isNotEmpty();
        // The two we just inserted are visible somewhere in the head/admin view.
        assertThat(page.getContent())
                .extracting(ClassEntity::getName)
                .contains("PG-All-A", "PG-All-B");
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private ClassEntity newClass(String name, String code, long lecturerId) {
        ClassEntity e = new ClassEntity(name, lecturerId, lecturerId, null, null, null, 100);
        e.setCode(code);
        return e;
    }

    private static String randomCode() {
        // 5 uppercase chars from the project's code alphabet [A-HJ-NP-Z2-9]
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(5);
        String src = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        for (int i = 0; sb.length() < 5 && i < src.length(); i++) {
            char c = src.charAt(i);
            if (alphabet.indexOf(c) >= 0) sb.append(c);
        }
        while (sb.length() < 5) sb.append('A');
        return sb.toString();
    }

    private Long lookupUserId(String email) {
        Object id = em.createNativeQuery("SELECT id FROM users WHERE email = :e")
                .setParameter("e", email)
                .getSingleResult();
        return ((Number) id).longValue();
    }
}
