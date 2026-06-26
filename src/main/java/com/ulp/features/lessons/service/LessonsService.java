package com.ulp.features.lessons.service;

import com.ulp.common.HtmlSanitizer;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.Lesson;
import com.ulp.entities.LessonActivity;
import com.ulp.entities.Section;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.dto.LessonDtos.LessonRow;
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
import java.util.Objects;
import java.util.Set;

import static com.ulp.common.IConstant.LESSON_STATUS_DRAFT;
import static com.ulp.common.IConstant.LESSON_STATUS_PUBLISHED;
import static com.ulp.common.IConstant.MSG_LESSON_NOT_FOUND;
import static com.ulp.common.IConstant.MSG_SECTION_NOT_FOUND;

/**
 * Lesson CRUD service for the lessons tab (ULP-4.0b).
 *
 * <p>Covers create, update, reorder, and soft-delete. Publish and
 * unpublish state transitions live on {@link LessonsPublishService}
 * (extracted during the C.3 structural split); the status-change branch
 * triggered by the update form still runs inline through the private
 * {@link #applyStatusTransition} helper so a single update POST writes
 * both UPDATED and PUBLISHED/UNPUBLISHED audit rows in one transaction.
 *
 * <p>Every mutating method enforces ownership via
 * {@link ClassesService#getEditable}: a LECTURER may only manage lessons
 * inside classes they own; HEAD and ADMIN may manage any class. The
 * second auth layer — verifying that the targeted {@code sectionId}
 * actually belongs to {@code classId} — runs through
 * {@link SectionRepository#findByIdAndClassId}, blocking the cross-class
 * enumeration attack flagged in design D5.
 *
 * <p>Each mutation writes an audit row through
 * {@link LessonActivityWriter} in the same {@code @Transactional} block,
 * so the edit page can render a history tab without ever showing a
 * partial state. Audit writes carry diff metadata for content / title
 * updates and reorder positions.
 *
 * <p>Reordering uses the same two-phase write as
 * {@code SectionsService.reorder} to dodge the
 * {@code uk_lesson_section_order} unique constraint when the new ordering
 * is a permutation of the old one.
 *
 * <p>Rich-text bodies are sanitised through {@link HtmlSanitizer} before
 * persistence so a malicious paste cannot execute scripts when a student
 * views the lesson later (ULP-4.2 viewer).
 */
@Service
public class LessonsService {

    /**
     * Temp offset used during reorder phase 1; must exceed any real
     * {@code display_order} (zero-based, dense per section).
     *
     * <p>The reorder algorithm shifts every lesson into the range
     * {@code [TEMP_ORDER_OFFSET, TEMP_ORDER_OFFSET + lessonCount)} during
     * phase 1, then writes the final {@code [0, lessonCount)} positions
     * in phase 2. Phase 1's upper bound must fit in {@code SMALLINT}
     * (max 32767), so the implementation is safe as long as
     * {@code lessonCount + TEMP_ORDER_OFFSET <= 32767} — i.e. a section
     * can hold up to {@code 32767 - 1000 = 31767} live lessons before
     * the second-phase shift overflows. Far beyond any plausible chapter
     * size, so no guard is enforced.
     */
    private static final short TEMP_ORDER_OFFSET = 1000;

    private final LessonRepository lessonRepository;
    private final SectionRepository sectionRepository;
    private final ClassesService classesService;
    private final LessonActivityWriter activityWriter;

    public LessonsService(LessonRepository lessonRepository,
                          SectionRepository sectionRepository,
                          ClassesService classesService,
                          LessonActivityWriter activityWriter) {
        this.lessonRepository = lessonRepository;
        this.sectionRepository = sectionRepository;
        this.classesService = classesService;
        this.activityWriter = activityWriter;
    }

    /**
     * Lists the lessons of a section in their authored order. Authorization
     * is enforced via {@link ClassesService#getEditable} (read access is
     * limited to the lecturer's own classes for now — relaxing this for
     * enrolled students is ULP-4.1).
     */
    @Transactional(readOnly = true)
    public List<LessonRow> listForSection(Long classId, Long sectionId,
                                          Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        verifySectionBelongsToClass(sectionId, classId);
        List<Lesson> lessons = lessonRepository
                .findBySectionIdOrderByDisplayOrderAsc(sectionId);
        List<LessonRow> rows = new ArrayList<>(lessons.size());
        for (Lesson l : lessons) {
            rows.add(toRow(l));
        }
        return rows;
    }

    /**
     * Creates a new lesson appended after the current last one. Sanitises
     * the supplied HTML body before persistence; publishes immediately
     * when {@code status == PUBLISHED}.
     */
    @Transactional
    public LessonRow create(Long classId, Long sectionId, String title,
                            String status, String contentHtmlRaw,
                            Long userId, Role role) {
        ClassEntity clazz = classesService.getEditable(classId, userId, role);
        verifySectionBelongsToClass(sectionId, classId);

        short nextOrder = (short) (lessonRepository.findMaxDisplayOrder(sectionId) + 1);
        Lesson lesson = new Lesson(sectionId, title, nextOrder, userId);
        lesson.updateContent(HtmlSanitizer.sanitize(contentHtmlRaw));
        if (LESSON_STATUS_PUBLISHED.equals(status)) {
            lesson.publish();
        }
        Lesson saved = lessonRepository.save(lesson);

        activityWriter.write(
                saved.getId(),
                LessonActivity.TYPE_CREATED,
                "Tạo bài giảng " + saved.getTitle(),
                userId);

        // Surface the publish event as a separate audit row so the
        // timeline shows "Đã xuất bản" alongside "Đã tạo" when the
        // lecturer publishes on create.
        if (LESSON_STATUS_PUBLISHED.equals(saved.getStatus())) {
            activityWriter.write(
                    saved.getId(),
                    LessonActivity.TYPE_PUBLISHED,
                    "Xuất bản bài giảng " + saved.getTitle(),
                    userId);
        }
        // The clazz reference is loaded to enforce auth; suppress
        // unused-variable warnings by referencing its id explicitly.
        if (clazz.getId() == null) {
            throw new IllegalStateException("Class id missing after auth check");
        }
        return toRow(saved);
    }

    /**
     * Updates the title / status / body of an existing lesson.
     *
     * <p>The UPDATED activity row is written only when the title OR the
     * sanitised body actually changed — re-submitting the unchanged form
     * therefore does not pollute the history. Status transitions ride on
     * their own activity types (PUBLISHED / UNPUBLISHED) so the timeline
     * can highlight publish events distinctly.
     */
    @Transactional
    public LessonRow update(Long classId, Long sectionId, Long lessonId,
                            String title, String status, String contentHtmlRaw,
                            Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        verifySectionBelongsToClass(sectionId, classId);
        Lesson lesson = loadLesson(sectionId, lessonId);

        String oldTitle = lesson.getTitle();
        String oldBody = nullToEmpty(lesson.getContentRichtext());
        String newBody = HtmlSanitizer.sanitize(contentHtmlRaw);
        boolean titleChanged = !Objects.equals(oldTitle, title);
        boolean bodyChanged = !Objects.equals(oldBody, newBody);

        lesson.rename(title);
        lesson.updateContent(newBody);
        Lesson saved = lessonRepository.save(lesson);

        if (titleChanged || bodyChanged) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (titleChanged) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("old", oldTitle);
                diff.put("new", saved.getTitle());
                metadata.put("title", diff);
            }
            if (bodyChanged) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("old", oldBody);
                diff.put("new", newBody);
                metadata.put("body", diff);
            }
            activityWriter.write(
                    saved.getId(),
                    LessonActivity.TYPE_UPDATED,
                    "Cập nhật bài giảng " + saved.getTitle(),
                    metadata,
                    userId);
        }

        // Handle the status transition AFTER the UPDATED row so the
        // history reads "Đã cập nhật → Đã xuất bản" in chronological order.
        applyStatusTransition(saved, status, userId);
        return toRow(saved);
    }

    /** Soft-deletes a lesson; releases its display_order slot via {@link Lesson#markDeleted()}. */
    @Transactional
    public void delete(Long classId, Long sectionId, Long lessonId,
                       Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        verifySectionBelongsToClass(sectionId, classId);
        Lesson lesson = loadLesson(sectionId, lessonId);
        lesson.markDeleted();
        lessonRepository.save(lesson);
        activityWriter.write(
                lesson.getId(),
                LessonActivity.TYPE_DELETED,
                "Xoá bài giảng " + lesson.getTitle(),
                userId);
    }

    /**
     * Persists a new ordering for the given section's lessons.
     *
     * <p>The supplied {@code orderedIds} list must contain EXACTLY the set
     * of live lesson ids for the section — any mismatch (extra, missing,
     * or unknown id) raises {@link IllegalArgumentException}, which the
     * controller surfaces as HTTP 400.
     *
     * <p>The write happens in two flushes to dodge the
     * {@code uk_lesson_section_order} unique constraint when the new
     * ordering is a permutation of the old one.
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

        // Capture the original order so we can detect whether a reorder
        // actually happened and skip activity rows for lessons that
        // stayed in place.
        List<Long> previousOrder = new ArrayList<>(current.size());
        for (Lesson l : current) previousOrder.add(l.getId());

        // Phase 1: shift every lesson into the temp range so the final
        // assignment cannot collide with an existing display_order.
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

        // Write one audit row per lesson whose position actually changed.
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
                        LessonActivity.TYPE_REORDERED,
                        "Sắp xếp lại: vị trí " + (previousIndex + 1)
                                + " → " + (i + 1),
                        metadata,
                        userId);
            }
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────

    /**
     * Applies a status transition triggered by an update form. Skips the
     * audit write when the incoming status is null/blank or unchanged so
     * "Đã cập nhật" stands alone when only title/body moved.
     */
    private void applyStatusTransition(Lesson lesson, String requestedStatus, Long userId) {
        if (requestedStatus == null || requestedStatus.isBlank()) return;
        if (requestedStatus.equals(lesson.getStatus())) return;
        if (LESSON_STATUS_PUBLISHED.equals(requestedStatus)) {
            lesson.publish();
            lessonRepository.save(lesson);
            activityWriter.write(
                    lesson.getId(),
                    LessonActivity.TYPE_PUBLISHED,
                    "Xuất bản bài giảng " + lesson.getTitle(),
                    userId);
        } else if (LESSON_STATUS_DRAFT.equals(requestedStatus)) {
            lesson.unpublish();
            lessonRepository.save(lesson);
            activityWriter.write(
                    lesson.getId(),
                    LessonActivity.TYPE_UNPUBLISHED,
                    "Chuyển bài giảng " + lesson.getTitle() + " về nháp",
                    userId);
        }
    }

    /**
     * Verifies that the section exists and lives inside the requested class.
     * Throws {@link EntityNotFoundException} otherwise — surfaced as 404 by
     * the controller. The class-scoped lookup blocks path-variable
     * enumeration attempts (e.g. POSTing class A's URL with section B's id).
     */
    private void verifySectionBelongsToClass(Long sectionId, Long classId) {
        Section section = sectionRepository.findByIdAndClassId(sectionId, classId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_SECTION_NOT_FOUND));
        // Side-effect-only check; the result is discarded but referenced so
        // the compiler does not warn about an unused local.
        if (section.getId() == null) {
            throw new IllegalStateException("Section id missing after lookup");
        }
    }

    private Lesson loadLesson(Long sectionId, Long lessonId) {
        return lessonRepository.findByIdAndSectionId(lessonId, sectionId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_LESSON_NOT_FOUND));
    }

    private static LessonRow toRow(Lesson l) {
        return new LessonRow(l.getId(), l.getTitle(), l.getStatus(),
                l.getDisplayOrder() == null ? 0 : l.getDisplayOrder());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
