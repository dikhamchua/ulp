package com.ulp.classes.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.auth.Role;
import com.ulp.classes.ClassGradient;
import com.ulp.classes.dto.ClassesDtos.ClassForm;
import com.ulp.classes.dto.ClassesDtos.ClassRow;
import com.ulp.classes.entity.ClassActivity;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.repository.ClassActivityRepository;
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
 * {@link ClassActivity}. Because service methods are {@code @Transactional},
 * a failure when inserting the activity record will also roll back the class mutation.
 */
@Service
public class ClassesService {

    private static final Logger log = LoggerFactory.getLogger(ClassesService.class);
    private static final int MAX_CODE_GEN_ATTEMPTS = 3;

    private final ClassRepository classRepository;
    private final ClassActivityRepository activityRepository;
    private final ClassCodeGenerator codeGenerator;
    private final ObjectMapper objectMapper;

    public ClassesService(ClassRepository classRepository,
                          ClassActivityRepository activityRepository,
                          ClassCodeGenerator codeGenerator,
                          ObjectMapper objectMapper) {
        this.classRepository = classRepository;
        this.activityRepository = activityRepository;
        this.codeGenerator = codeGenerator;
        this.objectMapper = objectMapper;
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

    /** Load lop de edit, da kiem tra quyen. */
    @Transactional(readOnly = true)
    public ClassEntity getEditable(Long id, Long userId, Role role) {
        return loadEditable(id, userId, role);
    }

    /**
     * Load lop de XEM CHI TIET (members, board, ...). Cung kiem tra
     * quyen nhu {@link #getEditable}: LECTURER chi xem duoc lop minh,
     * HEAD/ADMIN xem het. Tach ra de Sprint sau co the noi long quy
     * tac (vd. ai cung xem duoc bang tin) ma khong dung den edit path.
     */
    @Transactional(readOnly = true)
    public ClassEntity getViewable(Long id, Long userId, Role role) {
        return loadEditable(id, userId, role);
    }

    /**
     * Tao lop moi. Sinh code, retry toi da 3 lan khi collision tren
     * {@code uk_classes_code}; nem ngay neu unique-violation tren cot khac.
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
                activityRepository.save(new ClassActivity(
                        saved.getId(),
                        ClassActivity.TYPE_CREATED,
                        "Tạo lớp " + saved.getName(),
                        null,
                        userId
                ));
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

    /** Cap nhat lop. Phan quyen enforced. Ghi activity UPDATED voi diff. */
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

        activityRepository.save(new ClassActivity(
                saved.getId(),
                ClassActivity.TYPE_UPDATED,
                "Cập nhật lớp " + saved.getName(),
                serialize(diff),
                userId
        ));
        return saved;
    }

    /** Soft-delete lop. Phan quyen enforced. Ghi activity DELETED. */
    @Transactional
    public void softDelete(Long id, Long userId, Role role) {
        ClassEntity entity = loadEditable(id, userId, role);

        entity.softDelete();
        classRepository.save(entity);
        activityRepository.save(new ClassActivity(
                entity.getId(),
                ClassActivity.TYPE_DELETED,
                "Xoá lớp " + entity.getName(),
                null,
                userId
        ));
    }

    // ──────────────────────── Internal ──────────────────────────────

    /** Load + authz against the caller's id/role. DRY voi {@link #getEditable}. */
    private ClassEntity loadEditable(Long id, Long userId, Role role) {
        ClassEntity entity = classRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Lớp không tồn tại"));
        requireEditableBy(entity, userId, role);
        return entity;
    }

    private void requireEditableBy(ClassEntity entity, Long userId, Role role) {
        if (role == Role.LECTURER && !entity.getLecturerId().equals(userId)) {
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

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize activity metadata", ex);
            return null;
        }
    }
}
