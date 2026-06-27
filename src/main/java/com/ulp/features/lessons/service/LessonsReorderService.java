package com.ulp.features.lessons.service;

import com.ulp.entities.Lesson;
import com.ulp.entities.LessonActivity;
import com.ulp.entities.Section;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ulp.common.IConstant.MSG_SECTION_NOT_FOUND;

/**
 * Reorder + validation helpers for lessons within a section.
 *
 * <p>Extracted from {@link LessonsService} during the file-size refactor.
 * The public {@link #reorder} method is the same two-phase write the
 * service used to host inline; the section↔class binding check and
 * ordering-shape validation also live here so {@code LessonsService}
 * stays focused on lesson CRUD.
 *
 * <p>The two-phase write dodges the {@code uk_lesson_section_order}
 * unique constraint when the new ordering is a permutation of the old one:
 * phase 1 shifts every lesson into a high temp range, phase 2 writes the
 * final positions.
 */
@Service
public class LessonsReorderService {

    /**
     * Temp offset used during reorder phase 1; must exceed any real
     * {@code display_order} (zero-based, dense per section).
     *
     * <p>Phase 1's upper bound must fit in {@code SMALLINT} (max 32767),
     * so the implementation is safe as long as
     * {@code lessonCount + TEMP_ORDER_OFFSET <= 32767} — i.e. up to
     * 31767 live lessons per section. Far beyond any plausible chapter
     * size, so no guard is enforced.
     */
    private static final short TEMP_ORDER_OFFSET = 1000;

    private final LessonRepository lessonRepository;
    private final SectionRepository sectionRepository;
    private final ClassesService classesService;
    private final LessonActivityWriter activityWriter;

    public LessonsReorderService(LessonRepository lessonRepository,
                                 SectionRepository sectionRepository,
                                 ClassesService classesService,
                                 LessonActivityWriter activityWriter) {
        this.lessonRepository = lessonRepository;
        this.sectionRepository = sectionRepository;
        this.classesService = classesService;
        this.activityWriter = activityWriter;
    }

    /**
     * Persists a new ordering for the given section's lessons.
     *
     * <p>The supplied {@code orderedIds} list must contain EXACTLY the set
     * of live lesson ids for the section — any mismatch raises
     * {@link IllegalArgumentException}, which the controller surfaces as
     * HTTP 400.
     */
    @Transactional
    public void reorder(Long classId, Long sectionId, List<Long> orderedIds,
                        Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        verifySectionBelongsToClass(sectionId, classId);
        if (orderedIds == null) {
            throw new IllegalArgumentException("Danh sách thứ tự không được rỗng");
        }
        List<Lesson> current = lessonRepository
                .findBySectionIdOrderByDisplayOrderAsc(sectionId);
        verifyOrderingMatches(current, orderedIds);

        // Capture original order to skip audit rows for lessons that stayed.
        List<Long> previousOrder = new ArrayList<>(current.size());
        for (Lesson l : current) previousOrder.add(l.getId());

        // Phase 1: shift into temp range to avoid uk_lesson_section_order.
        for (int i = 0; i < current.size(); i++) {
            current.get(i).changeOrder((short) (TEMP_ORDER_OFFSET + i));
        }
        lessonRepository.saveAllAndFlush(current);

        // Phase 2: assign final positions matching the requested order.
        for (int i = 0; i < orderedIds.size(); i++) {
            Long id = orderedIds.get(i);
            Lesson lesson = findById(current, id);
            lesson.changeOrder((short) i);
        }
        lessonRepository.saveAllAndFlush(current);

        writeReorderActivity(previousOrder, orderedIds, userId);
    }

    /**
     * Verifies that the section exists and lives inside the requested class.
     * Throws {@link EntityNotFoundException} otherwise. Blocks path-variable
     * enumeration attempts (e.g. POSTing class A's URL with section B's id).
     */
    public void verifySectionBelongsToClass(Long sectionId, Long classId) {
        Section section = sectionRepository.findByIdAndClassId(sectionId, classId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_SECTION_NOT_FOUND));
        if (section.getId() == null) {
            throw new IllegalStateException("Section id missing after lookup");
        }
    }

    /** Writes one audit row per lesson whose position actually changed. */
    private void writeReorderActivity(List<Long> previousOrder,
                                      List<Long> orderedIds, Long userId) {
        if (previousOrder.equals(orderedIds)) return;
        for (int i = 0; i < orderedIds.size(); i++) {
            Long id = orderedIds.get(i);
            int previousIndex = previousOrder.indexOf(id);
            if (previousIndex == i) continue; // didn't move
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("from", previousIndex);
            metadata.put("to", i);
            activityWriter.write(
                    id,
                    LessonActivity.TYPE_REORDERED,
                    "Sắp xếp lại: vị trí " + (previousIndex + 1)
                            + " → " + (i + 1),
                    metadata,
                    userId);
        }
    }

    private static void verifyOrderingMatches(List<Lesson> current, List<Long> orderedIds) {
        if (current.size() != orderedIds.size()) {
            throw new IllegalArgumentException(
                    "Số lượng bài giảng không khớp với danh sách gửi lên");
        }
        Set<Long> currentIds = new HashSet<>(current.size());
        for (Lesson l : current) currentIds.add(l.getId());
        Set<Long> requestedIds = new HashSet<>(orderedIds);
        if (requestedIds.size() != orderedIds.size()) {
            throw new IllegalArgumentException("Danh sách thứ tự chứa id trùng lặp");
        }
        if (!currentIds.equals(requestedIds)) {
            throw new IllegalArgumentException(
                    "Danh sách thứ tự không khớp với các bài giảng hiện có");
        }
    }

    private static Lesson findById(List<Lesson> lessons, Long id) {
        for (Lesson l : lessons) {
            if (l.getId().equals(id)) return l;
        }
        // Cannot happen — verifyOrderingMatches already guards the id set.
        throw new EntityNotFoundException("Bài giảng không tồn tại: " + id);
    }
}
