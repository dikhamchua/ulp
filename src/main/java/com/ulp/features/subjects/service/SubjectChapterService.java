package com.ulp.features.subjects.service;

import com.ulp.features.subjects.dto.SubjectDtos.ChapterRow;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.entity.SubjectChapter;
import com.ulp.features.subjects.repository.SubjectChapterRepository;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static com.ulp.common.IConstant.MSG_CHAPTER_NOT_FOUND;
import static com.ulp.common.IConstant.MSG_CHAPTER_TITLE_REQUIRED;

/**
 * Sample chapter outline for a subject — list/create/rename/delete/reorder.
 *
 * <p>Auth reuses {@link SubjectService#requireScopedSubject}. Does not touch
 * class {@code sections}.
 */
@Service
public class SubjectChapterService {

    public enum MoveDirection {
        UP,
        DOWN
    }

    private final SubjectChapterRepository chapterRepository;
    private final SubjectService subjectService;

    public SubjectChapterService(SubjectChapterRepository chapterRepository,
                                 SubjectService subjectService) {
        this.chapterRepository = chapterRepository;
        this.subjectService = subjectService;
    }

    /** Lists live chapters for a scoped subject in display order. */
    @Transactional(readOnly = true)
    public List<ChapterRow> listChapters(Long subjectId, Long actorId, Role role) {
        subjectService.requireScopedSubject(subjectId, actorId, role);
        List<SubjectChapter> chapters =
                chapterRepository.findBySubjectIdOrderByDisplayOrderAsc(subjectId);
        List<ChapterRow> rows = new ArrayList<>(chapters.size());
        for (SubjectChapter c : chapters) {
            rows.add(toRow(c));
        }
        return rows;
    }

    /**
     * Appends a chapter after the current maximum display_order.
     *
     * @return the created row
     */
    @Transactional
    public ChapterRow createChapter(Long subjectId, String title, Long actorId, Role role) {
        Subject subject = subjectService.requireScopedSubject(subjectId, actorId, role);
        String normalized = requireTitle(title);
        short nextOrder = (short) (chapterRepository.findMaxDisplayOrder(subject.getId()) + 1);
        SubjectChapter chapter = new SubjectChapter(
                subject.getId(), normalized, nextOrder, actorId);
        return toRow(chapterRepository.save(chapter));
    }

    /** Renames a live chapter on the scoped subject. */
    @Transactional
    public ChapterRow renameChapter(Long subjectId, Long chapterId, String title,
                                    Long actorId, Role role) {
        subjectService.requireScopedSubject(subjectId, actorId, role);
        SubjectChapter chapter = requireChapter(subjectId, chapterId);
        chapter.rename(requireTitle(title));
        return toRow(chapterRepository.save(chapter));
    }

    /** Soft-deletes a chapter and releases its display_order slot. */
    @Transactional
    public void softDeleteChapter(Long subjectId, Long chapterId, Long actorId, Role role) {
        subjectService.requireScopedSubject(subjectId, actorId, role);
        SubjectChapter chapter = requireChapter(subjectId, chapterId);
        chapter.markDeleted();
        chapterRepository.save(chapter);
    }

    /**
     * Swaps display_order with the previous (UP) or next (DOWN) live sibling.
     * No-op when already at the edge.
     */
    @Transactional
    public void moveChapter(Long subjectId, Long chapterId, MoveDirection direction,
                            Long actorId, Role role) {
        subjectService.requireScopedSubject(subjectId, actorId, role);
        List<SubjectChapter> ordered =
                chapterRepository.findBySubjectIdOrderByDisplayOrderAsc(subjectId);
        int index = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(chapterId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new EntityNotFoundException(MSG_CHAPTER_NOT_FOUND);
        }
        int swapWith = direction == MoveDirection.UP ? index - 1 : index + 1;
        if (swapWith < 0 || swapWith >= ordered.size()) {
            return;
        }
        SubjectChapter a = ordered.get(index);
        SubjectChapter b = ordered.get(swapWith);
        short orderA = a.getDisplayOrder() == null ? (short) index : a.getDisplayOrder();
        short orderB = b.getDisplayOrder() == null ? (short) swapWith : b.getDisplayOrder();
        // Two-step swap avoids unique (subject_id, display_order) collision.
        a.changeOrder((short) -1);
        chapterRepository.saveAndFlush(a);
        b.changeOrder(orderA);
        chapterRepository.saveAndFlush(b);
        a.changeOrder(orderB);
        chapterRepository.save(a);
    }

    private SubjectChapter requireChapter(Long subjectId, Long chapterId) {
        return chapterRepository.findByIdAndSubjectId(chapterId, subjectId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_CHAPTER_NOT_FOUND));
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new SubjectValidationException(MSG_CHAPTER_TITLE_REQUIRED);
        }
        return title.trim();
    }

    private static ChapterRow toRow(SubjectChapter c) {
        short order = c.getDisplayOrder() == null ? 0 : c.getDisplayOrder();
        return new ChapterRow(c.getId(), c.getTitle(), order);
    }
}
