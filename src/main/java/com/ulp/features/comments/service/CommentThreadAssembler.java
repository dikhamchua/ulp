package com.ulp.features.comments.service;

import com.ulp.entities.Comment;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.comments.dto.LessonCommentsDtos.CommentRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a flat, oldest-first APPROVED comment list into the threaded
 * {@link CommentRow} view model (ULP-4.6). Kept separate from
 * {@link LessonCommentsService} so each file stays focused (design D2).
 *
 * <p>Threading rules: one level deep; deleted roots with a live reply become
 * a content-less placeholder; deleted roots without live replies and deleted
 * replies are omitted. Author names are resolved with a single batch query to
 * avoid N+1.
 */
@Component
public class CommentThreadAssembler {

    private final UserRepository userRepository;

    public CommentThreadAssembler(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Assembles the full threaded list for the caller. */
    public List<CommentRow> assemble(List<Comment> all, Long lecturerId, Long callerId) {
        Map<Long, String> authorNames = resolveAuthorNames(all);

        // Preserve creation order for roots; bucket live replies by root id.
        Map<Long, Comment> roots = new LinkedHashMap<>();
        Map<Long, List<Comment>> repliesByRoot = new HashMap<>();
        for (Comment c : all) {
            if (c.isRoot()) {
                roots.put(c.getId(), c);
            } else if (!c.isDeleted()) {
                repliesByRoot.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
            }
        }

        List<CommentRow> result = new ArrayList<>();
        for (Comment root : roots.values()) {
            List<Comment> liveReplies = repliesByRoot.getOrDefault(root.getId(), List.of());
            if (root.isDeleted() && liveReplies.isEmpty()) {
                continue; // deleted root with no live replies → omit entirely
            }
            List<CommentRow> replyRows = new ArrayList<>(liveReplies.size());
            for (Comment reply : liveReplies) {
                replyRows.add(row(reply, authorNames.get(reply.getUserId()),
                        lecturerId, callerId, List.of()));
            }
            result.add(root.isDeleted()
                    ? placeholderRow(root, replyRows)
                    : row(root, authorNames.get(root.getUserId()), lecturerId, callerId, replyRows));
        }
        return result;
    }

    /** Builds a single row (no replies) — used for create/edit responses. */
    public CommentRow singleRow(Comment c, Long lecturerId, Long callerId) {
        String authorName = userRepository.findById(c.getUserId())
                .map(User::getFullName).orElse(null);
        return row(c, authorName, lecturerId, callerId, List.of());
    }

    private Map<Long, String> resolveAuthorNames(List<Comment> all) {
        Set<Long> ids = all.stream().map(Comment::getUserId).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> names = new HashMap<>();
        userRepository.findAllById(ids).forEach(u -> names.put(u.getId(), u.getFullName()));
        return names;
    }

    private CommentRow row(Comment c, String authorName, Long lecturerId,
                           Long callerId, List<CommentRow> replies) {
        boolean owner = c.getUserId().equals(callerId);
        boolean callerIsLecturer = lecturerId.equals(callerId);
        return new CommentRow(
                c.getId(), c.getParentId(), authorName,
                lecturerId.equals(c.getUserId()), c.getContent(), c.isEdited(),
                false, c.getCreatedAt(), owner, owner || callerIsLecturer, replies);
    }

    /** A soft-deleted root shown only to anchor its live replies (no content). */
    private CommentRow placeholderRow(Comment root, List<CommentRow> replies) {
        return new CommentRow(
                root.getId(), null, null, false, null, false,
                true, root.getCreatedAt(), false, false, replies);
    }
}
