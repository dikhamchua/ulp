package com.ulp.features.classes.service.invites;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.ClassInviteCode;
import com.ulp.features.classes.repository.ClassInviteCodeRepository;
import com.ulp.features.classes.repository.ClassRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link InviteCodeBackfillRunner}. Seeds
 * classes in three shapes (no tokens, only CODE active, both
 * active) and asserts the runner brings them all into the
 * "one active per type" invariant — and that a second run is a
 * no-op.
 *
 * <p>The test is {@code @Commit} by necessity: {@code backfill()} opens its
 * own transaction and cannot see uncommitted rows. Nothing rolls back, so
 * seeded rows are removed by the {@link #cleanUp()} {@code @AfterEach} hook,
 * which runs on failure as well as on success.
 */
@SpringBootTest
class InviteCodeBackfillRunnerTest {

    /** Name prefix identifying every class this test seeds. */
    private static final String CLASS_NAME_PREFIX = "Backfill-";

    @Autowired private InviteCodeBackfillRunner runner;
    @Autowired private ClassRepository classRepository;
    @Autowired private ClassInviteCodeRepository inviteRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate tx;

    @PersistenceContext private EntityManager em;

    @Test
    @Commit
    void backfill_brings_missing_classes_to_invariant_and_is_idempotent() {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();

        // Seed 3 classes in three different invite-token shapes.
        ClassEntity none = createClass(lecturer.getId(), CLASS_NAME_PREFIX + "None");
        ClassEntity codeOnly = createClass(lecturer.getId(), CLASS_NAME_PREFIX + "CodeOnly");
        ClassEntity full = createClass(lecturer.getId(), CLASS_NAME_PREFIX + "Full");

        // Clean any pre-existing tokens left by ClassesService.create
        // so we control the starting state of each class precisely.
        wipeInviteRows(none.getId());
        wipeInviteRows(codeOnly.getId());
        wipeInviteRows(full.getId());

        // codeOnly: one active CODE row, no LINK
        String codeOnlyValue = "BFC" + tokenSuffix();
        tx.executeWithoutResult(s -> {
            ClassInviteCode code = new ClassInviteCode(codeOnly.getId(), codeOnlyValue,
                    "CODE", lecturer.getId());
            inviteRepository.saveAndFlush(code);
        });

        // full: one active CODE + one active LINK
        String fullCodeValue = "BFD" + tokenSuffix();
        // Build a deterministic 32-char base64url-safe link value
        // unique per test run.
        String fullLinkValue = (tokenSuffix() + tokenSuffix() + tokenSuffix()
                + tokenSuffix() + tokenSuffix() + tokenSuffix())
                .substring(0, 32)
                .replace('+', '-').replace('/', '_');
        tx.executeWithoutResult(s -> {
            inviteRepository.saveAndFlush(new ClassInviteCode(full.getId(),
                    fullCodeValue, "CODE", lecturer.getId()));
            inviteRepository.saveAndFlush(new ClassInviteCode(full.getId(),
                    fullLinkValue, "LINK", lecturer.getId()));
        });

        // First run.
        // backfill() requires a transaction (the production caller
        // opens one via TransactionTemplate). Wrap each invocation
        // here so the call mirrors the real bootstrap flow.
        tx.executeWithoutResult(s -> runner.backfill());

        // Assert: each class has exactly one active CODE + one active LINK.
        assertInvariant(none.getId());
        assertInvariant(codeOnly.getId());
        assertInvariant(full.getId());

        // Second run must be a no-op (idempotent).
        tx.executeWithoutResult(s -> runner.backfill());
        assertInvariant(none.getId());
        assertInvariant(codeOnly.getId());
        assertInvariant(full.getId());

        // Sentinel rows the runner uses as placeholders must be gone. This is
        // a database-wide check, so it stays meaningful regardless of cleanup.
        Number sentinelCount = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM class_invite_codes WHERE code LIKE 'SEED-%'")
                .getSingleResult();
        assertThat(sentinelCount.intValue()).isZero();
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
            System.err.println("[InviteCodeBackfillRunnerTest] cleanup failed: " + ex);
        }
    }

    /** Deletes seeded classes child-rows-first to satisfy FK constraints. */
    private void deleteSeededClasses() {
        tx.executeWithoutResult(s -> {
            String subQuery = "(SELECT id FROM (SELECT id FROM classes "
                    + "WHERE name LIKE :prefix) AS c)";
            // This test seeds no enrollments, but they are cleared first
            // anyway: enrollments FK-reference class_invite_codes.
            String[] statements = {
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

    private void assertInvariant(Long classId) {
        long codeCount = inviteRepository.findAllByClassIdOrderByIdAsc(classId).stream()
                .filter(ic -> ic.getType().equals("CODE") && ic.isActive()).count();
        long linkCount = inviteRepository.findAllByClassIdOrderByIdAsc(classId).stream()
                .filter(ic -> ic.getType().equals("LINK") && ic.isActive()).count();
        assertThat(codeCount).as("class %d active CODE", classId).isEqualTo(1L);
        assertThat(linkCount).as("class %d active LINK", classId).isEqualTo(1L);
    }

    private void wipeInviteRows(Long classId) {
        tx.executeWithoutResult(s ->
                em.createNativeQuery("DELETE FROM class_invite_codes WHERE class_id = :id")
                        .setParameter("id", classId).executeUpdate());
    }

    private ClassEntity createClass(Long lecturerId, String name) {
        return tx.execute(s -> {
            ClassEntity c = new ClassEntity(name, lecturerId, lecturerId,
                    null, null, null, 100);
            c.setCode(uniqueCode());
            return classRepository.saveAndFlush(c);
        });
    }

    private static String uniqueCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.util.Random rnd = new java.util.Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static String tokenSuffix() {
        return Long.toString(System.nanoTime(), 36).substring(0, 6).toUpperCase();
    }
}
