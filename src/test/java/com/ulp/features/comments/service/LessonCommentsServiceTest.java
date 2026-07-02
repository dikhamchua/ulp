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
import com.ulp.features.comments.dto.LessonCommentsDtos.CommentPageView;
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

        List<CommentRow> rows = listRoots(lesson.getId(), student.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).replies()).hasSize(1);
        assertThat(rows.get(0).replies().get(0).parentId()).isEqualTo(root.id());
    }

    @Test
    void reply_to_reply_creates_third_level() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Gốc", null);
        CommentRow reply = service.create(lesson.getId(), other.getId(), "Trả lời 1", root.id());

        // Level-3 reply parents onto the level-2 reply (no longer flattened).
        CommentRow deep = service.create(lesson.getId(), student.getId(), "Trả lời của trả lời", reply.id());

        assertThat(deep.parentId()).isEqualTo(reply.id());
    }

    @Test
    void reply_beyond_third_level_clamps_to_third() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "L1", null);
        CommentRow lvl2 = service.create(lesson.getId(), other.getId(), "L2", root.id());
        CommentRow lvl3 = service.create(lesson.getId(), student.getId(), "L3", lvl2.id());

        // A reply to a depth-3 node re-parents to that node's parent (stays depth 3).
        CommentRow lvl4 = service.create(lesson.getId(), other.getId(), "L4", lvl3.id());

        assertThat(lvl4.parentId()).isEqualTo(lvl2.id());
    }

    @Test
    void three_level_tree_assembles() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "L1", null);
        CommentRow lvl2 = service.create(lesson.getId(), other.getId(), "L2", root.id());
        service.create(lesson.getId(), student.getId(), "L3", lvl2.id());

        List<CommentRow> rows = listRoots(lesson.getId(), student.getId());

        assertThat(rows).hasSize(1);
        CommentRow r1 = rows.get(0);
        assertThat(r1.replies()).hasSize(1);
        CommentRow r2 = r1.replies().get(0);
        assertThat(r2.parentId()).isEqualTo(root.id());
        assertThat(r2.replies()).hasSize(1);
        assertThat(r2.replies().get(0).parentId()).isEqualTo(lvl2.id());
    }

    @Test
    void row_carries_avatar_fields() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Có avatar", null);

        // Seeded test students have no avatar URL → initials + gradient fallback.
        assertThat(root.authorAvatarUrl()).isNull();
        assertThat(root.avatarLabel()).isNotBlank();
        assertThat(root.avatarGradient()).startsWith("linear-gradient(");
    }

    @Test
    void deleted_mid_node_keeps_live_grandchild() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "L1", null);
        CommentRow lvl2 = service.create(lesson.getId(), other.getId(), "L2 sẽ xoá", root.id());
        service.create(lesson.getId(), student.getId(), "L3 còn sống", lvl2.id());
        service.delete(lesson.getId(), lvl2.id(), other.getId());

        List<CommentRow> rows = listRoots(lesson.getId(), student.getId());

        assertThat(rows).hasSize(1);
        CommentRow placeholder = rows.get(0).replies().get(0);
        assertThat(placeholder.deleted()).isTrue();
        assertThat(placeholder.content()).isNull();
        assertThat(placeholder.avatarLabel()).isNull();
        assertThat(placeholder.replies()).hasSize(1);
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

        assertThat(listRoots(lesson.getId(), student.getId())).isEmpty();
    }

    @Test
    void lecturer_deletes_any() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Sinh viên viết", null);

        service.delete(lesson.getId(), root.id(), lecturer.getId());

        assertThat(listRoots(lesson.getId(), student.getId())).isEmpty();
    }

    @Test
    void other_student_delete_denied() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Của tôi", null);

        assertThatThrownBy(() -> service.delete(lesson.getId(), root.id(), other.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(listRoots(lesson.getId(), student.getId())).hasSize(1);
    }

    @Test
    void deleted_root_drops_whole_thread() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Gốc bị xoá", null);
        service.create(lesson.getId(), other.getId(), "Trả lời còn sống", root.id());
        service.delete(lesson.getId(), root.id(), student.getId());

        // Option A: a deleted root is excluded entirely; its replies go with it.
        assertThat(listRoots(lesson.getId(), student.getId())).isEmpty();
    }

    @Test
    void deleted_leaf_reply_is_omitted() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Gốc", null);
        CommentRow reply = service.create(lesson.getId(), other.getId(), "Trả lời bị xoá", root.id());
        service.delete(lesson.getId(), reply.id(), other.getId());

        List<CommentRow> rows = listRoots(lesson.getId(), student.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).deleted()).isFalse();
        assertThat(rows.get(0).replies()).isEmpty();
    }

    @Test
    void draft_lesson_denied() {
        assertThatThrownBy(() -> listRoots(draft.getId(), student.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.create(draft.getId(), student.getId(), "Hi", null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void outsider_denied() {
        User outsider = ensureUser("comment-outsider@ulp.edu.vn", "Outsider");
        assertThatThrownBy(() -> listRoots(lesson.getId(), outsider.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── Pagination ────────────────────────────────────────────────────

    @Test
    void first_page_returns_newest_roots_and_flags_next() {
        // 12 roots (> size 5); creation order 0..11, so id 11 is newest.
        for (int i = 0; i < 12; i++) {
            service.create(lesson.getId(), student.getId(), "Gốc " + i, null);
        }

        CommentPageView page0 = service.listPage(lesson.getId(), student.getId(), 0, 5);

        assertThat(page0.comments()).hasSize(5);
        assertThat(page0.totalRoots()).isEqualTo(12);
        assertThat(page0.page()).isZero();
        assertThat(page0.size()).isEqualTo(5);
        assertThat(page0.hasNext()).isTrue();
        // Newest-first: the last-created root leads the first page.
        assertThat(page0.comments().get(0).content()).isEqualTo("Gốc 11");
        assertThat(page0.comments().get(4).content()).isEqualTo("Gốc 7");
    }

    @Test
    void last_page_returns_remainder_and_clears_next() {
        for (int i = 0; i < 12; i++) {
            service.create(lesson.getId(), student.getId(), "Gốc " + i, null);
        }

        CommentPageView page2 = service.listPage(lesson.getId(), student.getId(), 2, 5);

        // 12 roots / size 5 → page 2 holds the final two, oldest at the tail.
        assertThat(page2.comments()).hasSize(2);
        assertThat(page2.hasNext()).isFalse();
        assertThat(page2.comments().get(0).content()).isEqualTo("Gốc 1");
        assertThat(page2.comments().get(1).content()).isEqualTo("Gốc 0");
    }

    @Test
    void page_drags_full_reply_tree() {
        // Older root with a reply, then a newer root; page size 1 isolates page 0.
        CommentRow oldRoot = service.create(lesson.getId(), student.getId(), "Gốc cũ", null);
        service.create(lesson.getId(), other.getId(), "Trả lời cũ", oldRoot.id());
        service.create(lesson.getId(), student.getId(), "Gốc mới", null);

        CommentPageView page1 = service.listPage(lesson.getId(), student.getId(), 1, 1);

        // Page 1 (second-newest) is the old root and must carry its reply subtree.
        assertThat(page1.comments()).hasSize(1);
        assertThat(page1.comments().get(0).content()).isEqualTo("Gốc cũ");
        assertThat(page1.comments().get(0).replies()).hasSize(1);
        assertThat(page1.comments().get(0).replies().get(0).content()).isEqualTo("Trả lời cũ");
    }

    @Test
    void deleted_root_excluded_from_page_and_count() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Gốc sẽ xoá", null);
        service.create(lesson.getId(), other.getId(), "Trả lời sống", root.id());
        service.delete(lesson.getId(), root.id(), student.getId());

        CommentPageView page0 = service.listPage(lesson.getId(), student.getId(), 0, 5);

        // Option A: deleted root leaves neither a placeholder nor a count entry,
        // so an all-deleted thread reads as a clean empty state (no phantom pager).
        assertThat(page0.totalRoots()).isZero();
        assertThat(page0.comments()).isEmpty();
        assertThat(page0.hasNext()).isFalse();
    }

    @Test
    void deleted_root_excluded_from_count() {
        service.create(lesson.getId(), student.getId(), "Gốc sống", null);
        CommentRow dead = service.create(lesson.getId(), student.getId(), "Gốc chết", null);
        service.delete(lesson.getId(), dead.id(), student.getId());

        CommentPageView page0 = service.listPage(lesson.getId(), student.getId(), 0, 5);

        // Only the live root counts; the deleted root is dropped from page + count.
        assertThat(page0.totalRoots()).isEqualTo(1);
        assertThat(page0.comments()).hasSize(1);
        assertThat(page0.comments().get(0).content()).isEqualTo("Gốc sống");
    }

    @Test
    void zero_size_falls_back_to_default() {
        service.create(lesson.getId(), student.getId(), "Gốc", null);

        CommentPageView page0 = service.listPage(lesson.getId(), student.getId(), 0, 0);

        // size 0 → service default (DEFAULT_COMMENT_PAGE_SIZE).
        assertThat(page0.size()).isEqualTo(10);
        assertThat(page0.comments()).hasSize(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Reads the first, generously-sized root page and returns its rows. */
    private List<CommentRow> listRoots(Long lessonId, Long userId) {
        return service.listPage(lessonId, userId, 0, 50).comments();
    }

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
