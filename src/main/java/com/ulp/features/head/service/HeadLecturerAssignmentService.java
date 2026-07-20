package com.ulp.features.head.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Department;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.head.dto.HeadDtos.AssignClassRow;
import com.ulp.features.head.dto.HeadDtos.AssignView;
import com.ulp.features.head.dto.HeadDtos.DepartmentSummary;
import com.ulp.features.head.dto.HeadDtos.LecturerOption;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lists department classes and reassigns lecturers within the HEAD's department.
 */
@Service
public class HeadLecturerAssignmentService {

    private static final Set<Role> ELIGIBLE = Set.of(Role.LECTURER, Role.HEAD);

    private final HeadDepartmentResolver resolver;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;

    public HeadLecturerAssignmentService(HeadDepartmentResolver resolver,
                                         ClassRepository classRepository,
                                         UserRepository userRepository) {
        this.resolver = resolver;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public AssignView load(Long headUserId) {
        Optional<Department> deptOpt = resolver.resolve(headUserId);
        if (deptOpt.isEmpty()) {
            return new AssignView(null, List.of(), List.of(), true);
        }
        Department dept = deptOpt.get();
        List<ClassEntity> classes =
                classRepository.findAllByDepartmentIdOrderByCreatedAtDesc(dept.getId());
        Map<Long, String> names = loadNames(classes);
        List<AssignClassRow> rows = new ArrayList<>(classes.size());
        for (ClassEntity c : classes) {
            rows.add(new AssignClassRow(
                    c.getId(), c.getName(), c.getCode(),
                    c.getLecturerId(),
                    names.getOrDefault(c.getLecturerId(), "—")));
        }
        List<LecturerOption> lecturers = departmentLecturers(dept.getId());
        return new AssignView(
                new DepartmentSummary(dept.getId(), dept.getCode(), dept.getName()),
                rows, lecturers, false);
    }

    /**
     * Reassigns a class lecturer. Class must belong to the HEAD's department;
     * new lecturer must be active LECTURER/HEAD in the same department.
     * Does not change {@code department_id}.
     *
     * @return class display name for success toast
     */
    @Transactional
    public String reassign(Long headUserId, Long classId, Long newLecturerId) {
        Department dept = resolver.resolve(headUserId)
                .orElseThrow(() -> new AccessDeniedException("Không có bộ môn"));
        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy lớp"));
        if (clazz.getDepartmentId() == null
                || !clazz.getDepartmentId().equals(dept.getId())) {
            throw new AccessDeniedException("Lớp không thuộc bộ môn của bạn");
        }
        User lecturer = userRepository.findById(newLecturerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy giảng viên"));
        if (!lecturer.isActive() || lecturer.isDeleted()
                || !ELIGIBLE.contains(lecturer.getRole())
                || lecturer.getDepartmentId() == null
                || !lecturer.getDepartmentId().equals(dept.getId())) {
            throw new IllegalArgumentException(
                    "Giảng viên phải thuộc cùng bộ môn và đang hoạt động");
        }
        clazz.reassignLecturer(newLecturerId);
        classRepository.save(clazz);
        return clazz.getName();
    }

    private List<LecturerOption> departmentLecturers(Long departmentId) {
        return userRepository.findAll().stream()
                .filter(u -> u.isActive() && !u.isDeleted())
                .filter(u -> ELIGIBLE.contains(u.getRole()))
                .filter(u -> departmentId.equals(u.getDepartmentId()))
                .sorted((a, b) -> a.getFullName().compareToIgnoreCase(b.getFullName()))
                .map(u -> new LecturerOption(u.getId(), u.getFullName(), u.getEmail()))
                .toList();
    }

    private Map<Long, String> loadNames(List<ClassEntity> classes) {
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
