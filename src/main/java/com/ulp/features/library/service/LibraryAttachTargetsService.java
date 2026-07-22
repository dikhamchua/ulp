package com.ulp.features.library.service;

import com.ulp.entities.Lesson;
import com.ulp.entities.LessonAttachment;
import com.ulp.features.classes.dto.ClassesDtos.ClassRow;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.dto.LessonDtos.LessonRow;
import com.ulp.features.lessons.dto.SectionDtos.SectionRow;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.service.LessonsService;
import com.ulp.features.lessons.service.SectionsService;
import com.ulp.features.library.dto.LibraryDtos.AttachLessonContentSummary;
import com.ulp.features.library.dto.LibraryDtos.AttachTargetClassRow;
import com.ulp.features.library.dto.LibraryDtos.AttachTargetClassesPage;
import com.ulp.features.library.dto.LibraryDtos.AttachTargetLessonRow;
import com.ulp.features.library.dto.LibraryDtos.AttachTargetSectionRow;
import com.ulp.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.ulp.common.IConstant.DEFAULT_LIBRARY_TARGET_PAGE_SIZE;
import static com.ulp.common.IConstant.DEFAULT_SECTION_TITLE;
import static com.ulp.common.IConstant.MAX_LIBRARY_TARGET_PAGE_SIZE;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_UPLOAD;

/**
 * Read-only target lists for the library "attach to class" wizard.
 *
 * <p>Wraps existing class/section/lesson list primitives and enforces the same
 * editable-class auth chain as lesson edit.
 */
@Service
public class LibraryAttachTargetsService {

    /** Upper bound of class rows scanned when applying in-memory name search. */
    private static final int MAX_SEARCH_SCAN = 500;

    private final ClassesService classesService;
    private final SectionsService sectionsService;
    private final LessonsService lessonsService;
    private final LessonAttachmentRepository attachmentRepository;

    public LibraryAttachTargetsService(ClassesService classesService,
                                       SectionsService sectionsService,
                                       LessonsService lessonsService,
                                       LessonAttachmentRepository attachmentRepository) {
        this.classesService = classesService;
        this.sectionsService = sectionsService;
        this.lessonsService = lessonsService;
        this.attachmentRepository = attachmentRepository;
    }

    /**
     * Lists classes the user may edit, with optional name search and pagination.
     *
     * <p>Search filters in-memory after loading a capped window because
     * {@link ClassesService#listForUser} has no name predicate today.
     */
    @Transactional(readOnly = true)
    public AttachTargetClassesPage listClasses(Long userId, Role role, String q, int page, int size) {
        int p = Math.max(page, 0);
        int s = normalizeSize(size);
        String qNorm = normalizeQ(q);

        if (qNorm == null) {
            Page<ClassRow> result = classesService.listForUser(userId, role, PageRequest.of(p, s));
            List<AttachTargetClassRow> items = mapClassRows(result.getContent());
            return new AttachTargetClassesPage(
                    items, result.getNumber(), result.getSize(),
                    result.getTotalPages(), result.getTotalElements());
        }

        // Walk pages of listForUser and filter in memory — no name predicate on repo yet.
        // Hard stop after MAX_SEARCH_SCAN rows so HEAD/ADMIN cannot force unbounded load.
        String needle = qNorm.toLowerCase(Locale.ROOT);
        List<ClassRow> matched = new ArrayList<>();
        int batch = MAX_LIBRARY_TARGET_PAGE_SIZE;
        int scanned = 0;
        int pageIdx = 0;
        while (scanned < MAX_SEARCH_SCAN) {
            Page<ClassRow> batchPage = classesService.listForUser(
                    userId, role, PageRequest.of(pageIdx, batch));
            if (batchPage.isEmpty()) {
                break;
            }
            for (ClassRow row : batchPage.getContent()) {
                scanned++;
                String name = row.name() == null ? "" : row.name().toLowerCase(Locale.ROOT);
                String code = row.code() == null ? "" : row.code().toLowerCase(Locale.ROOT);
                if (name.contains(needle) || code.contains(needle)) {
                    matched.add(row);
                }
                if (scanned >= MAX_SEARCH_SCAN) {
                    break;
                }
            }
            if (!batchPage.hasNext()) {
                break;
            }
            pageIdx++;
        }
        int total = matched.size();
        int from = Math.min(p * s, total);
        int to = Math.min(from + s, total);
        List<AttachTargetClassRow> items = mapClassRows(matched.subList(from, to));
        int totalPages = s == 0 ? 0 : (int) Math.ceil(total / (double) s);
        return new AttachTargetClassesPage(items, p, s, totalPages, total);
    }

    /**
     * Sections of an editable class. When the class has none, creates a default
     * {@code Chương 1} so clone/attach wizards are never stuck on an empty list.
     */
    @Transactional
    public List<AttachTargetSectionRow> listSections(Long classId, Long userId, Role role) {
        // Editable gate — tighter than SectionsService.listForClass (viewable).
        classesService.getEditable(classId, userId, role);
        List<SectionRow> sections = sectionsService.listForClass(classId, userId, role);
        // Auto-seed first section so clone/attach never dead-ends on empty class.
        if (sections.isEmpty()) {
            SectionRow created = sectionsService.create(
                    classId, DEFAULT_SECTION_TITLE, userId, role);
            sections = List.of(created);
        }
        List<AttachTargetSectionRow> rows = new ArrayList<>(sections.size());
        for (SectionRow s : sections) {
            rows.add(new AttachTargetSectionRow(s.id(), s.title(), s.displayOrder()));
        }
        return rows;
    }

    /** Lessons in a section of an editable class. */
    @Transactional(readOnly = true)
    public List<AttachTargetLessonRow> listLessons(Long classId, Long sectionId,
                                                    Long userId, Role role) {
        List<LessonRow> lessons = lessonsService.listForSection(classId, sectionId, userId, role);
        List<AttachTargetLessonRow> rows = new ArrayList<>(lessons.size());
        for (LessonRow l : lessons) {
            rows.add(new AttachTargetLessonRow(
                    l.id(), l.title(), l.status(), l.contentType(), l.displayOrder()));
        }
        return rows;
    }

    /** Best-effort body flags used only for replace-confirm UX. */
    @Transactional(readOnly = true)
    public AttachLessonContentSummary contentSummary(Long classId, Long sectionId, Long lessonId,
                                                     Long userId, Role role) {
        Lesson lesson = lessonsService.getEditableLesson(
                classId, sectionId, lessonId, userId, role);
        boolean hasMainPdf = lesson.getPdfAttachmentId() != null;
        String pdfFilename = null;
        if (hasMainPdf) {
            LessonAttachment att = attachmentRepository.findById(lesson.getPdfAttachmentId())
                    .orElse(null);
            if (att != null) {
                pdfFilename = att.getOriginalFilename();
            }
        }
        boolean hasUploadedVideo = VIDEO_PROVIDER_UPLOAD.equals(lesson.getVideoProvider())
                && (lesson.hasLibraryVideo()
                || (lesson.getVideoUrl() != null && !lesson.getVideoUrl().isBlank()));
        return new AttachLessonContentSummary(
                lesson.getId(),
                hasMainPdf,
                hasUploadedVideo,
                pdfFilename,
                lesson.getVideoProvider());
    }

    private static List<AttachTargetClassRow> mapClassRows(List<ClassRow> rows) {
        List<AttachTargetClassRow> items = new ArrayList<>(rows.size());
        for (ClassRow r : rows) {
            items.add(new AttachTargetClassRow(r.id(), r.name(), r.code()));
        }
        return items;
    }

    private static int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_LIBRARY_TARGET_PAGE_SIZE;
        }
        return Math.min(size, MAX_LIBRARY_TARGET_PAGE_SIZE);
    }

    private static String normalizeQ(String q) {
        if (q == null) {
            return null;
        }
        String t = q.trim();
        return t.isEmpty() ? null : t;
    }
}
