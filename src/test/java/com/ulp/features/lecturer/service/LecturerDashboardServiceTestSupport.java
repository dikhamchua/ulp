package com.ulp.features.lecturer.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.LearningProgress;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.progress.repository.LearningProgressRepository;
import com.ulp.security.Role;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

/** Shared fixture helpers for {@link LecturerDashboardServiceTest}. */
@Component
class LecturerDashboardServiceTestSupport {

    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final LessonRepository lessonRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final LearningProgressRepository progressRepository;
    private final UserRepository userRepository;

    LecturerDashboardServiceTestSupport(ClassRepository classRepository,
                                        SectionRepository sectionRepository,
                                        LessonRepository lessonRepository,
                                        EnrollmentRepository enrollmentRepository,
                                        LearningProgressRepository progressRepository,
                                        UserRepository userRepository) {
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.lessonRepository = lessonRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.progressRepository = progressRepository;
        this.userRepository = userRepository;
    }

    void markStatus(ClassEntity entity, String status) {
        ReflectionTestUtils.setField(entity, "status", status);
        classRepository.saveAndFlush(entity);
    }

    void complete(User student, List<Lesson> lessons) {
        for (Lesson l : lessons) {
            LearningProgress lp = new LearningProgress(student.getId(), l.getId());
            lp.markCompleted();
            progressRepository.saveAndFlush(lp);
        }
    }

    Lesson persistLesson(Long sectionId, String title, short order,
                         boolean published, Long authorId) {
        Lesson l = new Lesson(sectionId, title, order, authorId);
        if (published) {
            l.publish();
        }
        return lessonRepository.saveAndFlush(l);
    }

    Section persistSection(Long classId, String title, short order, Long authorId) {
        return sectionRepository.saveAndFlush(new Section(classId, title, order, authorId));
    }

    User enroll(ClassEntity clazz, String email, String name) {
        User u = ensureUser(email, name, Role.STUDENT);
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                u, clazz.getId(), Enrollment.JoinedVia.CODE, null));
        return u;
    }

    ClassEntity saveClass(User owner, String name, String code) {
        ClassEntity entity = new ClassEntity(name, owner.getId(), owner.getId(),
                null, null, null, 100);
        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "x");
            return classRepository.saveAndFlush(entity);
        }
    }

    User ensureUser(String email, String name, Role role) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = UserFactory.newAdminCreated(email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    name, role, true, null, null);
            return userRepository.saveAndFlush(u);
        });
    }

    User requireUser(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }
}
