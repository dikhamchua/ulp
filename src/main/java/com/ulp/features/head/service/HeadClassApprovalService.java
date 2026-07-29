package com.ulp.features.head.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Department;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.service.approval.ClassReviewNotifier;
import com.ulp.features.head.dto.HeadDtos.ApprovalQueueView;
import com.ulp.features.head.dto.HeadDtos.DepartmentSummary;
import com.ulp.features.head.dto.HeadDtos.PendingClassRow;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Drives the DRAFT → UPCOMING / REJECTED review lifecycle for the classes of a
 * department HEAD's own department.
 *
 * <p><b>Authorization.</b> {@code hasRole('HEAD')} on the controller is
 * necessary but not sufficient — it would let any HEAD review any department's
 * class. Every mutating method therefore resolves the actor's department
 * through {@link HeadDepartmentResolver} and requires the class's
 * {@code department_id} to match, throwing {@link AccessDeniedException}
 * otherwise.
 *
 * <p>The DRAFT-only guard lives on {@link ClassEntity} itself, so an illegal
 * transition fails identically regardless of caller.
 */
@Service
public class HeadClassApprovalService {

    private static final Logger log = LoggerFactory.getLogger(HeadClassApprovalService.class);

    private final HeadDepartmentResolver resolver;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final ClassReviewNotifier reviewNotifier;

    public HeadClassApprovalService(HeadDepartmentResolver resolver,
                                    ClassRepository classRepository,
                                    UserRepository userRepository,
                                    ClassReviewNotifier reviewNotifier) {
        this.resolver = resolver;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.reviewNotifier = reviewNotifier;
    }

    /**
     * Builds the approval queue for the given HEAD: the DRAFT classes of their
     * own department, newest first.
     *
     * @param headUserId the authenticated HEAD's user id
     * @return the queue view; an empty-department view when no department resolves
     */
    @Transactional(readOnly = true)
    public ApprovalQueueView load(Long headUserId) {
        Optional<Department> deptOpt = resolver.resolve(headUserId);
        if (deptOpt.isEmpty()) {
            return new ApprovalQueueView(null, List.of(), true);
        }
        Department dept = deptOpt.get();
        List<ClassEntity> drafts = classRepository
                .findAllByDepartmentIdAndStatusOrderByCreatedAtDesc(
                        dept.getId(), ClassEntity.STATUS_DRAFT);
        Map<Long, String> lecturerNames = loadLecturerNames(drafts);

        List<PendingClassRow> rows = new ArrayList<>(drafts.size());
        for (ClassEntity c : drafts) {
            rows.add(new PendingClassRow(
                    c.getId(), c.getName(), c.getCode(),
                    lecturerNames.getOrDefault(c.getLecturerId(), "—"),
                    c.getCreatedAt()));
        }
        return new ApprovalQueueView(
                new DepartmentSummary(dept.getId(), dept.getCode(), dept.getName()),
                rows, false);
    }

    /**
     * Approves a DRAFT class, making it operational and joinable, and notifies
     * the owning lecturer.
     *
     * @param headUserId the authenticated HEAD's user id
     * @param classId    the class to approve
     * @return the class display name, for the success toast
     * @throws AccessDeniedException   when the class is outside the HEAD's department
     * @throws EntityNotFoundException when no such class exists
     * @throws IllegalStateException   when the class is not DRAFT
     */
    @Transactional
    public String approve(Long headUserId, Long classId) {
        ClassEntity clazz = loadOwnDepartmentClass(headUserId, classId);
        clazz.approve(headUserId, LocalDateTime.now());
        ClassEntity saved = classRepository.save(clazz);
        // Notification is secondary to the state transition: a broken notifier
        // must not roll back an approval the HEAD already granted.
        try {
            reviewNotifier.notifyLecturerApproved(saved);
        } catch (RuntimeException ex) {
            log.warn("Không gửi được thông báo duyệt lớp {}", saved.getId(), ex);
        }
        return saved.getName();
    }

    /**
     * Rejects a DRAFT class into the terminal REJECTED state with an optional
     * reviewer note, and notifies the owning lecturer.
     *
     * @param headUserId the authenticated HEAD's user id
     * @param classId    the class to reject
     * @param note       optional reviewer explanation; blank is stored as null
     * @return the class display name, for the success toast
     * @throws AccessDeniedException   when the class is outside the HEAD's department
     * @throws EntityNotFoundException when no such class exists
     * @throws IllegalStateException   when the class is not DRAFT
     */
    @Transactional
    public String reject(Long headUserId, Long classId, String note) {
        ClassEntity clazz = loadOwnDepartmentClass(headUserId, classId);
        clazz.reject(headUserId, note, LocalDateTime.now());
        ClassEntity saved = classRepository.save(clazz);
        // Same rationale as approve(): the rejection stands even if the
        // lecturer cannot be notified about it.
        try {
            reviewNotifier.notifyLecturerRejected(saved, saved.getRejectionNote());
        } catch (RuntimeException ex) {
            log.warn("Không gửi được thông báo từ chối lớp {}", saved.getId(), ex);
        }
        return saved.getName();
    }

    /**
     * Loads a class and asserts it belongs to the acting HEAD's department.
     * A class with a null {@code department_id} matches no HEAD and is denied.
     */
    private ClassEntity loadOwnDepartmentClass(Long headUserId, Long classId) {
        Department dept = resolver.resolve(headUserId)
                .orElseThrow(() -> new AccessDeniedException("Không có bộ môn"));
        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy lớp"));
        if (clazz.getDepartmentId() == null
                || !clazz.getDepartmentId().equals(dept.getId())) {
            throw new AccessDeniedException("Lớp không thuộc bộ môn của bạn");
        }
        return clazz;
    }

    /** Resolves lecturer display names for the queue rows, one lookup per id. */
    private Map<Long, String> loadLecturerNames(List<ClassEntity> classes) {
        Map<Long, String> names = new HashMap<>();
        for (ClassEntity c : classes) {
            if (c.getLecturerId() != null && !names.containsKey(c.getLecturerId())) {
                userRepository.findById(c.getLecturerId())
                        .ifPresent(u -> names.put(u.getId(), u.getFullName()));
            }
        }
        return names;
    }
}
