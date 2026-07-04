package com.ulp.features.comments.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Comment;
import com.ulp.entities.CommentModeration;
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
import com.ulp.features.comments.repository.CommentModerationRepository;
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
    @Autowired private CommentModerationRepository moderationRepository;

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

    // ── Moderation: hide / unhide (ULP-11.7) ──────────────────────────

    @Test
    void lecturer_hides_comment_hidden_for_student_flagged_for_moderator() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Bình luận xấu", null);

        service.hide(lesson.getId(), root.id(), lecturer.getId(), Role.LECTURER);

        // Student never sees a hidden comment.
        assertThat(listRoots(lesson.getId(), student.getId())).isEmpty();
        // Moderator sees it, flagged hidden + moderatable.
        List<CommentRow> modRows = listAsModerator(lesson.getId(), lecturer.getId(), Role.LECTURER);
        assertThat(modRows).hasSize(1);
        assertThat(modRows.get(0).hidden()).isTrue();
        assertThat(modRows.get(0).canModerate()).isTrue();
    }

    @Test
    void hidden_root_drops_thread_for_student() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Gốc bị ẩn", null);
        service.create(lesson.getId(), other.getId(), "Trả lời còn sống", root.id());

        service.hide(lesson.getId(), root.id(), lecturer.getId(), Role.LECTURER);

        // A hidden root removes the whole thread for students (like a deleted root).
        assertThat(listRoots(lesson.getId(), student.getId())).isEmpty();
    }

    @Test
    void moderator_sees_visible_comment_as_moderatable() {
        service.create(lesson.getId(), student.getId(), "Bình luận thường", null);

        List<CommentRow> modRows = listAsModerator(lesson.getId(), lecturer.getId(), Role.LECTURER);

        assertThat(modRows).hasSize(1);
        assertThat(modRows.get(0).hidden()).isFalse();
        assertThat(modRows.get(0).canModerate()).isTrue();
    }

    @Test
    void student_sees_no_moderate_flag() {
        service.create(lesson.getId(), student.getId(), "Bình luận thường", null);

        List<CommentRow> rows = listRoots(lesson.getId(), student.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).canModerate()).isFalse();
        assertThat(rows.get(0).hidden()).isFalse();
    }

    @Test
    void unhide_restores_comment_for_student() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Ẩn rồi hiện lại", null);
        service.hide(lesson.getId(), root.id(), lecturer.getId(), Role.LECTURER);

        service.unhide(lesson.getId(), root.id(), lecturer.getId(), Role.LECTURER);

        List<CommentRow> rows = listRoots(lesson.getId(), student.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).content()).isEqualTo("Ẩn rồi hiện lại");
    }

    @Test
    void admin_not_enrolled_can_hide() {
        User admin = ensureUser("comment-admin@ulp.edu.vn", "Comment Admin", Role.ADMIN);
        CommentRow root = service.create(lesson.getId(), student.getId(), "Admin sẽ ẩn", null);

        service.hide(lesson.getId(), root.id(), admin.getId(), Role.ADMIN);

        // ADMIN (not enrolled) can both moderate and view the hidden thread.
        List<CommentRow> modRows = listAsModerator(lesson.getId(), admin.getId(), Role.ADMIN);
        assertThat(modRows).hasSize(1);
        assertThat(modRows.get(0).hidden()).isTrue();
        assertThat(listRoots(lesson.getId(), student.getId())).isEmpty();
    }

    @Test
    void head_not_enrolled_can_hide() {
        User head = ensureUser("comment-head@ulp.edu.vn", "Comment Head", Role.HEAD);
        CommentRow root = service.create(lesson.getId(), student.getId(), "Head sẽ ẩn", null);

        service.hide(lesson.getId(), root.id(), head.getId(), Role.HEAD);

        assertThat(listRoots(lesson.getId(), student.getId())).isEmpty();
    }

    @Test
    void student_hide_denied() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Của tôi", null);

        assertThatThrownBy(() -> service.hide(lesson.getId(), root.id(), other.getId(), Role.STUDENT))
                .isInstanceOf(AccessDeniedException.class);
        // Status unchanged: still visible to students.
        assertThat(listRoots(lesson.getId(), student.getId())).hasSize(1);
    }

    @Test
    void student_unhide_denied() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Của tôi", null);
        service.hide(lesson.getId(), root.id(), lecturer.getId(), Role.LECTURER);

        assertThatThrownBy(() -> service.unhide(lesson.getId(), root.id(), student.getId(), Role.STUDENT))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void hide_is_idempotent() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Ẩn hai lần", null);
        service.hide(lesson.getId(), root.id(), lecturer.getId(), Role.LECTURER);

        // Second hide is a no-op success (no duplicate audit row).
        service.hide(lesson.getId(), root.id(), lecturer.getId(), Role.LECTURER);

        assertThat(auditRowsFor(root.id(), CommentModeration.ACTION_REJECTED)).isEqualTo(1);
    }

    @Test
    void unhide_already_visible_is_idempotent() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Chưa ẩn", null);

        // Unhide an APPROVED comment: success, no state change, no audit row.
        service.unhide(lesson.getId(), root.id(), lecturer.getId(), Role.LECTURER);

        assertThat(auditRowsFor(root.id(), CommentModeration.ACTION_APPROVED)).isZero();
        assertThat(listRoots(lesson.getId(), student.getId())).hasSize(1);
    }

    @Test
    void hide_writes_audit_row_with_moderator_and_action() {
        CommentRow root = service.create(lesson.getId(), student.getId(), "Ghi lịch sử", null);

        service.hide(lesson.getId(), root.id(), lecturer.getId(), Role.LECTURER);

        CommentModeration audit = moderationRepository.findAll().stream()
                .filter(m -> m.getCommentId().equals(root.id()))
                .findFirst().orElseThrow();
        assertThat(audit.getModeratedBy()).isEqualTo(lecturer.getId());
        assertThat(audit.getAction()).isEqualTo(CommentModeration.ACTION_REJECTED);
    }

    @Test
    void hide_foreign_comment_returns_404() {
        Lesson other2 = persistLesson("Bài 2", true);
        CommentRow foreign = service.create(other2.getId(), student.getId(), "Ở lesson khác", null);

        // Target a comment that does not belong to `lesson` → 404, no existence leak.
        assertThatThrownBy(() ->
                service.hide(lesson.getId(), foreign.id(), lecturer.getId(), Role.LECTURER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── Pagination ────────────────────────────────────────────────────

    @Test
    void first_page_returns_newest_roots_and_flags_next() {
        // 12 roots (> size 5); creation order 0..11, so id 11 is newest.
        for (int i = 0; i < 12; i++) {
            service.create(lesson.getId(), student.getId(), "Gốc " + i, null);
        }

        CommentPageView page0 = service.listPage(lesson.getId(), student.getId(), Role.STUDENT,0, 5);

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

        CommentPageView page2 = service.listPage(lesson.getId(), student.getId(), Role.STUDENT,2, 5);

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

        CommentPageView page1 = service.listPage(lesson.getId(), student.getId(), Role.STUDENT,1, 1);

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

        CommentPageView page0 = service.listPage(lesson.getId(), student.getId(), Role.STUDENT,0, 5);

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

        CommentPageView page0 = service.listPage(lesson.getId(), student.getId(), Role.STUDENT,0, 5);

        // Only the live root counts; the deleted root is dropped from page + count.
        assertThat(page0.totalRoots()).isEqualTo(1);
        assertThat(page0.comments()).hasSize(1);
        assertThat(page0.comments().get(0).content()).isEqualTo("Gốc sống");
    }

    @Test
    void zero_size_falls_back_to_default() {
        service.create(lesson.getId(), student.getId(), "Gốc", null);

        CommentPageView page0 = service.listPage(lesson.getId(), student.getId(), Role.STUDENT,0, 0);

        // size 0 → service default (DEFAULT_COMMENT_PAGE_SIZE).
        assertThat(page0.size()).isEqualTo(10);
        assertThat(page0.comments()).hasSize(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Reads the first, generously-sized root page (student view) and returns its rows. */
    private List<CommentRow> listRoots(Long lessonId, Long userId) {
        return service.listPage(lessonId, userId, Role.STUDENT, 0, 50).comments();
    }

    /** Reads the first root page from a moderator's perspective (includes hidden). */
    private List<CommentRow> listAsModerator(Long lessonId, Long userId, Role role) {
        return service.listPage(lessonId, userId, role, 0, 50).comments();
    }

    /** Counts audit rows written for a comment with the given action. */
    private long auditRowsFor(Long commentId, String action) {
        return moderationRepository.findAll().stream()
                .filter(m -> m.getCommentId().equals(commentId) && action.equals(m.getAction()))
                .count();
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
        return ensureUser(email, name, Role.STUDENT);
    }

    private User ensureUser(String email, String name, Role role) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = UserFactory.newAdminCreated(email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    name, role, true, null, null);
            return userRepository.saveAndFlush(u);
        });
    }
}
