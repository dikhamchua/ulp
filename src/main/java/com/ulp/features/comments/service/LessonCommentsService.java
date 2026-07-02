package com.ulp.features.comments.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Comment;
import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.comments.dto.LessonCommentsDtos.CommentRow;
import com.ulp.features.comments.repository.LessonCommentRepository;
import com.ulp.features.lessons.support.LessonAccessResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    /** Returns the lesson's threaded comment list for the caller. */
    @Transactional(readOnly = true)
    public List<CommentRow> list(Long lessonId, Long userId) {
        ClassEntity clazz = authorize(lessonId, userId);
        List<Comment> all = commentRepository
                .findByLessonIdAndModerationStatusOrderByCreatedAtAsc(
                        lessonId, Comment.MODERATION_APPROVED);
        return assembler.assemble(all, clazz.getLecturerId(), userId);
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
            // Reply-to-reply flattens to the reply's root (1-level threading).
            effectiveParent = parent.isRoot() ? parent.getId() : parent.getParentId();
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
