package com.ulp.classes.service;

import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.entity.ClassInviteCode;
import com.ulp.classes.repository.ClassInviteCodeRepository;
import com.ulp.classes.repository.ClassRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link InviteCodeBackfillRunner}. Seeds
 * classes in three shapes (no tokens, only CODE active, both
 * active) and asserts the runner brings them all into the
 * "one active per type" invariant — and that a second run is a
 * no-op.
 */
@SpringBootTest
class InviteCodeBackfillRunnerTest {

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

        // ── Seed 3 classes ──
        ClassEntity none = createClass(lecturer.getId(), "Backfill-None");
        ClassEntity codeOnly = createClass(lecturer.getId(), "Backfill-CodeOnly");
        ClassEntity full = createClass(lecturer.getId(), "Backfill-Full");

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

        // ── First run ──
        // backfill() requires a transaction (the production caller
        // opens one via TransactionTemplate). Wrap each invocation
        // here so the call mirrors the real bootstrap flow.
        tx.executeWithoutResult(s -> runner.backfill());

        // Assert: each class has exactly one active CODE + one active LINK.
        assertInvariant(none.getId());
        assertInvariant(codeOnly.getId());
        assertInvariant(full.getId());

        // ── Second run is a no-op (idempotent) ──
        tx.executeWithoutResult(s -> runner.backfill());
        assertInvariant(none.getId());
        assertInvariant(codeOnly.getId());
        assertInvariant(full.getId());

        // ── Sentinel rows are gone ──
        Number sentinelCount = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM class_invite_codes WHERE code LIKE 'SEED-%'")
                .getSingleResult();
        assertThat(sentinelCount.intValue()).isZero();

        // Cleanup
        tx.executeWithoutResult(s -> {
            for (Long id : List.of(none.getId(), codeOnly.getId(), full.getId())) {
                em.createNativeQuery("DELETE FROM class_invite_codes WHERE class_id = :id")
                        .setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_classes WHERE class_id = :id")
                        .setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM classes WHERE id = :id")
                        .setParameter("id", id).executeUpdate();
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
