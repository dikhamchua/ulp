package com.ulp.features.student.service;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.student.dto.StudentClassesDtos.EnrolledClassRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read service that powers {@code GET /my/classes}.
 *
 * <p>ACTIVE enrollments appear as full class rows; PENDING enrollments appear
 * as "đang chờ duyệt" without content entry links.
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

    /** ACTIVE enrolled classes, most recent join first. Soft-deleted classes hidden. */
    @Transactional(readOnly = true)
    public List<EnrolledClassRow> listEnrolledClasses(Long userId) {
        return mapRows(enrollmentRepository
                .findAllByUserIdAndStatusOrderByJoinedAtDesc(userId, Enrollment.STATUS_ACTIVE));
    }

    /** PENDING join requests for the student (awaiting owner approval). */
    @Transactional(readOnly = true)
    public List<EnrolledClassRow> listPendingClasses(Long userId) {
        return mapRows(enrollmentRepository
                .findAllByUserIdAndStatusOrderByJoinedAtDesc(userId, Enrollment.STATUS_PENDING));
    }

    private List<EnrolledClassRow> mapRows(List<Enrollment> enrollments) {
        if (enrollments.isEmpty()) {
            return List.of();
        }

        List<Long> classIds = enrollments.stream().map(Enrollment::getClassId).distinct().toList();
        Map<Long, ClassEntity> classById = new HashMap<>();
        for (ClassEntity c : classRepository.findAllById(classIds)) {
            classById.put(c.getId(), c);
        }

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
            // Soft-deleted class → hide row.
            if (c == null) continue;
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
