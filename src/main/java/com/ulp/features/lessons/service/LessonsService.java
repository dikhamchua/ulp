package com.ulp.features.lessons.service;

import com.ulp.common.HtmlSanitizer;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.Lesson;
import com.ulp.entities.LessonActivity;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.dto.LessonDtos.LessonRow;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.ulp.common.IConstant.LESSON_STATUS_PUBLISHED;
import static com.ulp.common.IConstant.MSG_LESSON_NOT_FOUND;

/**
 * Lesson CRUD service for the lessons tab (ULP-4.0b).
 *
 * <p>Covers list, create, update, and soft-delete. Publish/unpublish is
 * on {@link LessonsPublishService}; reorder + ordering validation on
 * {@link LessonsReorderService}; the diff-metadata builder + status
 * transition invoked from {@link #update} on {@link LessonsUpdateHelper}.
 * {@link #reorder} delegates to keep the public service API stable for
 * existing callers and tests.
 *
 * <p>Every mutating method enforces ownership via
 * {@link ClassesService#getEditable}: a LECTURER may only manage lessons
 * inside classes they own; HEAD and ADMIN may manage any class. The
 * section↔class binding is verified through
 * {@link LessonsReorderService#verifySectionBelongsToClass}, blocking the
 * cross-class enumeration attack flagged in design D5.
 *
 * <p>Rich-text bodies are sanitised through {@link HtmlSanitizer} before
 * persistence so a malicious paste cannot execute scripts when a student
 * views the lesson later (ULP-4.2 viewer).
 */
@Service
public class LessonsService {

    private final LessonRepository lessonRepository;
    private final ClassesService classesService;
    private final LessonActivityWriter activityWriter;
    private final LessonsReorderService reorderService;
    private final LessonsUpdateHelper updateHelper;

    public LessonsService(LessonRepository lessonRepository,
                          ClassesService classesService,
                          LessonActivityWriter activityWriter,
                          LessonsReorderService reorderService,
                          LessonsUpdateHelper updateHelper) {
        this.lessonRepository = lessonRepository;
        this.classesService = classesService;
        this.activityWriter = activityWriter;
        this.reorderService = reorderService;
        this.updateHelper = updateHelper;
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
        reorderService.verifySectionBelongsToClass(sectionId, classId);
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
        reorderService.verifySectionBelongsToClass(sectionId, classId);

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

        // Surface the publish event as a separate audit row so the timeline
        // shows "Đã xuất bản" alongside "Đã tạo" when publishing on create.
        if (LESSON_STATUS_PUBLISHED.equals(saved.getStatus())) {
            activityWriter.write(
                    saved.getId(),
                    LessonActivity.TYPE_PUBLISHED,
                    "Xuất bản bài giảng " + saved.getTitle(),
                    userId);
        }
        // Reference clazz id so the compiler does not warn about an unused
        // local — the lookup is for auth side-effect only.
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
        reorderService.verifySectionBelongsToClass(sectionId, classId);
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
            updateHelper.writeUpdateActivity(saved, oldTitle, oldBody, newBody,
                    titleChanged, bodyChanged, userId);
        }

        // Handle the status transition AFTER the UPDATED row so the history
        // reads "Đã cập nhật → Đã xuất bản" in chronological order.
        updateHelper.applyStatusTransition(saved, status, userId);
        return toRow(saved);
    }

    /** Soft-deletes a lesson; releases its display_order slot via {@link Lesson#markDeleted()}. */
    @Transactional
    public void delete(Long classId, Long sectionId, Long lessonId,
                       Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        reorderService.verifySectionBelongsToClass(sectionId, classId);
        Lesson lesson = loadLesson(sectionId, lessonId);
        lesson.markDeleted();
        lessonRepository.save(lesson);
        activityWriter.write(
                lesson.getId(),
                LessonActivity.TYPE_DELETED,
                "Xoá bài giảng " + lesson.getTitle(),
                userId);
    }

    /** Delegates to {@link LessonsReorderService#reorder} — kept here so the
     *  public service API stays stable for existing callers and tests. */
    @Transactional
    public void reorder(Long classId, Long sectionId, List<Long> orderedIds,
                        Long userId, Role role) {
        reorderService.reorder(classId, sectionId, orderedIds, userId, role);
    }

    // ── Internal helpers ───────────────────────────────────────────────

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
}
