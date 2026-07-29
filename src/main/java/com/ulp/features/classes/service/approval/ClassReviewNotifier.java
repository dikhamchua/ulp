package com.ulp.features.classes.service.approval;

import com.ulp.entities.ClassEntity;
import com.ulp.features.admin.departments.repository.DepartmentRepository;
import com.ulp.features.notifications.entity.NotificationType;
import com.ulp.features.notifications.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Emits the in-app notifications of the class review lifecycle: the department
 * HEAD is told a class needs review, and the owning lecturer is told the
 * outcome.
 *
 * <p>None of the three types is in {@link NotificationType#EMAIL_TYPES}, so
 * {@link NotificationService#create} never enqueues mail for them — class
 * creation and review stay entirely off the SMTP path. See
 * {@code .claude/rules/mail-job-queue.md}.
 *
 * <p>Every emission swallows its failure. A notification problem must never
 * roll back the class create/approve/reject that triggered it, matching the
 * behaviour of {@code JoinClassService}.
 */
@Component
public class ClassReviewNotifier {

    private static final Logger log = LoggerFactory.getLogger(ClassReviewNotifier.class);

    private final NotificationService notificationService;
    private final DepartmentRepository departmentRepository;

    public ClassReviewNotifier(NotificationService notificationService,
                               DepartmentRepository departmentRepository) {
        this.notificationService = notificationService;
        this.departmentRepository = departmentRepository;
    }

    /**
     * Tells the department HEAD that a freshly created class awaits review.
     *
     * <p>Silently does nothing when the class has no department, when the
     * department has no HEAD assigned, or when the HEAD is the class's own
     * creator — none of these is an error condition for class creation.
     *
     * @param clazz the newly persisted DRAFT class
     */
    public void notifyHeadPendingApproval(ClassEntity clazz) {
        try {
            Long departmentId = clazz.getDepartmentId();
            if (departmentId == null) {
                return;
            }
            departmentRepository.findById(departmentId)
                    .map(dept -> dept.getHeadUserId())
                    .filter(headId -> !headId.equals(clazz.getLecturerId()))
                    .ifPresent(headId -> notificationService.create(
                            headId,
                            "Lớp mới chờ duyệt",
                            "Lớp \"" + clazz.getName() + "\" (" + clazz.getCode()
                                    + ") đang chờ bạn duyệt.",
                            NotificationType.CLASS_PENDING_APPROVAL,
                            NotificationType.REF_CLASS,
                            clazz.getId()));
        } catch (RuntimeException ex) {
            log.warn("Không gửi được thông báo chờ duyệt cho lớp {}", clazz.getId(), ex);
        }
    }

    /**
     * Tells the owning lecturer their class was approved and is now operational.
     *
     * @param clazz the class just moved to UPCOMING
     */
    public void notifyLecturerApproved(ClassEntity clazz) {
        try {
            notificationService.create(
                    clazz.getLecturerId(),
                    "Lớp đã được duyệt",
                    "Lớp \"" + clazz.getName() + "\" (" + clazz.getCode()
                            + ") đã được duyệt. Sinh viên có thể tham gia.",
                    NotificationType.CLASS_APPROVED,
                    NotificationType.REF_CLASS,
                    clazz.getId());
        } catch (RuntimeException ex) {
            log.warn("Không gửi được thông báo duyệt lớp {}", clazz.getId(), ex);
        }
    }

    /**
     * Tells the owning lecturer their class was rejected, carrying the
     * reviewer's note when one was given.
     *
     * @param clazz the class just moved to REJECTED
     * @param note  the reviewer note, or null when none was supplied
     */
    public void notifyLecturerRejected(ClassEntity clazz, String note) {
        try {
            String body = "Lớp \"" + clazz.getName() + "\" (" + clazz.getCode()
                    + ") đã bị từ chối."
                    + (note == null || note.isBlank() ? "" : " Lý do: " + note);
            notificationService.create(
                    clazz.getLecturerId(),
                    "Lớp bị từ chối",
                    body,
                    NotificationType.CLASS_REJECTED,
                    NotificationType.REF_CLASS,
                    clazz.getId());
        } catch (RuntimeException ex) {
            log.warn("Không gửi được thông báo từ chối lớp {}", clazz.getId(), ex);
        }
    }
}
