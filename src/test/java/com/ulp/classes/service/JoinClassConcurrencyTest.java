package com.ulp.classes.service;

import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.entity.ClassInviteCode;
import com.ulp.classes.entity.Enrollment;
import com.ulp.classes.repository.ClassInviteCodeRepository;
import com.ulp.classes.repository.ClassRepository;
import com.ulp.classes.repository.EnrollmentRepository;
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
 * Concurrency-stress test for the join pipeline. Two threads race
 * for the last available use of a {@code max_uses=1} token; the
 * pessimistic write lock must serialize them so exactly ONE thread
 * wins and the token's {@code use_count} settles at {@code 1}.
 *
 * <p>The test repeats the race 10 times to make a deterministic
 * pass — a broken lock would manifest as both threads winning at
 * least once across the iterations.
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
    void two_threads_race_on_last_use_yields_one_success_and_one_rejection() throws Exception {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        User a = userRepository.findByEmailIgnoreCase("sv01@ulp.edu.vn").orElseThrow();
        User b = userRepository.findByEmailIgnoreCase("sv02@ulp.edu.vn").orElseThrow();

        for (int iter = 0; iter < RUNS; iter++) {
            // ── Fresh seed for each iteration ──
            ClassEntity clazz = tx.execute(s -> {
                ClassEntity c = new ClassEntity("Race-" + System.nanoTime(),
                        lecturer.getId(), lecturer.getId(), null, null, null, 100);
                c.setCode(uniqueClassCode());
                return classRepository.saveAndFlush(c);
            });
            // Provision a CODE token and set max_uses=1
            inviteCodeService.provisionDefaults(clazz.getId(), lecturer.getId());
            ClassInviteCode token = inviteRepository
                    .findByClassIdAndTypeAndActiveTrue(clazz.getId(), "CODE").orElseThrow();
            tx.executeWithoutResult(s -> {
                em.createNativeQuery(
                                "UPDATE class_invite_codes SET max_uses = 1, use_count = 0 WHERE id = :id")
                        .setParameter("id", token.getId())
                        .executeUpdate();
            });
            final String code = token.getCode();
            final Long classId = clazz.getId();

            // ── Race two threads on the same token ──
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger rejections = new AtomicInteger();

            Thread t1 = new Thread(() -> {
                try {
                    start.await();
                    try {
                        tx.execute(s -> joinClassService.join(code, a.getId()));
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
                        tx.execute(s -> joinClassService.join(code, b.getId()));
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

            ClassInviteCode after = inviteRepository.findById(token.getId()).orElseThrow();
            int useCount = after.getUseCount();
            long activeEnrollments = enrollmentRepository.countActiveByClassId(classId);

            assertThat(successes.get())
                    .as("iteration %d: expected one Success, got %d", iter, successes.get())
                    .isEqualTo(1);
            assertThat(rejections.get())
                    .as("iteration %d: expected one rejection", iter)
                    .isEqualTo(1);
            assertThat(useCount)
                    .as("iteration %d: use_count must settle at 1", iter)
                    .isEqualTo(1);
            assertThat(activeEnrollments)
                    .as("iteration %d: exactly one ACTIVE enrollment", iter)
                    .isEqualTo(1L);

            // ── Cleanup ──
            tx.executeWithoutResult(s -> {
                em.createNativeQuery("DELETE FROM enrollments WHERE class_id = :id")
                        .setParameter("id", classId).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_classes WHERE class_id = :id")
                        .setParameter("id", classId).executeUpdate();
                em.createNativeQuery("DELETE FROM class_invite_codes WHERE class_id = :id")
                        .setParameter("id", classId).executeUpdate();
                em.createNativeQuery("DELETE FROM classes WHERE id = :id")
                        .setParameter("id", classId).executeUpdate();
            });
        }
    }

    private static String uniqueClassCode() {
        // 5-char random in the allowed alphabet
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.util.Random rnd = new java.util.Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }
}
