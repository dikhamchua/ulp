package com.ulp.classes.service;

import com.ulp.auth.entity.User;
import com.ulp.classes.dto.MemberDtos.MemberRow;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.entity.Enrollment;
import com.ulp.classes.repository.EnrollmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.List;

/**
 * Read-only service for managing the member (student) list within a single class.
 * Sprint 2 exposes READ only; full member CRUD (add/remove/import) is deferred to Sprint 2.1.
 *
 * <p>Authorization is delegated to {@link ClassesService#getViewable} to verify
 * the caller has access to the class before returning its members.
 */
@Service
public class ClassMembersService {

    private static final int AVATAR_HUE_COUNT = 5;
    private static final String[][] AVATAR_GRADIENTS = {
            {"#5E92F3", "#1E88E5"},
            {"#EC407A", "#D81B60"},
            {"#26A69A", "#00897B"},
            {"#FFA726", "#FB8C00"},
            {"#7E57C2", "#5E35B1"}
    };

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
     * @param classId   the ID of the class whose members are being retrieved
     * @param principal the currently authenticated user
     * @return a {@link ClassMembersView} containing class info, member rows, and total count
     * @throws jakarta.persistence.EntityNotFoundException              if the class does not exist
     * @throws org.springframework.security.access.AccessDeniedException if the caller lacks access
     */
    @Transactional(readOnly = true)
    public ClassMembersView listForClass(Long classId, Principal principal) {
        ClassEntity clazz = classesService.getViewable(classId, principal);
        List<Enrollment> enrollments = enrollmentRepository
                .findAllByClassIdAndStatusOrderByJoinedAtDesc(classId, Enrollment.STATUS_ACTIVE);

        List<MemberRow> rows = enrollments.stream()
                .map(e -> toRow(e, indexOf(enrollments, e)))
                .toList();

        return new ClassMembersView(clazz, rows, rows.size());
    }

    private static int indexOf(List<Enrollment> list, Enrollment target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) return i;
        }
        return 0;
    }

    private static MemberRow toRow(Enrollment e, int index) {
        User u = e.getUser();
        String[] colors = AVATAR_GRADIENTS[Math.floorMod(index, AVATAR_HUE_COUNT)];
        String gradient = "linear-gradient(135deg," + colors[0] + "," + colors[1] + ")";
        return new MemberRow(
                u.getId(),
                u.getFullName(),
                avatarLabel(u.getFullName()),
                gradient,
                u.getEmail(),
                u.getPhone(),
                e.getJoinedVia()
        );
    }

    private static String avatarLabel(String fullName) {
        if (fullName == null || fullName.isBlank()) return "?";
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        String first = parts[0].substring(0, 1);
        String last = parts[parts.length - 1].substring(0, 1);
        return (first + last).toUpperCase();
    }

    /** View model aggregating class info, member rows, and total member count. */
    public record ClassMembersView(ClassEntity clazz, List<MemberRow> members, int total) {}
}
