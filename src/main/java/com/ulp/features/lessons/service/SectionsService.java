package com.ulp.features.lessons.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Section;
import com.ulp.entities.SectionActivity;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.dto.SectionDtos.SectionRow;
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
 * Section CRUD service for the lessons tab (ULP-4.0a).
 *
 * <p>Every mutating method enforces ownership via
 * {@link ClassesService#getEditable}: a LECTURER may only manage sections
 * inside classes they own; HEAD and ADMIN may manage any class. Read
 * operations go through {@link ClassesService#getViewable}, which today
 * applies the same rule but is decoupled so a future sprint can relax it
 * for enrolled students.
 *
 * <p>Each mutation writes an audit row through
 * {@link SectionActivityWriter} in the same {@code @Transactional} block,
 * so the lessons-tab edit page can render a history tab without ever
 * showing a partial state. Audit writes carry rename diffs and reorder
 * metadata so the history view stays informative.
 *
 * <p>Reordering uses a two-phase write to dodge the
 * {@code uk_section_class_order} unique constraint: phase 1 shifts every
 * section into a high temporary range, phase 2 writes the final positions.
 * Without the temp shift, swapping the first two sections would hit the
 * constraint mid-update.
 */
@Service
public class SectionsService {

    /**
     * Temp offset used during reorder phase 1; must exceed any real
     * {@code display_order} (zero-based, dense per class).
     *
     * <p>The reorder algorithm shifts every section into the range
     * {@code [TEMP_ORDER_OFFSET, TEMP_ORDER_OFFSET + sectionCount)} during
     * phase 1, then writes the final {@code [0, sectionCount)} positions
     * in phase 2. Phase 1's upper bound must fit in {@code SMALLINT}
     * (max 32767), so the implementation is safe as long as
     * {@code sectionCount + TEMP_ORDER_OFFSET <= 32767} — i.e. a class
     * can hold up to {@code 32767 - 1000 = 31767} live sections before
     * the second-phase shift overflows. That is well beyond any plausible
     * real-world chapter count, so no guard is enforced; raise this
     * constant if the schema ever widens to {@code INT}.
     */
    private static final short TEMP_ORDER_OFFSET = 1000;

    private final SectionRepository sectionRepository;
    private final ClassesService classesService;
    private final SectionActivityWriter activityWriter;

    public SectionsService(SectionRepository sectionRepository,
                           ClassesService classesService,
                           SectionActivityWriter activityWriter) {
        this.sectionRepository = sectionRepository;
        this.classesService = classesService;
        this.activityWriter = activityWriter;
    }

    /**
     * Lists the sections of a class in their authored order. Authorization
     * is delegated to {@link ClassesService#getViewable}.
     */
    @Transactional(readOnly = true)
    public List<SectionRow> listForClass(Long classId, Long userId, Role role) {
        classesService.getViewable(classId, userId, role);
        List<Section> sections = sectionRepository.findByClassIdOrderByDisplayOrderAsc(classId);
        List<SectionRow> rows = new ArrayList<>(sections.size());
        for (Section s : sections) {
            rows.add(toRow(s));
        }
        return rows;
    }

    /**
     * Creates a new section appended after the current last one. The
     * {@code display_order} is derived from
     * {@link SectionRepository#findMaxDisplayOrder(Long)} + 1.
     */
    @Transactional
    public SectionRow create(Long classId, String title, Long userId, Role role) {
        ClassEntity clazz = classesService.getEditable(classId, userId, role);
        short nextOrder = (short) (sectionRepository.findMaxDisplayOrder(clazz.getId()) + 1);
        Section section = new Section(clazz.getId(), title, nextOrder, userId);
        Section saved = sectionRepository.save(section);
        activityWriter.write(
                saved.getId(),
                SectionActivity.TYPE_CREATED,
                "Tạo chương " + saved.getTitle(),
                userId);
        return toRow(saved);
    }

    /** Renames an existing section after verifying ownership. */
    @Transactional
    public SectionRow rename(Long classId, Long sectionId, String title,
                             Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        Section section = sectionRepository.findByIdAndClassId(sectionId, classId)
                .orElseThrow(() -> new EntityNotFoundException("Chương không tồn tại"));
        String oldTitle = section.getTitle();
        section.rename(title);
        Section saved = sectionRepository.save(section);

        // Only write an audit row when the title actually changed — silent
        // re-saves of the same title would otherwise pollute the history.
        if (!oldTitle.equals(saved.getTitle())) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("old", oldTitle);
            metadata.put("new", saved.getTitle());
            activityWriter.write(
                    saved.getId(),
                    SectionActivity.TYPE_RENAMED,
                    "Đổi tên: " + oldTitle + " → " + saved.getTitle(),
                    metadata,
                    userId);
        }
        return toRow(saved);
    }

    /**
     * Soft-deletes a section. The {@code display_order} of the remaining
     * siblings is NOT compacted — the lecturer can drag-reorder afterwards
     * if they care about the gap.
     */
    @Transactional
    public void delete(Long classId, Long sectionId, Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        Section section = sectionRepository.findByIdAndClassId(sectionId, classId)
                .orElseThrow(() -> new EntityNotFoundException("Chương không tồn tại"));
        section.markDeleted();
        sectionRepository.save(section);
        activityWriter.write(
                section.getId(),
                SectionActivity.TYPE_DELETED,
                "Xoá chương " + section.getTitle(),
                userId);
    }

    /**
     * Persists a new ordering for the given class's sections.
     *
     * <p>The supplied {@code orderedIds} list must contain EXACTLY the set
     * of live section ids for the class — any mismatch (extra, missing, or
     * unknown id) raises {@link IllegalArgumentException}, which the
     * controller surfaces as HTTP 400. This guards against stale UI state
     * after a concurrent delete.
     *
     * <p>The write happens in two flushes to dodge the
     * {@code uk_section_class_order} unique constraint when the new
     * ordering is a permutation of the old one.
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

        // Capture the original order so we can detect whether a reorder
        // actually happened — saves an audit row when the user just clicks
        // around without changing anything.
        List<Long> previousOrder = new ArrayList<>(current.size());
        for (Section s : current) previousOrder.add(s.getId());

        // Phase 1: shift every section into the temp range so the final
        // assignment cannot collide with an existing display_order.
        for (int i = 0; i < current.size(); i++) {
            current.get(i).changeOrder((short) (TEMP_ORDER_OFFSET + i));
        }
        sectionRepository.saveAllAndFlush(current);

        // Phase 2: assign final positions matching the requested order.
        // Build a lookup by id so we can write positions in O(n).
        for (int i = 0; i < orderedIds.size(); i++) {
            Long id = orderedIds.get(i);
            Section section = findById(current, id);
            section.changeOrder((short) i);
        }
        sectionRepository.saveAllAndFlush(current);

        // Write one audit row per section whose position actually changed.
        // We attribute each row to its own section so the per-section
        // history tab shows "Đã sắp xếp lại" when the section moved.
        if (!previousOrder.equals(orderedIds)) {
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
    }

    // ── Internal helpers ───────────────────────────────────────────────

    private static SectionRow toRow(Section s) {
        return new SectionRow(s.getId(), s.getTitle(),
                s.getDisplayOrder() == null ? 0 : s.getDisplayOrder());
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
