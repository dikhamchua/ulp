package com.ulp.features.comments.service;

import com.ulp.entities.Comment;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.utils.AvatarStyles;
import com.ulp.features.comments.dto.LessonCommentsDtos.CommentRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a flat, oldest-first APPROVED comment list into the threaded
 * {@link CommentRow} tree (ULP-4.6). Kept separate from
 * {@link LessonCommentsService} so each file stays focused (design D2).
 *
 * <p>Threading rules: up to three levels deep (writes clamp deeper replies).
 * A deleted node that still anchors a live descendant becomes a content-less
 * placeholder; a deleted node with no live descendant is omitted entirely.
 * Authors (name + avatar) are resolved with a single batch query to avoid N+1.
 */
@Component
public class CommentThreadAssembler {

    private static final int MAX_RENDER_DEPTH = 3;

    private final UserRepository userRepository;

    public CommentThreadAssembler(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Assembles the threaded tree for the caller. Roots (the null bucket) keep
     * the caller-supplied order of {@code all} — the paged service feeds them
     * newest-first — while replies within each thread are re-sorted oldest-first.
     *
     * <p>When {@code moderator} is true (ULP-11.7), hidden (REJECTED) nodes are
     * kept as real rows flagged {@code hidden=true}, and every visible node is
     * flagged {@code canModerate=true}. When false, output is identical to the
     * pre-moderation behaviour and no hidden node is expected in {@code all}.
     */
    public List<CommentRow> assemble(List<Comment> all, Long lecturerId, Long callerId,
                                     boolean moderator) {
        Map<Long, User> authors = resolveAuthors(all);

        // Bucket every comment (incl. deleted) by parent id; roots key on null.
        Map<Long, List<Comment>> childrenByParent = new HashMap<>();
        for (Comment c : all) {
            childrenByParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
        }
        // Sort reply buckets oldest-first; leave roots (null key) in input order.
        childrenByParent.forEach((parentId, list) -> {
            if (parentId != null) {
                list.sort(Comparator.comparing(Comment::getCreatedAt));
            }
        });

        return buildLevel(childrenByParent.get(null), childrenByParent,
                authors, lecturerId, callerId, moderator, 1);
    }

    /** Builds a single row (no replies) — used for create/edit responses. */
    public CommentRow singleRow(Comment c, Long lecturerId, Long callerId) {
        User author = userRepository.findById(c.getUserId()).orElse(null);
        return row(c, author, lecturerId, callerId, false, new ArrayList<>());
    }

    /**
     * Recursively builds the rows at one level, pruning deleted nodes that have
     * no live descendant and capping recursion at {@link #MAX_RENDER_DEPTH}. A
     * hidden (REJECTED, non-deleted) node is NOT pruned — it renders as a real
     * flagged row so a moderator can unhide it.
     */
    private List<CommentRow> buildLevel(List<Comment> level,
                                        Map<Long, List<Comment>> childrenByParent,
                                        Map<Long, User> authors,
                                        Long lecturerId, Long callerId,
                                        boolean moderator, int depth) {
        List<CommentRow> rows = new ArrayList<>();
        if (level == null) return rows;
        for (Comment c : level) {
            // Defensive cap: never recurse past level 3 even on legacy data.
            List<CommentRow> replies = depth < MAX_RENDER_DEPTH
                    ? buildLevel(childrenByParent.get(c.getId()), childrenByParent,
                            authors, lecturerId, callerId, moderator, depth + 1)
                    : new ArrayList<>();
            if (c.isDeleted()) {
                // Keep a deleted node only when it still anchors live replies.
                if (replies.isEmpty()) continue;
                rows.add(placeholderRow(c, replies));
            } else {
                rows.add(row(c, authors.get(c.getUserId()), lecturerId, callerId, moderator, replies));
            }
        }
        return rows;
    }

    private Map<Long, User> resolveAuthors(List<Comment> all) {
        Set<Long> ids = all.stream().map(Comment::getUserId).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<Long, User> map = new HashMap<>();
        userRepository.findAllById(ids).forEach(u -> map.put(u.getId(), u));
        return map;
    }

    private CommentRow row(Comment c, User author, Long lecturerId,
                           Long callerId, boolean moderator, List<CommentRow> replies) {
        boolean owner = c.getUserId().equals(callerId);
        boolean callerIsLecturer = lecturerId.equals(callerId);
        String name = author != null ? author.getFullName() : null;
        String avatarUrl = author != null ? author.getAvatarUrl() : null;
        // Stable gradient keyed on userId so a user keeps one colour everywhere.
        String gradient = AvatarStyles.gradient(c.getUserId().intValue());
        // hidden is only ever surfaced to a moderator; students never receive it.
        boolean hidden = moderator && Comment.MODERATION_REJECTED.equals(c.getModerationStatus());
        return new CommentRow(
                c.getId(), c.getParentId(), name,
                lecturerId.equals(c.getUserId()), c.getContent(), c.isEdited(),
                false, c.getCreatedAt(), owner, owner || callerIsLecturer,
                avatarUrl, AvatarStyles.label(name), gradient,
                hidden, moderator, replies);
    }

    /** A soft-deleted node shown only to anchor its live replies (no content). */
    private CommentRow placeholderRow(Comment c, List<CommentRow> replies) {
        return new CommentRow(
                c.getId(), c.getParentId(), null, false, null, false,
                true, c.getCreatedAt(), false, false,
                null, null, null, false, false, replies);
    }
}
