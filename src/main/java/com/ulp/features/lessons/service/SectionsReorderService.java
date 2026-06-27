package com.ulp.features.lessons.service;

import com.ulp.entities.Section;
import com.ulp.entities.SectionActivity;
import com.ulp.features.classes.service.ClassesService;
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

/**
 * Reorder + validation helpers for sections within a class.
 *
 * <p>Extracted from {@link SectionsService} during the file-size refactor.
 * Uses a two-phase write to dodge the {@code uk_section_class_order}
 * unique constraint: phase 1 shifts every section into a high temporary
 * range, phase 2 writes the final positions. Without the temp shift,
 * swapping the first two sections would hit the constraint mid-update.
 */
@Service
public class SectionsReorderService {

    /**
     * Temp offset used during reorder phase 1; must exceed any real
     * {@code display_order} (zero-based, dense per class).
     *
     * <p>Phase 1's upper bound must fit in {@code SMALLINT} (max 32767),
     * so the implementation is safe as long as
     * {@code sectionCount + TEMP_ORDER_OFFSET <= 32767} — i.e. up to
     * 31767 live sections per class. Well beyond any plausible chapter
     * count; raise this constant if the schema ever widens to {@code INT}.
     */
    private static final short TEMP_ORDER_OFFSET = 1000;

    private final SectionRepository sectionRepository;
    private final ClassesService classesService;
    private final SectionActivityWriter activityWriter;

    public SectionsReorderService(SectionRepository sectionRepository,
                                  ClassesService classesService,
                                  SectionActivityWriter activityWriter) {
        this.sectionRepository = sectionRepository;
        this.classesService = classesService;
        this.activityWriter = activityWriter;
    }

    /**
     * Persists a new ordering for the given class's sections.
     *
     * <p>The supplied {@code orderedIds} list must contain EXACTLY the set
     * of live section ids for the class — any mismatch raises
     * {@link IllegalArgumentException}, surfaced as HTTP 400 by the
     * controller. Guards against stale UI state after a concurrent delete.
     */
    @Transactional
    public void reorder(Long classId, List<Long> orderedIds,
                        Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        if (orderedIds == null) {
            throw new IllegalArgumentException("Danh sách thứ tự không được rỗng");
        }
        List<Section> current = sectionRepository.findByClassIdOrderByDisplayOrderAsc(classId);
        verifyOrderingMatches(current, orderedIds);

        // Capture original order to skip audit rows for sections that stayed.
        List<Long> previousOrder = new ArrayList<>(current.size());
        for (Section s : current) previousOrder.add(s.getId());

        // Phase 1: shift into temp range to avoid uk_section_class_order.
        for (int i = 0; i < current.size(); i++) {
            current.get(i).changeOrder((short) (TEMP_ORDER_OFFSET + i));
        }
        sectionRepository.saveAllAndFlush(current);

        // Phase 2: assign final positions matching the requested order.
        for (int i = 0; i < orderedIds.size(); i++) {
            Long id = orderedIds.get(i);
            Section section = findById(current, id);
            section.changeOrder((short) i);
        }
        sectionRepository.saveAllAndFlush(current);

        writeReorderActivity(previousOrder, orderedIds, userId);
    }

    /**
     * Writes one audit row per section whose position actually changed.
     * Each row is attributed to its own section so the per-section
     * history tab shows "Đã sắp xếp lại" when the section moved.
     */
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
                    SectionActivity.TYPE_REORDERED,
                    "Sắp xếp lại: vị trí " + (previousIndex + 1)
                            + " → " + (i + 1),
                    metadata,
                    userId);
        }
    }

    private static void verifyOrderingMatches(List<Section> current, List<Long> orderedIds) {
        if (current.size() != orderedIds.size()) {
            throw new IllegalArgumentException(
                    "Số lượng chương không khớp với danh sách gửi lên");
        }
        Set<Long> currentIds = new HashSet<>(current.size());
        for (Section s : current) currentIds.add(s.getId());
        Set<Long> requestedIds = new HashSet<>(orderedIds);
        if (requestedIds.size() != orderedIds.size()) {
            throw new IllegalArgumentException("Danh sách thứ tự chứa id trùng lặp");
        }
        if (!currentIds.equals(requestedIds)) {
            throw new IllegalArgumentException(
                    "Danh sách thứ tự không khớp với các chương hiện có");
        }
    }

    private static Section findById(List<Section> sections, Long id) {
        for (Section s : sections) {
            if (s.getId().equals(id)) return s;
        }
        // Cannot happen — verifyOrderingMatches already guards the id set.
        throw new EntityNotFoundException("Chương không tồn tại: " + id);
    }
}
