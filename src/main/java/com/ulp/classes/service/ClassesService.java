package com.ulp.classes.service;

import com.ulp.auth.Role;
import com.ulp.classes.ClassGradient;
import com.ulp.classes.dto.ClassesDtos.ClassForm;
import com.ulp.classes.dto.ClassesDtos.ClassRow;
import com.ulp.classes.entity.ClassActivity;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.repository.ClassRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business service for class CRUD operations on the lecturer-facing screens.
 *
 * <p>Authorization rules (enforced here, NOT in the controller):
 * <ul>
 *   <li>LECTURER can only view/edit/delete their own classes ({@code lecturer_id == user.id}).</li>
 *   <li>HEAD and ADMIN can view all classes and edit/delete any class.</li>
 *   <li>Authorization violations throw {@link AccessDeniedException} → HTTP 403.</li>
 *   <li>Non-existent or soft-deleted classes throw {@link EntityNotFoundException} → HTTP 404.</li>
 * </ul>
 *
 * <p>Caller identity is supplied directly by controllers from
 * {@code @AuthenticationPrincipal UlpUserDetails} as {@code (Long userId, Role role)}.
 * The service does not look up the caller by email — Spring Security has already
 * loaded the user during authentication, so a second SELECT per request would be
 * wasted work.
 *
 * <p>Every mutation (create/update/softDelete) writes one row to
 * {@link ClassActivity} via {@link ClassActivityWriter}. Because service methods
 * are {@code @Transactional}, a failure when inserting the activity record will
 * also roll back the class mutation.
 */
@Service
public class ClassesService {

    private static final Logger log = LoggerFactory.getLogger(ClassesService.class);
    private static final int MAX_CODE_GEN_ATTEMPTS = 3;

    private final ClassRepository classRepository;
    private final ClassActivityWriter activityWriter;
    private final ClassCodeGenerator codeGenerator;
    private final InviteCodeService inviteCodeService;

    public ClassesService(ClassRepository classRepository,
                          ClassActivityWriter activityWriter,
                          ClassCodeGenerator codeGenerator,
                          InviteCodeService inviteCodeService) {
        this.classRepository = classRepository;
        this.activityWriter = activityWriter;
        this.codeGenerator = codeGenerator;
        this.inviteCodeService = inviteCodeService;
    }

    // ───────────────────── Public CRUD API ──────────────────────────

    /**
     * Returns the page of classes visible to the current user.
     * LECTURER → only their own classes.
     * HEAD/ADMIN → all classes that have not been soft-deleted.
     *
     * <p>The gradient assigned to each {@link ClassRow} is derived from the row's
     * position within the CURRENT page (0-based), not the global ranking. Different
     * pages can therefore repeat gradient colours — this is intentional and matches
     * the audit's "good enough" tolerance for the cosmetic ordering of class
     * thumbnails. Pages are otherwise sorted strictly per the supplied {@link Pageable}
     * (typically {@code createdAt DESC}).
     *
     * @param userId   the authenticated user's database id
     * @param role     the authenticated user's role
     * @param pageable page request (page index, size, sort)
     * @return a {@link Page} of {@link ClassRow} DTOs
     */
    @Transactional(readOnly = true)
    public Page<ClassRow> listForUser(Long userId, Role role, Pageable pageable) {
        Page<ClassEntity> page = role == Role.LECTURER
                ? classRepository.findAllByLecturerId(userId, pageable)
                : classRepository.findAllBy(pageable);

        List<ClassEntity> content = page.getContent();
        List<ClassRow> rows = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            rows.add(toRow(content.get(i), i));
        }
        return new PageImpl<>(rows, pageable, page.getTotalElements());
    }

    /** Loads a class for editing after enforcing authorization. */
    @Transactional(readOnly = true)
    public ClassEntity getEditable(Long id, Long userId, Role role) {
        return loadEditable(id, userId, role);
    }

    /**
     * Loads a class for the detail view (members, board, ...). Applies the
     * same authorization as {@link #getEditable}: LECTURER may only access
     * their own classes; HEAD and ADMIN may access any class. The viewable
     * and editable code paths are kept separate so a future sprint can
     * relax the read-side rule (for example, allowing students enrolled in
     * a class to read the board) without touching the edit-side rule.
     */
    @Transactional(readOnly = true)
    public ClassEntity getViewable(Long id, Long userId, Role role) {
        return loadEditable(id, userId, role);
    }

    /**
     * Creates a new class. Generates the {@code classes.code} value, retrying up to
     * {@value #MAX_CODE_GEN_ATTEMPTS} times when the unique index
     * {@code uk_classes_code} reports a collision. Other unique-violation causes
     * (e.g., on a different column) are rethrown immediately.
     */
    @Transactional
    public ClassEntity create(ClassForm form, Long userId) {
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
                // (DB error, repeated collision) propagates out of
                // this @Transactional method, rolling the class
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

    /** Updates an existing class. Authorization is enforced; writes an UPDATED activity row with a before/after diff. */
    @Transactional
    public ClassEntity update(Long id, ClassForm form, Long userId, Role role) {
        ClassEntity entity = loadEditable(id, userId, role);

        Map<String, Object> oldState = snapshot(entity);
        entity.updateDetails(form.name(), form.description(),
                form.startDate(), form.endDate(), form.maxStudents());
        ClassEntity saved = classRepository.save(entity);

        Map<String, Object> newState = snapshot(saved);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("old", oldState);
        diff.put("new", newState);

        activityWriter.write(
                saved.getId(),
                ClassActivity.TYPE_UPDATED,
                "Cập nhật lớp " + saved.getName(),
                diff,
                userId
        );
        return saved;
    }

    /** Soft-deletes a class. Authorization is enforced; writes a DELETED activity row. */
    @Transactional
    public void softDelete(Long id, Long userId, Role role) {
        ClassEntity entity = loadEditable(id, userId, role);

        entity.softDelete();
        classRepository.save(entity);
        activityWriter.write(
                entity.getId(),
                ClassActivity.TYPE_DELETED,
                "Xoá lớp " + entity.getName(),
                userId
        );
    }

    // ──────────────────────── Internal ──────────────────────────────

    /**
     * Returns whether the caller is authorised to edit the given class.
     * HEAD and ADMIN may edit any class; LECTURER may only edit classes they own.
     */
    public boolean isEditableBy(ClassEntity clazz, Long userId, Role role) {
        if (role == null) return false;
        return role == Role.ADMIN
                || role == Role.HEAD
                || (role == Role.LECTURER && clazz.getLecturerId().equals(userId));
    }

    /** Loads the class and enforces the editable-by authorisation rule. */
    private ClassEntity loadEditable(Long id, Long userId, Role role) {
        ClassEntity entity = classRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Lớp không tồn tại"));
        requireEditableBy(entity, userId, role);
        return entity;
    }

    private void requireEditableBy(ClassEntity entity, Long userId, Role role) {
        if (!isEditableBy(entity, userId, role)) {
            throw new AccessDeniedException("Bạn không có quyền chỉnh sửa lớp này");
        }
    }

    private boolean isCodeCollision(DataIntegrityViolationException ex) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        String msg = cause.getMessage();
        return msg != null && msg.contains("uk_classes_code");
    }

    private ClassRow toRow(ClassEntity e, int index) {
        // TODO Sprint 3/5: wire real counts from enrollments/lessons/assignments/lesson_attachments
        int studentCount = 0;
        int lectureCount = 0;
        int assignmentCount = 0;
        int materialCount = 0;
        String createdAtIso = e.getCreatedAt() != null ? e.getCreatedAt().toString() : "";
        return new ClassRow(
                e.getId(),
                e.getName(),
                e.getCode(),
                ClassGradient.forIndex(index).css(),
                studentCount, lectureCount, assignmentCount, materialCount,
                createdAtIso
        );
    }

    private Map<String, Object> snapshot(ClassEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.getName());
        m.put("description", e.getDescription());
        m.put("startDate", e.getStartDate() != null ? e.getStartDate().toString() : null);
        m.put("endDate", e.getEndDate() != null ? e.getEndDate().toString() : null);
        m.put("maxStudents", e.getMaxStudents());
        return m;
    }
}
