package com.ulp.features.classes.service;

import com.ulp.security.Role;
import com.ulp.entities.User;
import com.ulp.features.classes.dto.MemberDtos.MemberRow;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.utils.AvatarStyles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Read service for the member list and pending join requests within a class.
 *
 * <p>Authorization is delegated to {@link ClassesService#getViewable}.
 * Approve/reject mutations live on {@link JoinClassService}.
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
     * Returns ACTIVE members and PENDING join requests for the given class.
     */
    @Transactional(readOnly = true)
    public ClassMembersView listForClass(Long classId, Long userId, Role role) {
        ClassEntity clazz = classesService.getViewable(classId, userId, role);
        List<Enrollment> active = enrollmentRepository
                .findAllByClassIdAndStatusOrderByJoinedAtDesc(classId, Enrollment.STATUS_ACTIVE);
        List<Enrollment> pending = enrollmentRepository
                .findAllByClassIdAndStatusOrderByJoinedAtDesc(classId, Enrollment.STATUS_PENDING);

        List<MemberRow> members = IntStream.range(0, active.size())
                .mapToObj(i -> toRow(active.get(i), i))
                .toList();
        List<MemberRow> pendingRows = IntStream.range(0, pending.size())
                .mapToObj(i -> toRow(pending.get(i), i + active.size()))
                .toList();

        return new ClassMembersView(clazz, members, members.size(), pendingRows, pendingRows.size());
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
     * View model for the Members tab.
     *
     * @param clazz          the target class
     * @param members        ACTIVE member rows
     * @param total          ACTIVE count
     * @param pendingMembers PENDING request rows
     * @param pendingTotal   PENDING count
     */
    public record ClassMembersView(ClassEntity clazz,
                                   List<MemberRow> members,
                                   int total,
                                   List<MemberRow> pendingMembers,
                                   int pendingTotal) {}
}
