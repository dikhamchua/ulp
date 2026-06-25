package com.ulp.classes.service;

import com.ulp.auth.Role;
import com.ulp.classes.entity.ClassActivity;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.entity.ClassInviteCode;
import com.ulp.classes.repository.ClassInviteCodeRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Service for the invite-token lifecycle of a class.
 *
 * <p>Encapsulates the "one active per type per class" invariant
 * documented in {@code class-invite-codes/spec.md}: every mutation
 * disables the previous active row (history retained) before
 * inserting a new active row. Token format/uniqueness is delegated
 * to {@link InviteTokenGenerator}; collisions on the unique index
 * {@code idx_ic_code} trigger an in-loop retry.
 *
 * <p>Authorization for regeneration is delegated to
 * {@link ClassesService#getEditable(Long, Long, Role)} — same
 * ownership policy as the rest of the lecturer surfaces.
 */
@Service
public class InviteCodeService {

    private static final Logger log = LoggerFactory.getLogger(InviteCodeService.class);

    /** Cap on collision retries when generating CODE values. */
    static final int MAX_GENERATE_ATTEMPTS = 5;

    private final ClassInviteCodeRepository inviteRepository;
    private final InviteTokenGenerator generator;
    private final ClassActivityWriter activityWriter;
    private final ClassesService classesService;
    private final String appBaseUrl;

    public InviteCodeService(ClassInviteCodeRepository inviteRepository,
                             InviteTokenGenerator generator,
                             ClassActivityWriter activityWriter,
                             @Lazy ClassesService classesService,
                             @Value("${app.base-url:http://localhost:8080}") String appBaseUrl) {
        this.inviteRepository = inviteRepository;
        this.generator = generator;
        this.activityWriter = activityWriter;
        this.classesService = classesService;
        this.appBaseUrl = stripTrailingSlash(appBaseUrl);
    }

    /**
     * Provisions a fresh CODE-type and LINK-type active row for the
     * given class. Called from {@code ClassesService.create} and
     * from the backfill runner.
     *
     * <p>The method MUST run inside an existing transaction (caller
     * propagates {@code REQUIRED}) so a failure during CODE/LINK
     * insertion rolls back the surrounding class creation.
     *
     * @param classId   id of the class receiving the tokens
     * @param createdBy id of the user attributed as creator
     */
    @Transactional
    public void provisionDefaults(Long classId, Long createdBy) {
        insertWithRetry(classId, ClassInviteCode.TYPE_CODE, createdBy, generator::generateCode);
        insertWithRetry(classId, ClassInviteCode.TYPE_LINK, createdBy, generator::generateLink);
    }

    /**
     * Provisions ONE fresh active row of the requested type. Used
     * by the backfill runner when a class already has the other
     * type covered and we only want to fill the gap (avoids ending
     * up with two active rows of the same type).
     */
    @Transactional
    public ClassInviteCode provisionType(Long classId, Long createdBy, String type) {
        validateType(type);
        if (ClassInviteCode.TYPE_CODE.equals(type)) {
            return insertWithRetry(classId, type, createdBy, generator::generateCode);
        }
        return insertWithRetry(classId, type, createdBy, generator::generateLink);
    }

    /**
     * Rotates the active token of the given type for the given
     * class: disables the current active row (audit retained),
     * inserts a fresh active row, and writes a
     * {@code TYPE_UPDATED} {@code activity_classes} row noting the
     * rotation.
     *
     * @param classId target class id
     * @param type    {@code CODE} or {@code LINK}
     * @param userId  caller's id
     * @param role    caller's role (forwarded to
     *                {@link ClassesService#getEditable} for the
     *                ownership check)
     * @return the freshly-active token row
     * @throws EntityNotFoundException             when the class is missing
     * @throws org.springframework.security.access.AccessDeniedException when
     *         the caller is not the owning lecturer (HEAD/ADMIN bypass)
     * @throws IllegalArgumentException            when {@code type} is
     *         neither {@code CODE} nor {@code LINK}
     */
    @Transactional
    public ClassInviteCode regenerateActive(Long classId, String type, Long userId, Role role) {
        validateType(type);
        ClassEntity clazz = classesService.getEditable(classId, userId, role);

        Optional<ClassInviteCode> existing =
                inviteRepository.findByClassIdAndTypeAndActiveTrue(classId, type);
        String previousValue = existing.map(ClassInviteCode::getCode).orElse(null);
        existing.ifPresent(ic -> {
            ic.disable();
            inviteRepository.save(ic);
        });

        ClassInviteCode fresh = insertWithRetry(
                classId, type, userId,
                supplierFor(type, previousValue)
        );

        activityWriter.write(
                clazz.getId(),
                ClassActivity.TYPE_UPDATED,
                "Tạo mã mời mới (" + type + ") cho lớp " + clazz.getName(),
                Map.of("action", "regenerate_invite", "invite_type", type),
                userId
        );
        return fresh;
    }

    /** Returns the active CODE row for the given class, if any. */
    @Transactional(readOnly = true)
    public Optional<ClassInviteCode> findActiveCode(Long classId) {
        return inviteRepository.findByClassIdAndTypeAndActiveTrue(classId, ClassInviteCode.TYPE_CODE);
    }

    /** Returns the active LINK row for the given class, if any. */
    @Transactional(readOnly = true)
    public Optional<ClassInviteCode> findActiveLink(Long classId) {
        return inviteRepository.findByClassIdAndTypeAndActiveTrue(classId, ClassInviteCode.TYPE_LINK);
    }

    /**
     * Builds the full {@code /j/{code}} URL for the supplied LINK
     * invite. The base URL is sourced from {@code app.base-url}
     * (defaults to {@code http://localhost:8080}) with any trailing
     * slash stripped so URL concatenation is safe.
     */
    public String buildLinkUrl(ClassInviteCode link) {
        return appBaseUrl + "/j/" + link.getCode();
    }

    /**
     * Resolves an active token by raw user input.
     *
     * <p>If the input has length {@link InviteTokenGenerator#CODE_LENGTH}
     * the value is upper-cased before lookup (CODE tokens are
     * case-insensitive for student-facing UX). LINK tokens are case-
     * sensitive and queried as-is.
     */
    @Transactional(readOnly = true)
    public Optional<ClassInviteCode> findActiveByToken(String input) {
        if (input == null) return Optional.empty();
        String normalized = input.length() == InviteTokenGenerator.CODE_LENGTH
                ? input.toUpperCase()
                : input;
        return inviteRepository.findByCodeAndActiveTrue(normalized);
    }

    // ──────────────────────── internal ──────────────────────────

    private interface TokenSupplier {
        String next();
    }

    private TokenSupplier supplierFor(String type, String previousValue) {
        if (ClassInviteCode.TYPE_CODE.equals(type)) {
            return () -> {
                String next = generator.generateCode();
                // Ensure regenerated CODE differs from the just-
                // disabled value. Collision probability is 1/32^6 so
                // a single retry is more than enough in practice.
                int safety = 0;
                while (previousValue != null && previousValue.equals(next) && safety++ < 8) {
                    next = generator.generateCode();
                }
                return next;
            };
        }
        return generator::generateLink;
    }

    private ClassInviteCode insertWithRetry(Long classId, String type, Long createdBy,
                                            TokenSupplier supplier) {
        DataIntegrityViolationException last = null;
        for (int attempt = 1; attempt <= MAX_GENERATE_ATTEMPTS; attempt++) {
            String code = supplier.next();
            ClassInviteCode entity = new ClassInviteCode(classId, code, type, createdBy);
            try {
                return inviteRepository.saveAndFlush(entity);
            } catch (DataIntegrityViolationException ex) {
                if (!isCodeCollision(ex)) {
                    throw ex;
                }
                last = ex;
                log.warn("Invite-code collision on attempt {} for type {} (class {})",
                        attempt, type, classId);
            }
        }
        throw new IllegalStateException(
                "Không sinh được mã mời sau " + MAX_GENERATE_ATTEMPTS + " lần thử", last);
    }

    private boolean isCodeCollision(DataIntegrityViolationException ex) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        String msg = cause.getMessage();
        return msg != null && msg.contains("idx_ic_code");
    }

    private void validateType(String type) {
        if (!ClassInviteCode.TYPE_CODE.equals(type)
                && !ClassInviteCode.TYPE_LINK.equals(type)) {
            throw new IllegalArgumentException("Loại mã không hợp lệ: " + type);
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
