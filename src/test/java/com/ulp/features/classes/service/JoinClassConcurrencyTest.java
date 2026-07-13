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
 */
@SpringBootTest
class JoinClassConcurrencyTest {

    private static final int RUNS = 10;

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
                ClassEntity c = new ClassEntity("Race-" + System.nanoTime(),
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

            tx.executeWithoutResult(s -> {
                em.createNativeQuery("DELETE FROM enrollments WHERE class_id = :id")
                        .setParameter("id", classId).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_classes WHERE class_id = :id")
                        .setParameter("id", classId).executeUpdate();
                em.createNativeQuery("DELETE FROM notifications WHERE reference_id = :id")
                        .setParameter("id", classId).executeUpdate();
                em.createNativeQuery("DELETE FROM class_invite_codes WHERE class_id = :id")
                        .setParameter("id", classId).executeUpdate();
                em.createNativeQuery("DELETE FROM classes WHERE id = :id")
                        .setParameter("id", classId).executeUpdate();
            });
        }
    }

    private static String uniqueClassCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.util.Random rnd = new java.util.Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }
}
