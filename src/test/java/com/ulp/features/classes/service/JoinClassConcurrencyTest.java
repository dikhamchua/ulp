package com.ulp.features.classes.service;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.ClassInviteCode;
import com.ulp.features.classes.repository.ClassInviteCodeRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.classes.service.invites.InviteCodeService;
import com.ulp.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency stress for the approval pipeline. Two threads race to approve
 * the last capacity slot ({@code max_students=1}); capacity re-check under
 * transaction must yield exactly one ACTIVE enrollment.
 *
 * <p>The test is {@code @Commit} by necessity: the racing threads use their
 * own JDBC connections and cannot see rows the test thread has not committed.
 * Nothing rolls back, so seeded rows are removed by the {@link #cleanUp()}
 * {@code @AfterEach} hook, which runs on failure as well as on success.
 */
@SpringBootTest
class JoinClassConcurrencyTest {

    private static final int RUNS = 10;

    /** Name prefix identifying every class this test seeds. */
    private static final String CLASS_NAME_PREFIX = "Race-";

    @Autowired private JoinClassService joinClassService;
    @Autowired private InviteCodeService inviteCodeService;
    @Autowired private ClassRepository classRepository;
    @Autowired private ClassInviteCodeRepository inviteRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate tx;

    @PersistenceContext private EntityManager em;

    @Test
    @Commit
    void two_threads_race_on_last_capacity_slot_yields_one_active() throws Exception {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        User a = userRepository.findByEmailIgnoreCase("sv01@ulp.edu.vn").orElseThrow();
        User b = userRepository.findByEmailIgnoreCase("sv02@ulp.edu.vn").orElseThrow();

        for (int iter = 0; iter < RUNS; iter++) {
            ClassEntity clazz = tx.execute(s -> {
                ClassEntity c = new ClassEntity(CLASS_NAME_PREFIX + System.nanoTime(),
                        lecturer.getId(), lecturer.getId(), null, null, null, 1);
                c.setCode(uniqueClassCode());
                return classRepository.saveAndFlush(c);
            });
            inviteCodeService.provisionDefaults(clazz.getId(), lecturer.getId());
            ClassInviteCode token = inviteRepository
                    .findByClassIdAndTypeAndActiveTrue(clazz.getId(), "CODE").orElseThrow();

            // Two PENDING requests share the single capacity slot.
            tx.executeWithoutResult(s -> {
                em.createNativeQuery(
                                "INSERT INTO enrollments(user_id, class_id, status, joined_via, invite_code_id) "
                                        + "VALUES (:u1, :c, 'PENDING', 'CODE', :inv), "
                                        + "(:u2, :c, 'PENDING', 'CODE', :inv)")
                        .setParameter("u1", a.getId())
                        .setParameter("u2", b.getId())
                        .setParameter("c", clazz.getId())
                        .setParameter("inv", token.getId())
                        .executeUpdate();
            });

            final Long classId = clazz.getId();
            final Long ownerId = lecturer.getId();

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger rejections = new AtomicInteger();

            Thread t1 = new Thread(() -> {
                try {
                    start.await();
                    try {
                        tx.execute(s -> {
                            joinClassService.approve(classId, a.getId(), ownerId, Role.LECTURER);
                            return null;
                        });
                        successes.incrementAndGet();
                    } catch (RuntimeException ex) {
                        rejections.incrementAndGet();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread t2 = new Thread(() -> {
                try {
                    start.await();
                    try {
                        tx.execute(s -> {
                            joinClassService.approve(classId, b.getId(), ownerId, Role.LECTURER);
                            return null;
                        });
                        successes.incrementAndGet();
                    } catch (RuntimeException ex) {
                        rejections.incrementAndGet();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });

            t1.start();
            t2.start();
            start.countDown();
            t1.join();
            t2.join();

            long activeEnrollments = enrollmentRepository.countActiveByClassId(classId);
            long useCount = inviteRepository.findById(token.getId()).orElseThrow().getUseCount();

            assertThat(successes.get())
                    .as("iteration %d: expected one approve success, got %d", iter, successes.get())
                    .isEqualTo(1);
            assertThat(rejections.get())
                    .as("iteration %d: expected one capacity rejection", iter)
                    .isEqualTo(1);
            assertThat(activeEnrollments)
                    .as("iteration %d: exactly one ACTIVE enrollment", iter)
                    .isEqualTo(1L);
            assertThat(useCount)
                    .as("iteration %d: use_count increments once on approve", iter)
                    .isEqualTo(1L);

            // Each iteration expects a clean slate, so drop this class now.
            // The @AfterEach net below only covers rows an abort leaves behind.
            deleteSeededClasses();
        }
    }

    /**
     * Removes every class this test seeds, keyed by name prefix rather than by
     * id, so leftovers from earlier aborted runs are reclaimed too.
     *
     * <p>Runs even when the test fails, which is what stops {@code @Commit}
     * rows from accumulating in the developer database. Failures here are
     * swallowed so a cleanup problem cannot mask the real assertion failure.
     */
    @AfterEach
    void cleanUp() {
        try {
            deleteSeededClasses();
        } catch (RuntimeException ex) {
            // Never let cleanup replace the assertion error under diagnosis.
            System.err.println("[JoinClassConcurrencyTest] cleanup failed: " + ex);
        }
    }

    /** Deletes seeded classes child-rows-first to satisfy FK constraints. */
    private void deleteSeededClasses() {
        tx.executeWithoutResult(s -> {
            String subQuery = "(SELECT id FROM (SELECT id FROM classes "
                    + "WHERE name LIKE :prefix) AS c)";
            // activity_enrollments cascades from enrollments; sections and
            // class_invite_codes cascade from classes, but are removed
            // explicitly so the delete order holds if a cascade is dropped.
            String[] statements = {
                    "DELETE FROM notifications WHERE reference_type = 'CLASS' "
                            + "AND reference_id IN " + subQuery,
                    "DELETE FROM enrollments WHERE class_id IN " + subQuery,
                    "DELETE FROM activity_classes WHERE class_id IN " + subQuery,
                    "DELETE FROM sections WHERE class_id IN " + subQuery,
                    "DELETE FROM class_invite_codes WHERE class_id IN " + subQuery,
                    "DELETE FROM classes WHERE name LIKE :prefix",
            };
            for (String sql : statements) {
                em.createNativeQuery(sql)
                        .setParameter("prefix", CLASS_NAME_PREFIX + "%")
                        .executeUpdate();
            }
        });
    }

    private static String uniqueClassCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.util.Random rnd = new java.util.Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }
}
