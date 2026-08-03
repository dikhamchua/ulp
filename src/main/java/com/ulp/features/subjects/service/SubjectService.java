package com.ulp.features.subjects.service;

import com.ulp.entities.Department;
import com.ulp.features.admin.departments.repository.DepartmentRepository;
import com.ulp.features.head.service.HeadDepartmentResolver;
import com.ulp.features.subjects.dto.SubjectActivityRow;
import com.ulp.features.subjects.dto.SubjectDtos.DepartmentOption;
import com.ulp.features.subjects.dto.SubjectDtos.SubjectForm;
import com.ulp.features.subjects.dto.SubjectDtos.SubjectOption;
import com.ulp.features.subjects.dto.SubjectDtos.SubjectRow;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.entity.SubjectActivity;
import com.ulp.features.subjects.repository.SubjectActivityRepository;
import com.ulp.features.subjects.repository.SubjectRepository;
import com.ulp.security.Role;
import com.ulp.utils.StringUtils;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.ulp.common.IConstant.MSG_SUBJECT_CODE_EXISTS;
import static com.ulp.common.IConstant.MSG_SUBJECT_CREATE_FORBIDDEN;
import static com.ulp.common.IConstant.MSG_SUBJECT_CROSS_DEPARTMENT;
import static com.ulp.common.IConstant.MSG_SUBJECT_DEPT_REQUIRED;
import static com.ulp.common.IConstant.MSG_SUBJECT_INACTIVE_OR_MISSING;
import static com.ulp.common.IConstant.MSG_SUBJECT_MUTATE_FORBIDDEN;
import static com.ulp.common.IConstant.MSG_SUBJECT_NO_DEPARTMENT;
import static com.ulp.common.IConstant.MSG_SUBJECT_NOT_FOUND;
import static com.ulp.common.IConstant.MSG_SUBJECT_REQUIRED;
import static com.ulp.common.IConstant.MSG_SUBJECT_VIEW_FORBIDDEN;

/**
 * Department-scoped subject catalog: HEAD own dept, ADMIN any dept.
 * Also exposes active-subject options for lecturer class forms.
 */
@Service
public class SubjectService {

    private final SubjectRepository subjectRepository;
    private final SubjectActivityRepository activityRepository;
    private final DepartmentRepository departmentRepository;
    private final HeadDepartmentResolver headDepartmentResolver;
    private final SubjectAuditWriter auditWriter;

    public SubjectService(SubjectRepository subjectRepository,
                          SubjectActivityRepository activityRepository,
                          DepartmentRepository departmentRepository,
                          HeadDepartmentResolver headDepartmentResolver,
                          SubjectAuditWriter auditWriter) {
        this.subjectRepository = subjectRepository;
        this.activityRepository = activityRepository;
        this.departmentRepository = departmentRepository;
        this.headDepartmentResolver = headDepartmentResolver;
        this.auditWriter = auditWriter;
    }

    /**
     * Lists subjects visible to the actor.
     *
     * @return empty list when HEAD has no resolved department (caller renders empty state)
     */
    @Transactional(readOnly = true)
    public List<SubjectRow> listForActor(Long actorId, Role role) {
        if (role == Role.ADMIN) {
            return toRows(subjectRepository.findAllByOrderByCodeAsc());
        }
        if (role == Role.HEAD) {
            Optional<Department> dept = headDepartmentResolver.resolve(actorId);
            if (dept.isEmpty()) {
                return List.of();
            }
            return toRows(subjectRepository.findAllByDepartmentIdOrderByCodeAsc(dept.get().getId()));
        }
        throw new AccessDeniedException(MSG_SUBJECT_VIEW_FORBIDDEN);
    }

    /** True when HEAD has no resolved department (empty-state UI). */
    @Transactional(readOnly = true)
    public boolean isEmptyDepartmentForHead(Long headUserId) {
        return headDepartmentResolver.resolve(headUserId).isEmpty();
    }

    @Transactional(readOnly = true)
    public Optional<Department> resolveHeadDepartment(Long headUserId) {
        return headDepartmentResolver.resolve(headUserId);
    }

    @Transactional(readOnly = true)
    public SubjectForm loadForm(Long id, Long actorId, Role role) {
        Subject subject = requireScoped(id, actorId, role);
        return new SubjectForm(
                subject.getCode(),
                subject.getTitle(),
                subject.getDescription(),
                subject.isActive(),
                subject.getDepartmentId());
    }

    /** Human-readable department label for subject detail chrome, or null. */
    @Transactional(readOnly = true)
    public String departmentLabel(Long departmentId) {
        if (departmentId == null) {
            return null;
        }
        return departmentRepository.findById(departmentId)
                .map(d -> d.getCode() + " — " + d.getName())
                .orElse(null);
    }

    @Transactional
    public String create(SubjectForm form, Long actorId, Role role) {
        Long departmentId = resolveCreateDepartmentId(form, actorId, role);
        String code = normalizeCode(form.code());
        assertCodeUnique(departmentId, code, null);

        Subject entity = new Subject(
                departmentId,
                code,
                form.title().trim(),
                StringUtils.blankToNull(form.description()),
                form.active(),
                actorId);
        Subject saved = subjectRepository.save(entity);
        auditWriter.write(saved.getId(), SubjectActivity.TYPE_CREATED,
                "Tạo môn học " + saved.getCode() + " — " + saved.getTitle(), actorId);
        return saved.getTitle();
    }

    @Transactional
    public void update(Long id, SubjectForm form, Long actorId, Role role) {
        Subject entity = requireScoped(id, actorId, role);
        String code = normalizeCode(form.code());
        assertCodeUnique(entity.getDepartmentId(), code, id);
        boolean wasActive = entity.isActive();
        entity.applyEdit(
                code,
                form.title().trim(),
                StringUtils.blankToNull(form.description()),
                form.active());
        subjectRepository.save(entity);
        auditWriter.write(entity.getId(), SubjectActivity.TYPE_UPDATED,
                "Cập nhật môn học " + entity.getCode(), actorId);
        // Surface visibility flips from the edit form as activate/deactivate too.
        if (wasActive != entity.isActive()) {
            auditWriter.write(entity.getId(),
                    entity.isActive() ? SubjectActivity.TYPE_ACTIVATED : SubjectActivity.TYPE_DEACTIVATED,
                    entity.isActive() ? "Hiện môn học " + entity.getCode()
                            : "Ẩn môn học " + entity.getCode(),
                    actorId);
        }
    }

    @Transactional
    public boolean toggleActive(Long id, Long actorId, Role role) {
        Subject entity = requireScoped(id, actorId, role);
        boolean now = entity.toggleActive();
        subjectRepository.save(entity);
        auditWriter.write(entity.getId(),
                now ? SubjectActivity.TYPE_ACTIVATED : SubjectActivity.TYPE_DEACTIVATED,
                now ? "Hiện môn học " + entity.getCode() : "Ẩn môn học " + entity.getCode(),
                actorId);
        return now;
    }

    @Transactional
    public void softDelete(Long id, Long actorId, Role role) {
        Subject entity = requireScoped(id, actorId, role);
        entity.softDelete();
        subjectRepository.save(entity);
        auditWriter.write(entity.getId(), SubjectActivity.TYPE_DELETED,
                "Xoá môn học " + entity.getCode(), actorId);
    }

    /** Paged history for the subject detail history tab (lazy-loaded). */
    @Transactional(readOnly = true)
    public Page<SubjectActivityRow> listActivities(Long id, Long actorId, Role role, Pageable pageable) {
        requireScoped(id, actorId, role);
        return activityRepository.findActivitiesForSubject(id, pageable);
    }

    /**
     * Active subjects across all departments for the class create/edit picker.
     * Inactive placeholders (e.g. UNASSIGNED) are excluded.
     */
    @Transactional(readOnly = true)
    public List<SubjectOption> listActiveOptions() {
        List<Subject> subjects = subjectRepository.findAllByActiveTrueOrderByCodeAsc();
        Map<Long, Department> depts = loadDepartments(subjects);
        List<SubjectOption> options = new ArrayList<>(subjects.size());
        for (Subject s : subjects) {
            Department d = depts.get(s.getDepartmentId());
            String deptCode = d != null ? d.getCode() : "?";
            String label = "[" + deptCode + "] " + s.getCode() + " — " + s.getTitle();
            options.add(new SubjectOption(
                    s.getId(),
                    s.getDepartmentId(),
                    s.getCode(),
                    s.getTitle(),
                    deptCode,
                    label));
        }
        return options;
    }

    /**
     * Loads an active subject for class write paths.
     *
     * @throws SubjectValidationException when missing, deleted, or inactive
     */
    @Transactional(readOnly = true)
    public Subject requireActiveSubject(Long subjectId) {
        if (subjectId == null) {
            throw new SubjectValidationException(MSG_SUBJECT_REQUIRED);
        }
        return subjectRepository.findActiveById(subjectId)
                .orElseThrow(() -> new SubjectValidationException(MSG_SUBJECT_INACTIVE_OR_MISSING));
    }

    @Transactional(readOnly = true)
    public List<DepartmentOption> listDepartmentOptions() {
        return departmentRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(d -> new DepartmentOption(d.getId(), d.getCode(), d.getName()))
                .toList();
    }

    private Long resolveCreateDepartmentId(SubjectForm form, Long actorId, Role role) {
        if (role == Role.HEAD) {
            return headDepartmentResolver.resolve(actorId)
                    .map(Department::getId)
                    .orElseThrow(() -> new AccessDeniedException(MSG_SUBJECT_NO_DEPARTMENT));
        }
        if (role == Role.ADMIN) {
            if (form.departmentId() == null) {
                throw new SubjectValidationException(MSG_SUBJECT_DEPT_REQUIRED);
            }
            if (!departmentRepository.existsById(form.departmentId())) {
                throw new SubjectValidationException(MSG_SUBJECT_DEPT_REQUIRED);
            }
            return form.departmentId();
        }
        throw new AccessDeniedException(MSG_SUBJECT_CREATE_FORBIDDEN);
    }

    /**
     * Loads a subject and asserts the actor may view/mutate it (HEAD dept scope, ADMIN any).
     * Shared by chapter-outline operations on the same subject.
     */
    @Transactional(readOnly = true)
    public Subject requireScopedSubject(Long id, Long actorId, Role role) {
        return requireScoped(id, actorId, role);
    }

    private Subject requireScoped(Long id, Long actorId, Role role) {
        Subject subject = subjectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(MSG_SUBJECT_NOT_FOUND));
        assertCanMutate(subject, actorId, role);
        return subject;
    }

    private void assertCanMutate(Subject subject, Long actorId, Role role) {
        if (role == Role.ADMIN) {
            return;
        }
        if (role != Role.HEAD) {
            throw new AccessDeniedException(MSG_SUBJECT_MUTATE_FORBIDDEN);
        }
        Long headDeptId = headDepartmentResolver.resolve(actorId)
                .map(Department::getId)
                .orElseThrow(() -> new AccessDeniedException(MSG_SUBJECT_NO_DEPARTMENT));
        if (!headDeptId.equals(subject.getDepartmentId())) {
            throw new AccessDeniedException(MSG_SUBJECT_CROSS_DEPARTMENT);
        }
    }

    private void assertCodeUnique(Long departmentId, String code, Long excludeId) {
        boolean exists = excludeId == null
                ? subjectRepository.existsByDepartmentIdAndCodeIgnoreCase(departmentId, code)
                : subjectRepository.existsByDepartmentIdAndCodeIgnoreCaseAndIdNot(
                        departmentId, code, excludeId);
        if (exists) {
            throw new SubjectValidationException(MSG_SUBJECT_CODE_EXISTS);
        }
    }

    private static String normalizeCode(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private List<SubjectRow> toRows(List<Subject> subjects) {
        Map<Long, Department> depts = loadDepartments(subjects);
        List<SubjectRow> rows = new ArrayList<>(subjects.size());
        for (Subject s : subjects) {
            Department d = depts.get(s.getDepartmentId());
            rows.add(new SubjectRow(
                    s.getId(),
                    s.getCode(),
                    s.getTitle(),
                    s.getDescription(),
                    s.isActive(),
                    s.getDepartmentId(),
                    d != null ? d.getCode() : null,
                    d != null ? d.getName() : null));
        }
        return rows;
    }

    private Map<Long, Department> loadDepartments(List<Subject> subjects) {
        Map<Long, Department> map = new HashMap<>();
        for (Subject s : subjects) {
            Long deptId = s.getDepartmentId();
            if (deptId != null && !map.containsKey(deptId)) {
                departmentRepository.findById(deptId).ifPresent(d -> map.put(deptId, d));
            }
        }
        return map;
    }
}
