package com.ulp.features.classes.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.ClassInviteCode;
import com.ulp.features.classes.repository.ClassInviteCodeRepository;
import com.ulp.features.classes.repository.ClassRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * One-shot startup task that brings every non-deleted class up to
 * the "one active CODE + one active LINK" invariant introduced by
 * Sprint 2.3.
 *
 * <p>The Flyway migration V12 inserts sentinel rows
 * ({@code SEED-CODE-<id>}, {@code SEED-LINK-<id>}, both
 * {@code is_active=0}) so the data is in a known state. This
 * listener then iterates non-deleted classes; for each missing
 * active token it calls
 * {@link InviteCodeService#provisionDefaults} to insert a real
 * value via {@link InviteTokenGenerator}. Finally, sentinel rows
 * (only those whose {@code code LIKE 'SEED-%'} AND
 * {@code is_active=0}) are deleted.
 *
 * <p>Idempotent: a second invocation skips classes that already
 * have both active tokens, so it is safe to rerun on every boot.
 */
@Component
public class InviteCodeBackfillRunner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(InviteCodeBackfillRunner.class);

    private final ClassRepository classRepository;
    private final ClassInviteCodeRepository inviteRepository;
    private final InviteCodeService inviteCodeService;
    private final TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager em;

    public InviteCodeBackfillRunner(ClassRepository classRepository,
                                    ClassInviteCodeRepository inviteRepository,
                                    InviteCodeService inviteCodeService,
                                    TransactionTemplate transactionTemplate) {
        this.classRepository = classRepository;
        this.inviteRepository = inviteRepository;
        this.inviteCodeService = inviteCodeService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            // Wrap the work in a TransactionTemplate. Using @Transactional
            // on backfill() and calling it from this same bean does not
            // open a transaction because the call bypasses the Spring AOP
            // proxy (self-invocation). The programmatic approach below
            // always opens a real transaction, which is required by
            // EntityManager.executeUpdate() at line ~100.
            int provisioned = transactionTemplate.execute(status -> backfill());
            log.info("InviteCodeBackfillRunner provisioned tokens for {} class(es)", provisioned);
        } catch (Exception ex) {
            // Do not block startup on backfill failure — log loudly.
            log.error("InviteCodeBackfillRunner failed", ex);
        }
    }

    /**
     * Executes the backfill loop. Caller must open a transaction
     * (see {@link #onApplicationEvent}).
     *
     * @return how many class rows received at least one fresh token
     */
    int backfill() {
        List<ClassEntity> classes = classRepository.findAllByOrderByCreatedAtDesc();
        int touched = 0;
        for (ClassEntity c : classes) {
            boolean haveCode = inviteRepository
                    .findByClassIdAndTypeAndActiveTrue(c.getId(), ClassInviteCode.TYPE_CODE)
                    .isPresent();
            boolean haveLink = inviteRepository
                    .findByClassIdAndTypeAndActiveTrue(c.getId(), ClassInviteCode.TYPE_LINK)
                    .isPresent();
            if (haveCode && haveLink) {
                continue;
            }
            // Provision only the missing type(s) so we never end up
            // with two active rows of the same type.
            if (!haveCode) {
                inviteCodeService.provisionType(c.getId(), c.getCreatedBy(), ClassInviteCode.TYPE_CODE);
            }
            if (!haveLink) {
                inviteCodeService.provisionType(c.getId(), c.getCreatedBy(), ClassInviteCode.TYPE_LINK);
            }
            touched++;
        }
        // Wipe leftover V12 sentinel rows (is_active=0 + SEED-* code)
        // so they never resurface in lookups or audit history. We
        // bypass JPA and run a single native DELETE.
        int sentinelsRemoved = em.createNativeQuery(
                        "DELETE FROM class_invite_codes " +
                                "WHERE is_active = 0 AND code LIKE 'SEED-%'")
                .executeUpdate();
        if (sentinelsRemoved > 0) {
            log.info("Removed {} sentinel invite-code row(s)", sentinelsRemoved);
        }
        return touched;
    }
}
