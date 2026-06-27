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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Section CRUD service for the lessons tab (ULP-4.0a).
 *
 * <p>Covers list, create, rename, and soft-delete. Reorder + its
 * ordering-shape validation live on {@link SectionsReorderService}; this
 * class delegates {@link #reorder} to keep the public API stable.
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
 * showing a partial state.
 */
@Service
public class SectionsService {

    private final SectionRepository sectionRepository;
    private final ClassesService classesService;
    private final SectionActivityWriter activityWriter;
    private final SectionsReorderService reorderService;

    public SectionsService(SectionRepository sectionRepository,
                           ClassesService classesService,
                           SectionActivityWriter activityWriter,
                           SectionsReorderService reorderService) {
        this.sectionRepository = sectionRepository;
        this.classesService = classesService;
        this.activityWriter = activityWriter;
        this.reorderService = reorderService;
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

    /** Delegates to {@link SectionsReorderService#reorder} — kept here so the
     *  public service API stays stable for existing callers and tests. */
    @Transactional
    public void reorder(Long classId, List<Long> orderedIds,
                        Long userId, Role role) {
        reorderService.reorder(classId, orderedIds, userId, role);
    }

    // ── Internal helpers ───────────────────────────────────────────────

    private static SectionRow toRow(Section s) {
        return new SectionRow(s.getId(), s.getTitle(),
                s.getDisplayOrder() == null ? 0 : s.getDisplayOrder());
    }
}
