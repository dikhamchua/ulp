package com.ulp.student.service;

import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.entity.Enrollment;
import com.ulp.classes.repository.ClassRepository;
import com.ulp.classes.repository.EnrollmentRepository;
import com.ulp.student.dto.StudentClassesDtos.EnrolledClassRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read service that powers {@code GET /my/classes}.
 *
 * <p>Returns the caller's ACTIVE enrollments, joined to the class
 * row (filtered by {@code is_deleted=0} via {@code @SQLRestriction}
 * on {@link ClassEntity}) and the lecturer's full name (via
 * {@link UserRepository}). Soft-deleted classes are filtered out;
 * REMOVED / COMPLETED enrollments do not appear.
 */
@Service
public class StudentClassesService {

    private static final String[][] AVATAR_GRADIENTS = {
            {"#5E92F3", "#1E88E5"},
            {"#EC407A", "#D81B60"},
            {"#26A69A", "#00897B"},
            {"#FFA726", "#FB8C00"},
            {"#7E57C2", "#5E35B1"}
    };

    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;

    public StudentClassesService(EnrollmentRepository enrollmentRepository,
                                 ClassRepository classRepository,
                                 UserRepository userRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
    }

    /**
     * Returns the caller's currently-ACTIVE enrolled classes, most
     * recent join first. Soft-deleted classes are hidden.
     */
    @Transactional(readOnly = true)
    public List<EnrolledClassRow> listEnrolledClasses(Long userId) {
        List<Enrollment> enrollments = enrollmentRepository
                .findAllByUserIdAndStatusOrderByJoinedAtDesc(userId, Enrollment.STATUS_ACTIVE);
        if (enrollments.isEmpty()) {
            return List.of();
        }

        // Bulk-load the class rows; @SQLRestriction filters soft-deleted.
        List<Long> classIds = enrollments.stream().map(Enrollment::getClassId).distinct().toList();
        Map<Long, ClassEntity> classById = new HashMap<>();
        for (ClassEntity c : classRepository.findAllById(classIds)) {
            classById.put(c.getId(), c);
        }

        // Bulk-load lecturer names.
        List<Long> lecturerIds = classById.values().stream()
                .map(ClassEntity::getLecturerId).distinct().toList();
        Map<Long, String> lecturerNames = new HashMap<>();
        for (User u : userRepository.findAllById(lecturerIds)) {
            lecturerNames.put(u.getId(), u.getFullName());
        }

        List<EnrolledClassRow> rows = new ArrayList<>(enrollments.size());
        int idx = 0;
        for (Enrollment e : enrollments) {
            ClassEntity c = classById.get(e.getClassId());
            if (c == null) continue; // class soft-deleted → hide row
            String lecName = lecturerNames.getOrDefault(c.getLecturerId(), "—");
            String gradient = gradientFor(idx++);
            rows.add(new EnrolledClassRow(
                    c.getId(),
                    c.getName(),
                    c.getCode(),
                    lecName,
                    e.getJoinedAt(),
                    gradient
            ));
        }
        return rows;
    }

    private static String gradientFor(int index) {
        String[] colors = AVATAR_GRADIENTS[Math.floorMod(index, AVATAR_GRADIENTS.length)];
        return "linear-gradient(135deg," + colors[0] + "," + colors[1] + ")";
    }
}
