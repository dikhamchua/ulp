package com.ulp.features.comments.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.comments.dto.LessonCommentsDtos.CommentRow;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link LessonCommentsService} (ULP-4.6): threading,
 * validation, authz, ownership, and soft-delete placeholder rules.
 */
@SpringBootTest
@Transactional
class LessonCommentsServiceTest {

    @Autowired private LessonCommentsService service;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;

    private User lecturer;
    private User student;
    private User other;
    private ClassEntity clazz;
    private Section section;
    private Lesson lesson;
    private Lesson draft;
    private short orderSeq;

    @BeforeEach
    void setUp() {
        orderSeq = 0;
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        student = ensureUser("comment-author@ulp.edu.vn", "Comment Author");
        other = ensureUser("comment-other@ulp.edu.vn", "Comment Other");
        clazz = saveClass("Comments class", "CMTCLS");
        section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
        lesson = persistLesson("Bài 1", true);
        draft = persistLesson("Bài nháp", false);
        enroll(student);
        enroll(other);
    }

    @Test
    void create_root_and_reply_thread() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Câu hỏi?", null);
        assertThat(root.parentId()).isNull();
        assertThat(root.canEdit()).isTrue();
        assertThat(root.canDelete()).isTrue();

        service.create(lesson.getId(), other.getId(), "Trả lời", root.id());

        List<CommentRow> rows = service.list(lesson.getId(), student.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).replies()).hasSize(1);
        assertThat(rows.get(0).replies().get(0).parentId()).isEqualTo(root.id());
    }

    @Test
    void reply_to_reply_flattens_to_root() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Gốc", null);
        CommentRow reply = service.create(lesson.getId(), other.getId(), "Trả lời 1", root.id());

        CommentRow deep = service.create(lesson.getId(), student.getId(), "Trả lời của trả lời", reply.id());

        assertThat(deep.parentId()).isEqualTo(root.id());
    }

    @Test
    void blank_content_rejected() {
        assertThatThrownBy(() -> service.create(lesson.getId(), student.getId(), "   ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void over_limit_content_rejected() {
        String tooLong = "a".repeat(2001);
        assertThatThrownBy(() -> service.create(lesson.getId(), student.getId(), tooLong, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cross_lesson_parent_rejected() {
        Lesson other2 = persistLesson("Bài 2", true);
        CommentRow rootInOther = service.create(other2.getId(), student.getId(), "Gốc khác", null);

        assertThatThrownBy(() -> service.create(
                lesson.getId(), student.getId(), "Trả lời sai lesson", rootInOther.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void author_edits_own_sets_edited() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Cũ", null);

        CommentRow edited = service.editOwn(lesson.getId(), root.id(), student.getId(), "Mới");

        assertThat(edited.content()).isEqualTo("Mới");
        assertThat(edited.edited()).isTrue();
    }

    @Test
    void non_author_edit_denied() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Cũ", null);

        assertThatThrownBy(() -> service.editOwn(lesson.getId(), root.id(), other.getId(), "Hack"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void author_deletes_own() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Xoá tôi", null);

        service.delete(lesson.getId(), root.id(), student.getId());

        assertThat(service.list(lesson.getId(), student.getId())).isEmpty();
    }

    @Test
    void lecturer_deletes_any() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Sinh viên viết", null);

        service.delete(lesson.getId(), root.id(), lecturer.getId());

        assertThat(service.list(lesson.getId(), student.getId())).isEmpty();
    }

    @Test
    void other_student_delete_denied() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Của tôi", null);

        assertThatThrownBy(() -> service.delete(lesson.getId(), root.id(), other.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(service.list(lesson.getId(), student.getId())).hasSize(1);
    }

    @Test
    void deleted_root_with_live_reply_becomes_placeholder() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Gốc bị xoá", null);
        service.create(lesson.getId(), other.getId(), "Trả lời còn sống", root.id());
        service.delete(lesson.getId(), root.id(), student.getId());

        List<CommentRow> rows = service.list(lesson.getId(), student.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).deleted()).isTrue();
        assertThat(rows.get(0).content()).isNull();
        assertThat(rows.get(0).replies()).hasSize(1);
    }

    @Test
    void deleted_leaf_reply_is_omitted() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Gốc", null);
        CommentRow reply = service.create(lesson.getId(), other.getId(), "Trả lời bị xoá", root.id());
        service.delete(lesson.getId(), reply.id(), other.getId());

        List<CommentRow> rows = service.list(lesson.getId(), student.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).deleted()).isFalse();
        assertThat(rows.get(0).replies()).isEmpty();
    }

    @Test
    void draft_lesson_denied() {
        assertThatThrownBy(() -> service.list(draft.getId(), student.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.create(draft.getId(), student.getId(), "Hi", null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void outsider_denied() {
        User outsider = ensureUser("comment-outsider@ulp.edu.vn", "Outsider");
        assertThatThrownBy(() -> service.list(lesson.getId(), outsider.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Lesson persistLesson(String title, boolean published) {
        Lesson l = new Lesson(section.getId(), title, orderSeq++, lecturer.getId());
        if (published) l.publish();
        return lessonRepository.saveAndFlush(l);
    }

    private void enroll(User u) {
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                u, clazz.getId(), Enrollment.JoinedVia.CODE, null));
    }

    private ClassEntity saveClass(String name, String code) {
        ClassEntity entity = new ClassEntity(name, lecturer.getId(), lecturer.getId(),
                null, null, null, 100);
        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "x");
            return classRepository.saveAndFlush(entity);
        }
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = UserFactory.newAdminCreated(email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    name, Role.STUDENT, true, null, null);
            return userRepository.saveAndFlush(u);
        });
    }
}
