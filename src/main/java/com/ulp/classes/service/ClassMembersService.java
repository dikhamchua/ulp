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
 * Read-only service cho danh sach thanh vien (sinh vien) trong 1 lop hoc.
 * Sprint 2 chi expose READ; CRUD memnber (add/remove/import) de Sprint 2.1.
 *
 * <p>Phan quyen reuse {@link ClassesService#getViewable} de bao dam quyen
 * truy cap lop truoc khi tra ve members.
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
     * Tra ve danh sach thanh vien ACTIVE trong lop, da kiem tra quyen.
     * @throws jakarta.persistence.EntityNotFoundException neu lop khong ton tai
     * @throws org.springframework.security.access.AccessDeniedException neu khong co quyen
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

    /** View-model gop chung class info + members + count. */
    public record ClassMembersView(ClassEntity clazz, List<MemberRow> members, int total) {}
}
