package com.ulp.features.comments.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Comment;
import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.comments.dto.LessonCommentsDtos.CommentPageView;
import com.ulp.features.comments.dto.LessonCommentsDtos.CommentRow;
import com.ulp.features.comments.repository.LessonCommentRepository;
import com.ulp.features.lessons.support.LessonAccessResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static com.ulp.common.IConstant.DEFAULT_COMMENT_PAGE_SIZE;
import static com.ulp.common.IConstant.MAX_COMMENT_PAGE_SIZE;
import static com.ulp.common.IConstant.MSG_COMMENT_BLANK;
import static com.ulp.common.IConstant.MSG_COMMENT_NOT_FOUND;
import static com.ulp.common.IConstant.MSG_COMMENT_PARENT_INVALID;
import static com.ulp.common.IConstant.MSG_COMMENT_TOO_LONG;

/**
 * Service for the lesson-comments feature (ULP-4.6).
 *
 * <p>Authorization mirrors {@code StudentLessonDetailService}'s gates
 * (canonical), plus a role check that also admits the class's owning
 * lecturer. Any gate/existence failure raises {@link EntityNotFoundException}
 * (mapped to 404) so lesson existence is never leaked; ownership failures
 * raise {@link AccessDeniedException} (mapped to 403).
 */
@Service
public class LessonCommentsService {

    private static final String NF_MSG = "Class not found or not accessible";
    private static final int MAX_CONTENT = 2000;
    private static final int MAX_DEPTH = 3;
    private static final int DEPTH_WALK_LIMIT = 5;

    private final EnrollmentRepository enrollmentRepository;
    private final LessonCommentRepository commentRepository;
    private final CommentThreadAssembler assembler;
    private final LessonAccessResolver lessonAccessResolver;

    public LessonCommentsService(EnrollmentRepository enrollmentRepository,
                                 LessonCommentRepository commentRepository,
                                 CommentThreadAssembler assembler,
                                 LessonAccessResolver lessonAccessResolver) {
        this.enrollmentRepository = enrollmentRepository;
        this.commentRepository = commentRepository;
        this.assembler = assembler;
        this.lessonAccessResolver = lessonAccessResolver;
    }

    /**
     * Returns one "load more" page of ROOT comments (newest-first) with their
     * full reply trees. Only non-deleted roots are paginated and counted; each
     * root drags along its entire descendant tree, loaded via two batched
     * IN-queries (levels 2 & 3). A deleted mid-thread node still renders as a
     * placeholder, but a deleted ROOT drops its whole thread from the list.
     */
    @Transactional(readOnly = true)
    public CommentPageView listPage(Long lessonId, Long userId, int page, int size) {
        ClassEntity clazz = authorize(lessonId, userId);

        // Clamp the client-supplied size: default when unset, capped so a huge
        // ?size can't force an oversized root page + IN () reply queries.
        int safeSize = size <= 0 ? DEFAULT_COMMENT_PAGE_SIZE
                : Math.min(size, MAX_COMMENT_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Page<Comment> rootPage = commentRepository
                .findByLessonIdAndParentIdIsNullAndDeletedFalseAndModerationStatus(
                        lessonId, Comment.MODERATION_APPROVED,
                        // id is a monotonic tiebreaker so same-second roots keep a
                        // stable order and never drift between pages.
                        PageRequest.of(safePage, safeSize,
                                Sort.by(Sort.Direction.DESC, "createdAt", "id")));

        List<Comment> roots = rootPage.getContent();       // newest-first
        List<Comment> level2 = repliesOf(idsOf(roots));    // depth-2 replies
        List<Comment> level3 = repliesOf(idsOf(level2));   // depth-3 replies

        // Keep roots first (in DESC page order) so the assembler renders them
        // newest-first; replies follow and are re-sorted ASC per thread there.
        List<Comment> combined = new ArrayList<>(roots.size() + level2.size() + level3.size());
        combined.addAll(roots);
        combined.addAll(level2);
        combined.addAll(level3);

        List<CommentRow> rows = assembler.assemble(combined, clazz.getLecturerId(), userId);
        return new CommentPageView(rows, safePage, safeSize,
                rootPage.getTotalElements(), rootPage.hasNext());
    }

    /** Creates a root comment or a reply and returns it. */
    @Transactional
    public CommentRow create(Long lessonId, Long userId, String rawContent, Long parentId) {
        ClassEntity clazz = authorize(lessonId, userId);
        String content = validateContent(rawContent);

        Long effectiveParent = null;
        if (parentId != null) {
            Comment parent = commentRepository.findById(parentId).orElse(null);
            boolean valid = parent != null && !parent.isDeleted()
                    && Comment.MODERATION_APPROVED.equals(parent.getModerationStatus())
                    && lessonId.equals(parent.getLessonId());
            if (!valid) {
                throw new IllegalArgumentException(MSG_COMMENT_PARENT_INVALID);
            }
            // Clamp thread depth to 3 (Facebook-style): a reply to a depth-3
            // node re-parents to its grandparent so nesting never exceeds 3.
            int depth = depthOf(parent);
            effectiveParent = depth < MAX_DEPTH ? parent.getId() : parent.getParentId();
        }

        Comment saved = commentRepository.saveAndFlush(
                new Comment(lessonId, userId, effectiveParent, content));
        return assembler.singleRow(saved, clazz.getLecturerId(), userId);
    }

    /** Edits the caller's own comment; sets is_edited. */
    @Transactional
    public CommentRow editOwn(Long lessonId, Long commentId, Long userId, String rawContent) {
        ClassEntity clazz = authorize(lessonId, userId);
        Comment comment = loadLiveComment(lessonId, commentId);
        if (!comment.getUserId().equals(userId)) {
            throw new AccessDeniedException("Not the comment author");
        }
        comment.edit(validateContent(rawContent));
        commentRepository.saveAndFlush(comment);
        return assembler.singleRow(comment, clazz.getLecturerId(), userId);
    }

    /** Soft-deletes a comment when the caller is its author or the owning lecturer. */
    @Transactional
    public void delete(Long lessonId, Long commentId, Long userId) {
        ClassEntity clazz = authorize(lessonId, userId);
        Comment comment = loadLiveComment(lessonId, commentId);
        boolean owner = comment.getUserId().equals(userId);
        boolean lecturer = clazz.getLecturerId().equals(userId);
        if (!owner && !lecturer) {
            throw new AccessDeniedException("Not allowed to delete this comment");
        }
        comment.markDeleted();
        commentRepository.saveAndFlush(comment);
    }

    /** Collects the ids of a comment list (helper for the level-by-level load). */
    private static List<Long> idsOf(List<Comment> comments) {
        List<Long> ids = new ArrayList<>(comments.size());
        for (Comment c : comments) {
            ids.add(c.getId());
        }
        return ids;
    }

    /** Batch-loads APPROVED replies of the given parents; guards empty IN (). */
    private List<Comment> repliesOf(List<Long> parentIds) {
        if (parentIds.isEmpty()) return List.of(); // MySQL IN () is invalid SQL
        return commentRepository.findByParentIdInAndModerationStatus(
                parentIds, Comment.MODERATION_APPROVED);
    }

    // ── Authorization ──────────────────────────────────────────────────

    /**
     * Runs the shared lesson gates (live class, section, PUBLISHED) then admits
     * an ACTIVE-enrolled student or the owning lecturer. Returns the live class.
     */
    private ClassEntity authorize(Long lessonId, Long userId) {
        ClassEntity clazz = lessonAccessResolver.resolveByLesson(lessonId).clazz();
        boolean lecturer = clazz.getLecturerId().equals(userId);
        boolean enrolled = enrollmentRepository
                .findByUserIdAndClassId(userId, clazz.getId())
                .filter(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()))
                .isPresent();
        if (!lecturer && !enrolled) {
            throw new EntityNotFoundException(NF_MSG);
        }
        return clazz;
    }

    /** Loads a live comment of the given lesson or 404s. */
    private Comment loadLiveComment(Long lessonId, Long commentId) {
        Comment comment = commentRepository.findById(commentId)
                .filter(c -> !c.isDeleted() && lessonId.equals(c.getLessonId()))
                .orElseThrow(() -> new EntityNotFoundException(MSG_COMMENT_NOT_FOUND));
        return comment;
    }

    /**
     * Counts a comment's depth by walking parent links up to the root (root=1).
     * Bounded by {@link #DEPTH_WALK_LIMIT} to stay safe against malformed cycles.
     */
    private int depthOf(Comment comment) {
        int depth = 1;
        Long parentId = comment.getParentId();
        int guard = 0;
        while (parentId != null && guard++ < DEPTH_WALK_LIMIT) {
            depth++;
            Comment parent = commentRepository.findById(parentId).orElse(null);
            if (parent == null) break;
            parentId = parent.getParentId();
        }
        return depth;
    }

    /** Trims then enforces 1..2000 chars; throws 400-mapped IllegalArgumentException. */
    private static String validateContent(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(MSG_COMMENT_BLANK);
        }
        if (trimmed.length() > MAX_CONTENT) {
            throw new IllegalArgumentException(MSG_COMMENT_TOO_LONG);
        }
        return trimmed;
    }
}
