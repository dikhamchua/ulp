package com.ulp.features.classes.service;

import com.ulp.entities.ClassActivity;
import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.dto.ClassesDtos.ClassForm;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.service.codes.ClassCodeGenerationException;
import com.ulp.features.classes.service.codes.ClassCodeGenerator;
import com.ulp.features.classes.service.invites.InviteCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Encapsulates the {@link ClassEntity} creation flow including the collision-
 * retry loop for the unique class code, the {@link ClassActivity#TYPE_CREATED}
 * audit row, and the default CODE + LINK invite token provisioning.
 *
 * <p>Plain helper instantiated by {@link ClassesService} during construction
 * rather than a separate Spring bean so that the existing
 * {@code (classRepository, activityWriter, codeGenerator, inviteCodeService)}
 * constructor surface is preserved for unit tests.
 *
 * <p>The {@code @Transactional} boundary lives on {@link ClassesService#create}
 * which calls into this helper, so failures during token provisioning roll
 * back the entity insert and the audit row together.
 */
final class ClassCreator {

    private static final Logger log = LoggerFactory.getLogger(ClassCreator.class);
    static final int MAX_CODE_GEN_ATTEMPTS = 3;

    private final ClassRepository classRepository;
    private final ClassActivityWriter activityWriter;
    private final ClassCodeGenerator codeGenerator;
    private final InviteCodeService inviteCodeService;

    ClassCreator(ClassRepository classRepository,
                 ClassActivityWriter activityWriter,
                 ClassCodeGenerator codeGenerator,
                 InviteCodeService inviteCodeService) {
        this.classRepository = classRepository;
        this.activityWriter = activityWriter;
        this.codeGenerator = codeGenerator;
        this.inviteCodeService = inviteCodeService;
    }

    /**
     * Inserts a fresh {@link ClassEntity} for the given lecturer, retrying
     * up to {@value #MAX_CODE_GEN_ATTEMPTS} times on
     * {@code uk_classes_code} collisions and rethrowing other unique-violation
     * causes immediately.
     */
    ClassEntity create(ClassForm form, Long userId) {
        DataIntegrityViolationException lastCollision = null;
        for (int attempt = 1; attempt <= MAX_CODE_GEN_ATTEMPTS; attempt++) {
            ClassEntity entity = new ClassEntity(
                    form.name(), userId, userId,
                    form.description(), form.startDate(), form.endDate(),
                    form.maxStudents());
            entity.setCode(codeGenerator.generate());
            try {
                ClassEntity saved = classRepository.saveAndFlush(entity);
                activityWriter.write(
                        saved.getId(),
                        ClassActivity.TYPE_CREATED,
                        "Tạo lớp " + saved.getName(),
                        userId
                );
                // Atomically provision the default CODE + LINK invite
                // tokens for the new class. Token-provisioning failure
                // (DB error, repeated collision) propagates out of the
                // surrounding @Transactional method, rolling the class
                // creation back together with the audit row.
                inviteCodeService.provisionDefaults(saved.getId(), userId);
                return saved;
            } catch (DataIntegrityViolationException ex) {
                if (!isCodeCollision(ex)) {
                    throw ex;
                }
                lastCollision = ex;
                log.warn("Class code collision on attempt {} — retrying", attempt);
            }
        }
        throw new ClassCodeGenerationException(
                "Không sinh được mã lớp sau " + MAX_CODE_GEN_ATTEMPTS + " lần thử",
                lastCollision);
    }

    /** True when the unique-violation cause names the {@code uk_classes_code} index. */
    private static boolean isCodeCollision(DataIntegrityViolationException ex) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        String msg = cause.getMessage();
        return msg != null && msg.contains("uk_classes_code");
    }
}
