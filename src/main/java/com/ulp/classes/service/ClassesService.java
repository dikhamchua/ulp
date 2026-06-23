package com.ulp.classes.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.auth.Role;
import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public ClassesService(ClassRepository classRepository,
                          ClassActivityRepository activityRepository,
                          ClassCodeGenerator codeGenerator,
                          UserRepository userRepository,
                          ObjectMapper objectMapper) {
        this.classRepository = classRepository;
        this.activityRepository = activityRepository;
        this.codeGenerator = codeGenerator;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    // ───────────────────── Public CRUD API ──────────────────────────

    /**
     * Returns the list of classes visible to the current user.
     * LECTURER → only their own classes.
     * HEAD/ADMIN → all classes that have not been soft-deleted.
     *
     * @param principal the authenticated user's security principal
     * @return list of {@link ClassRow} DTOs ordered by creation date descending
     */
    @Transactional(readOnly = true)
    public List<ClassRow> listForUser(Principal principal) {
        User user = currentUser(principal);
        List<ClassEntity> rows = user.getRole() == Role.LECTURER
                ? classRepository.findAllByLecturerIdOrderByCreatedAtDesc(user.getId())
                : classRepository.findAllByOrderByCreatedAtDesc();
        return IntStream.range(0, rows.size())
                .mapToObj(i -> toRow(rows.get(i), i))
                .toList();
    }

    /** Load lop de edit, da kiem tra quyen. */
    @Transactional(readOnly = true)
    public ClassEntity getEditable(Long id, Principal principal) {
        User user = currentUser(principal);
        return loadEditable(id, user);
    }

    /**
     * Load lop de XEM CHI TIET (members, board, ...). Cung kiem tra
     * quyen nhu {@link #getEditable}: LECTURER chi xem duoc lop minh,
     * HEAD/ADMIN xem het. Tach ra de Sprint sau co the noi long quy
     * tac (vd. ai cung xem duoc bang tin) ma khong dung den edit path.
     */
    @Transactional(readOnly = true)
    public ClassEntity getViewable(Long id, Principal principal) {
        User user = currentUser(principal);
        return loadEditable(id, user);
    }

    /**
     * Tao lop moi. Sinh code, retry toi da 3 lan khi collision tren
     * {@code uk_classes_code}; nem ngay neu unique-violation tren cot khac.
     */
    @Transactional
    public ClassEntity create(ClassForm form, Principal principal) {
        User user = currentUser(principal);

        DataIntegrityViolationException lastCollision = null;
        for (int attempt = 1; attempt <= MAX_CODE_GEN_ATTEMPTS; attempt++) {
            ClassEntity entity = new ClassEntity(
                    form.name(), user.getId(), user.getId(),
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
                        user.getId()
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
    public ClassEntity update(Long id, ClassForm form, Principal principal) {
        User user = currentUser(principal);
        ClassEntity entity = loadEditable(id, user);

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
                user.getId()
        ));
        return saved;
    }

    /** Soft-delete lop. Phan quyen enforced. Ghi activity DELETED. */
    @Transactional
    public void softDelete(Long id, Principal principal) {
        User user = currentUser(principal);
        ClassEntity entity = loadEditable(id, user);

        entity.softDelete();
        classRepository.save(entity);
        activityRepository.save(new ClassActivity(
                entity.getId(),
                ClassActivity.TYPE_DELETED,
                "Xoá lớp " + entity.getName(),
                null,
                user.getId()
        ));
    }

    // ──────────────────────── Internal ──────────────────────────────

    /** Load + authz, dung trong noi bo (khi user da resolve). DRY voi {@link #getEditable}. */
    private ClassEntity loadEditable(Long id, User user) {
        ClassEntity entity = classRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Lớp không tồn tại"));
        requireEditableBy(entity, user);
        return entity;
    }

    private User currentUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in DB"));
    }

    private void requireEditableBy(ClassEntity entity, User user) {
        if (user.getRole() == Role.LECTURER && !entity.getLecturerId().equals(user.getId())) {
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
