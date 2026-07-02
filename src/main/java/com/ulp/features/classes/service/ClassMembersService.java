package com.ulp.features.classes.service;

import com.ulp.security.Role;
import com.ulp.entities.User;
import com.ulp.features.classes.dto.MemberDtos.MemberRow;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.classes.service.support.AvatarStyles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Read-only service for managing the member (student) list within a single class.
 * Sprint 2 exposes READ only; full member CRUD (add/remove/import) is deferred to Sprint 2.1.
 *
 * <p>Authorization is delegated to {@link ClassesService#getViewable} to verify
 * the caller has access to the class before returning its members.
 *
 * <p>Sprint 2.4 refactor: the active CODE / LINK / canRegenerate fields previously
 * exposed on {@link ClassMembersView} were removed when the invite panel moved out
 * of the Members tab. Invite data is now injected into every detail tab by the
 * controller via {@code InviteCodeService} directly, so this service no longer
 * depends on {@code InviteCodeService} or {@code app.base-url}.
 */
@Service
public class ClassMembersService {

    private final ClassesService classesService;
    private final EnrollmentRepository enrollmentRepository;

    public ClassMembersService(ClassesService classesService,
                               EnrollmentRepository enrollmentRepository) {
        this.classesService = classesService;
        this.enrollmentRepository = enrollmentRepository;
    }

    /**
     * Returns the list of ACTIVE members for the given class, after verifying access rights.
     *
     * @param classId the ID of the class whose members are being retrieved
     * @param userId  the authenticated caller's database id
     * @param role    the authenticated caller's role
     * @return a {@link ClassMembersView} containing class info, member rows, and total count
     * @throws jakarta.persistence.EntityNotFoundException              if the class does not exist
     * @throws org.springframework.security.access.AccessDeniedException if the caller lacks access
     */
    @Transactional(readOnly = true)
    public ClassMembersView listForClass(Long classId, Long userId, Role role) {
        ClassEntity clazz = classesService.getViewable(classId, userId, role);
        List<Enrollment> enrollments = enrollmentRepository
                .findAllByClassIdAndStatusOrderByJoinedAtDesc(classId, Enrollment.STATUS_ACTIVE);

        List<MemberRow> rows = IntStream.range(0, enrollments.size())
                .mapToObj(i -> toRow(enrollments.get(i), i))
                .toList();

        return new ClassMembersView(clazz, rows, rows.size());
    }

    private static MemberRow toRow(Enrollment e, int index) {
        User u = e.getUser();
        return new MemberRow(
                u.getId(),
                u.getFullName(),
                AvatarStyles.label(u.getFullName()),
                AvatarStyles.gradient(index),
                u.getEmail(),
                u.getPhone(),
                e.getJoinedVia()
        );
    }

    /**
     * View model aggregating class info and member rows for the Members tab.
     *
     * @param clazz   the target class entity
     * @param members active member rows
     * @param total   active-member count
     */
    public record ClassMembersView(ClassEntity clazz,
                                   List<MemberRow> members,
                                   int total) {}
}
