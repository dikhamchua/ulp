package com.ulp.features.library.service;

import com.ulp.common.HtmlSanitizer;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.Lesson;
import com.ulp.entities.LessonActivity;
import com.ulp.entities.LessonAttachment;
import com.ulp.entities.LessonTemplate;
import com.ulp.entities.LessonTemplateAttachment;
import com.ulp.entities.LibraryAsset;
import com.ulp.entities.Section;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.lessons.service.LessonActivityWriter;
import com.ulp.features.lessons.service.LessonsReorderService;
import com.ulp.features.lessons.service.LessonsService;
import com.ulp.features.classes.dto.ClassesDtos.ClassRow;
import com.ulp.features.library.dto.LibraryDtos.AttachTargetClassRow;
import com.ulp.features.library.dto.LibraryDtos.LessonCloneResult;
import com.ulp.features.library.dto.LibraryDtos.LibraryLessonRow;
import com.ulp.features.library.dto.LibraryDtos.LibraryLessonsPageView;
import com.ulp.features.library.dto.LibraryDtos.LessonTemplatePageView;
import com.ulp.features.library.dto.LibraryDtos.LessonTemplateRow;
import com.ulp.features.library.repository.LessonTemplateAttachmentRepository;
import com.ulp.features.library.repository.LessonTemplateRepository;
import com.ulp.features.library.repository.LibraryAssetRepository;
import com.ulp.features.upload.LessonAttachmentStorageService;
import com.ulp.features.upload.LessonVideoStorageService;
import com.ulp.features.upload.LibraryStorageService;
import com.ulp.features.upload.LibraryStorageService.StoredLibraryFile;
import com.ulp.features.upload.UploadFileHelper;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ulp.common.IConstant.BASE_LECTURER;
import static com.ulp.common.IConstant.CONTENT_TYPE_PDF;
import static com.ulp.common.IConstant.CONTENT_TYPE_RICHTEXT;
import static com.ulp.common.IConstant.CONTENT_TYPE_VIDEO;
import static com.ulp.common.IConstant.DEFAULT_LIBRARY_PAGE_SIZE;
import static com.ulp.common.IConstant.MAX_LIBRARY_PAGE_SIZE;
import static com.ulp.common.IConstant.PATH_CLASSES;
import static com.ulp.common.IConstant.MSG_TEMPLATE_BODY_INCOMPLETE;
import static com.ulp.common.IConstant.MSG_TEMPLATE_NOT_FOUND;
import static com.ulp.common.IConstant.MSG_TEMPLATE_PROMOTE_FAILED;
import static com.ulp.common.IConstant.MSG_TEMPLATE_TITLE_BLANK;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_UPLOAD;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_VIMEO;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_YOUTUBE;
import static com.ulp.entities.LibraryAsset.KIND_DOCUMENT;
import static com.ulp.entities.LibraryAsset.KIND_VIDEO;

/**
 * Owner-scoped lesson templates: save from a lesson, list/rename/delete, and
 * clone a template or live lesson into an editable class section as DRAFT.
 *
 * <p>One-off lesson files are promoted into {@code library_assets} (disk copy)
 * so templates never share lesson-scoped stored paths. Library-backed refs are
 * reused by id without copying bytes.
 */
@Service
public class LessonTemplateService {

    private final LessonTemplateRepository templateRepository;
    private final LessonTemplateAttachmentRepository templateAttachmentRepository;
    private final LibraryAssetRepository assetRepository;
    private final LibraryService libraryService;
    private final LibraryStorageService libraryStorage;
    private final LessonAttachmentStorageService attachmentStorage;
    private final LessonVideoStorageService videoStorage;
    private final LessonRepository lessonRepository;
    private final LessonAttachmentRepository attachmentRepository;
    private final SectionRepository sectionRepository;
    private final ClassRepository classRepository;
    private final LessonsService lessonsService;
    private final LessonsReorderService reorderService;
    private final ClassesService classesService;
    private final LessonActivityWriter activityWriter;

    public LessonTemplateService(LessonTemplateRepository templateRepository,
                                 LessonTemplateAttachmentRepository templateAttachmentRepository,
                                 LibraryAssetRepository assetRepository,
                                 LibraryService libraryService,
                                 LibraryStorageService libraryStorage,
                                 LessonAttachmentStorageService attachmentStorage,
                                 LessonVideoStorageService videoStorage,
                                 LessonRepository lessonRepository,
                                 LessonAttachmentRepository attachmentRepository,
                                 SectionRepository sectionRepository,
                                 ClassRepository classRepository,
                                 LessonsService lessonsService,
                                 LessonsReorderService reorderService,
                                 ClassesService classesService,
                                 LessonActivityWriter activityWriter) {
        this.templateRepository = templateRepository;
        this.templateAttachmentRepository = templateAttachmentRepository;
        this.assetRepository = assetRepository;
        this.libraryService = libraryService;
        this.libraryStorage = libraryStorage;
        this.attachmentStorage = attachmentStorage;
        this.videoStorage = videoStorage;
        this.lessonRepository = lessonRepository;
        this.attachmentRepository = attachmentRepository;
        this.sectionRepository = sectionRepository;
        this.classRepository = classRepository;
        this.lessonsService = lessonsService;
        this.reorderService = reorderService;
        this.classesService = classesService;
        this.activityWriter = activityWriter;
    }

    /**
     * Live lessons across classes owned by the lecturer — primary list on the
     * library "Bài giảng" tab (clone / open / save-as-template).
     *
     * @param classId optional filter; null = all owned classes
     */
    @Transactional(readOnly = true)
    public LibraryLessonsPageView listLessons(Long lecturerId, String q, Long classId,
                                              int page, int size) {
        PageRequest pr = pageRequest(page, size);
        String qNorm = normalizeQ(q);
        // Ignore unknown/foreign classId so the filter never leaks other lecturers' classes.
        Long classFilter = resolveOwnedClassFilter(lecturerId, classId);
        Page<Lesson> result = lessonRepository.searchByLecturerId(
                lecturerId, qNorm, classFilter, pr);
        // Batch-load section + class labels for the current page only.
        Set<Long> sectionIds = new HashSet<>();
        for (Lesson l : result.getContent()) {
            sectionIds.add(l.getSectionId());
        }
        Map<Long, Section> sections = new HashMap<>();
        Map<Long, ClassEntity> classes = new HashMap<>();
        if (!sectionIds.isEmpty()) {
            for (Section s : sectionRepository.findAllById(sectionIds)) {
                sections.put(s.getId(), s);
            }
            Set<Long> classIds = new HashSet<>();
            for (Section s : sections.values()) {
                classIds.add(s.getClassId());
            }
            if (!classIds.isEmpty()) {
                for (ClassEntity c : classRepository.findAllById(classIds)) {
                    classes.put(c.getId(), c);
                }
            }
        }
        Page<LibraryLessonRow> rows = result.map(l -> toLessonRow(l, sections, classes));
        // Sidebar badge stays global (all classes); page total respects the filter.
        long lessonCount = lessonRepository.countByLecturerId(lecturerId, null);
        long templateCount = templateRepository.countByOwnerId(lecturerId);
        long totalCount = assetRepository.countByOwnerId(lecturerId);
        long documentCount = assetRepository.countByOwnerIdAndKind(lecturerId, KIND_DOCUMENT);
        long videoCount = assetRepository.countByOwnerIdAndKind(lecturerId, KIND_VIDEO);
        List<AttachTargetClassRow> classOptions = listOwnedClassOptions(lecturerId);
        return new LibraryLessonsPageView(
                rows,
                qNorm == null ? "" : qNorm,
                classFilter,
                classOptions,
                lessonCount,
                templateCount,
                totalCount,
                documentCount,
                videoCount);
    }

    /** Dropdown options: every class owned by the lecturer (capped). */
    private List<AttachTargetClassRow> listOwnedClassOptions(Long lecturerId) {
        Page<ClassRow> owned = classesService.listForUser(
                lecturerId, Role.LECTURER, PageRequest.of(0, MAX_LIBRARY_PAGE_SIZE));
        List<AttachTargetClassRow> options = new ArrayList<>(owned.getNumberOfElements());
        for (ClassRow row : owned.getContent()) {
            options.add(new AttachTargetClassRow(row.id(), row.name(), row.code()));
        }
        return options;
    }

    /** Returns classId only when it belongs to the lecturer; otherwise null (all). */
    private Long resolveOwnedClassFilter(Long lecturerId, Long classId) {
        if (classId == null) {
            return null;
        }
        return classRepository.findById(classId)
                .filter(c -> lecturerId.equals(c.getLecturerId()))
                .map(ClassEntity::getId)
                .orElse(null);
    }

    /** Saved templates only (secondary list / management). */
    @Transactional(readOnly = true)
    public LessonTemplatePageView list(Long ownerId, String q, int page, int size) {
        PageRequest pr = pageRequest(page, size);
        String qNorm = normalizeQ(q);
        Page<LessonTemplate> result = templateRepository.searchOwned(ownerId, qNorm, pr);
        Page<LessonTemplateRow> rows = result.map(t -> toRow(t,
                templateAttachmentRepository.findByTemplateIdOrderByDisplayOrderAsc(t.getId()).size()));
        long templateCount = templateRepository.countByOwnerId(ownerId);
        long totalCount = assetRepository.countByOwnerId(ownerId);
        long documentCount = assetRepository.countByOwnerIdAndKind(ownerId, KIND_DOCUMENT);
        long videoCount = assetRepository.countByOwnerIdAndKind(ownerId, KIND_VIDEO);
        return new LessonTemplatePageView(
                rows,
                qNorm == null ? "" : qNorm,
                templateCount,
                totalCount,
                documentCount,
                videoCount);
    }

    private static LibraryLessonRow toLessonRow(Lesson l,
                                                 Map<Long, Section> sections,
                                                 Map<Long, ClassEntity> classes) {
        Section section = sections.get(l.getSectionId());
        ClassEntity clazz = section == null ? null : classes.get(section.getClassId());
        Long classId = clazz != null ? clazz.getId() : null;
        Long sectionId = section != null ? section.getId() : l.getSectionId();
        String className = clazz != null ? clazz.getName() : "—";
        String sectionTitle = section != null ? section.getTitle() : "—";
        String editUrl = null;
        String cloneUrl = null;
        if (classId != null && sectionId != null && l.getId() != null) {
            String base = BASE_LECTURER + PATH_CLASSES + "/" + classId
                    + "/sections/" + sectionId + "/lessons/" + l.getId();
            editUrl = base + "/edit";
            cloneUrl = base + "/clone";
        }
        return new LibraryLessonRow(
                l.getId(),
                classId,
                sectionId,
                l.getTitle(),
                l.getContentType(),
                l.getStatus(),
                className,
                sectionTitle,
                l.getUpdatedAt(),
                editUrl,
                cloneUrl);
    }

    /**
     * Snapshots an editable lesson into the owner's personal template library.
     * Promotes one-off PDF/video/attachments into library assets first.
     */
    @Transactional
    public LessonTemplateRow saveFromLesson(Long classId, Long sectionId, Long lessonId,
                                            Long userId, Role role) {
        Lesson lesson = lessonsService.getEditableLesson(
                classId, sectionId, lessonId, userId, role);
        String type = lesson.getContentType() == null
                ? CONTENT_TYPE_RICHTEXT : lesson.getContentType();

        LessonTemplate template = new LessonTemplate(userId, lesson.getTitle(), type);
        applyBodyFromLesson(template, lesson, userId);
        LessonTemplate saved = templateRepository.saveAndFlush(template);

        // Supplementary attachments: skip the main PDF row (already on template).
        List<LessonAttachment> atts =
                attachmentRepository.findByLessonIdOrderByUploadedAtAsc(lessonId);
        Long mainPdfId = lesson.getPdfAttachmentId();
        int order = 0;
        for (LessonAttachment att : atts) {
            if (mainPdfId != null && mainPdfId.equals(att.getId())) {
                continue;
            }
            LibraryAsset asset = resolveOrPromoteDocument(att, userId);
            LessonTemplateAttachment row = new LessonTemplateAttachment(
                    saved.getId(), asset.getId(), asset.getOriginalFilename(),
                    asset.getMimeType(), asset.getSizeBytes(), order++);
            templateAttachmentRepository.save(row);
        }
        int attCount = order;
        return toRow(saved, attCount);
    }

    /** Renames an owned template. */
    @Transactional
    public LessonTemplateRow rename(Long ownerId, Long templateId, String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException(MSG_TEMPLATE_TITLE_BLANK);
        }
        LessonTemplate template = getOwned(ownerId, templateId);
        template.rename(newTitle.trim());
        LessonTemplate saved = templateRepository.save(template);
        int attCount = templateAttachmentRepository
                .findByTemplateIdOrderByDisplayOrderAsc(saved.getId()).size();
        return toRow(saved, attCount);
    }

    /** Soft-deletes an owned template (attachment rows stay for FK integrity). */
    @Transactional
    public void softDelete(Long ownerId, Long templateId) {
        LessonTemplate template = getOwned(ownerId, templateId);
        template.markDeleted();
        templateRepository.save(template);
    }

    /**
     * Clones an owned template into an editable class section as a DRAFT lesson.
     */
    @Transactional
    public LessonCloneResult cloneTemplateToSection(Long templateId, Long classId,
                                                    Long sectionId, Long userId, Role role) {
        LessonTemplate template = getOwned(userId, templateId);
        classesService.getEditable(classId, userId, role);
        reorderService.verifySectionBelongsToClass(sectionId, classId);

        Lesson lesson = materializeDraft(sectionId, template.getTitle(),
                template.getContentType(), userId);
        applyTemplateBodyToLesson(lesson, template, userId);
        Lesson saved = lessonRepository.saveAndFlush(lesson);

        List<LessonTemplateAttachment> extras =
                templateAttachmentRepository.findByTemplateIdOrderByDisplayOrderAsc(templateId);
        for (LessonTemplateAttachment extra : extras) {
            LibraryAsset asset = libraryService.getOwnedAsset(userId, extra.getLibraryAssetId());
            LessonAttachment row = new LessonAttachment(
                    saved.getId(), asset.getOriginalFilename(), asset.getStoredPath(),
                    asset.getMimeType(), asset.getSizeBytes(), userId, asset.getId());
            attachmentRepository.save(row);
        }

        activityWriter.write(saved.getId(), LessonActivity.TYPE_CREATED,
                "Tạo bài giảng (clone từ mẫu): " + saved.getTitle(), userId);
        return new LessonCloneResult(saved.getId(), classId, sectionId, saved.getTitle());
    }

    /**
     * Clones a live editable lesson into another editable section as DRAFT.
     * One-off files are promoted into library assets then linked (no shared path).
     */
    @Transactional
    public LessonCloneResult cloneLessonToSection(Long sourceClassId, Long sourceSectionId,
                                                  Long sourceLessonId,
                                                  Long targetClassId, Long targetSectionId,
                                                  Long userId, Role role) {
        Lesson source = lessonsService.getEditableLesson(
                sourceClassId, sourceSectionId, sourceLessonId, userId, role);
        classesService.getEditable(targetClassId, userId, role);
        reorderService.verifySectionBelongsToClass(targetSectionId, targetClassId);

        String type = source.getContentType() == null
                ? CONTENT_TYPE_RICHTEXT : source.getContentType();
        Lesson lesson = materializeDraft(targetSectionId, source.getTitle(), type, userId);
        applyLessonBodyToLesson(lesson, source, userId);
        Lesson saved = lessonRepository.saveAndFlush(lesson);

        Long mainPdfId = source.getPdfAttachmentId();
        List<LessonAttachment> atts =
                attachmentRepository.findByLessonIdOrderByUploadedAtAsc(sourceLessonId);
        for (LessonAttachment att : atts) {
            if (mainPdfId != null && mainPdfId.equals(att.getId())) {
                continue;
            }
            LibraryAsset asset = resolveOrPromoteDocument(att, userId);
            LessonAttachment row = new LessonAttachment(
                    saved.getId(), asset.getOriginalFilename(), asset.getStoredPath(),
                    asset.getMimeType(), asset.getSizeBytes(), userId, asset.getId());
            attachmentRepository.save(row);
        }

        activityWriter.write(saved.getId(), LessonActivity.TYPE_CREATED,
                "Tạo bài giảng (clone từ bài): " + saved.getTitle(), userId);
        return new LessonCloneResult(saved.getId(), targetClassId, targetSectionId, saved.getTitle());
    }

    // ── Body mapping ────────────────────────────────────────────────────

    private void applyBodyFromLesson(LessonTemplate template, Lesson lesson, Long ownerId) {
        String type = template.getContentType();
        if (CONTENT_TYPE_RICHTEXT.equals(type)) {
            String html = lesson.getContentRichtext() == null ? "" : lesson.getContentRichtext();
            template.setContentRichtext(HtmlSanitizer.sanitize(html));
            return;
        }
        if (CONTENT_TYPE_PDF.equals(type)) {
            Long pdfId = lesson.getPdfAttachmentId();
            if (pdfId == null) {
                throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
            }
            LessonAttachment att = attachmentRepository.findById(pdfId)
                    .orElseThrow(() -> new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE));
            LibraryAsset asset = resolveOrPromoteDocument(att, ownerId);
            if (!"application/pdf".equalsIgnoreCase(asset.getMimeType())) {
                throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
            }
            template.setPdfLibraryAssetId(asset.getId());
            return;
        }
        if (CONTENT_TYPE_VIDEO.equals(type)) {
            applyVideoFromLesson(template, lesson, ownerId);
            return;
        }
        throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
    }

    private void applyVideoFromLesson(LessonTemplate template, Lesson lesson, Long ownerId) {
        String provider = lesson.getVideoProvider();
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
        }
        if (VIDEO_PROVIDER_YOUTUBE.equals(provider) || VIDEO_PROVIDER_VIMEO.equals(provider)) {
            if (lesson.getVideoUrl() == null || lesson.getVideoUrl().isBlank()) {
                throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
            }
            template.setVideoProvider(provider);
            template.setVideoUrl(lesson.getVideoUrl());
            return;
        }
        if (VIDEO_PROVIDER_UPLOAD.equals(provider)) {
            LibraryAsset asset;
            if (lesson.hasLibraryVideo()) {
                asset = libraryService.getOwnedAsset(ownerId, lesson.getVideoLibraryAssetId());
            } else {
                asset = promoteOneOffVideo(lesson, ownerId);
            }
            template.setVideoProvider(VIDEO_PROVIDER_UPLOAD);
            template.setVideoLibraryAssetId(asset.getId());
            template.setVideoUrl(asset.getStoredPath());
            return;
        }
        throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
    }

    private void applyTemplateBodyToLesson(Lesson lesson, LessonTemplate template, Long userId) {
        String type = template.getContentType();
        if (CONTENT_TYPE_RICHTEXT.equals(type)) {
            lesson.switchContentTypeTo(CONTENT_TYPE_RICHTEXT);
            String html = template.getContentRichtext() == null ? "" : template.getContentRichtext();
            lesson.updateContent(HtmlSanitizer.sanitize(html));
            return;
        }
        if (CONTENT_TYPE_PDF.equals(type)) {
            LibraryAsset asset = libraryService.getOwnedAsset(
                    userId, template.getPdfLibraryAssetId());
            // Attachment row first so pdf_attachment_id CHECK can pass after type switch.
            LessonAttachment row = new LessonAttachment(
                    lesson.getId(), asset.getOriginalFilename(), asset.getStoredPath(),
                    asset.getMimeType(), asset.getSizeBytes(), userId, asset.getId());
            LessonAttachment savedAtt = attachmentRepository.saveAndFlush(row);
            lesson.setPdfAttachmentId(savedAtt.getId());
            lesson.switchContentTypeTo(CONTENT_TYPE_PDF);
            // switchContentTypeTo nulls pdf_attachment_id — restore after switch.
            lesson.setPdfAttachmentId(savedAtt.getId());
            return;
        }
        if (CONTENT_TYPE_VIDEO.equals(type)) {
            applyTemplateVideoToLesson(lesson, template, userId);
            return;
        }
        throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
    }

    private void applyTemplateVideoToLesson(Lesson lesson, LessonTemplate template, Long userId) {
        String provider = template.getVideoProvider();
        if (VIDEO_PROVIDER_YOUTUBE.equals(provider) || VIDEO_PROVIDER_VIMEO.equals(provider)) {
            lesson.switchContentTypeTo(CONTENT_TYPE_VIDEO);
            lesson.setVideoProvider(provider);
            lesson.setVideoUrl(template.getVideoUrl());
            return;
        }
        if (VIDEO_PROVIDER_UPLOAD.equals(provider)) {
            LibraryAsset asset = libraryService.getOwnedAsset(
                    userId, template.getVideoLibraryAssetId());
            lesson.switchContentTypeTo(CONTENT_TYPE_VIDEO);
            lesson.setVideoProvider(VIDEO_PROVIDER_UPLOAD);
            lesson.setVideoLibraryAssetId(asset.getId());
            lesson.setVideoUrl(asset.getStoredPath());
            return;
        }
        throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
    }

    private void applyLessonBodyToLesson(Lesson dest, Lesson source, Long userId) {
        String type = source.getContentType() == null
                ? CONTENT_TYPE_RICHTEXT : source.getContentType();
        if (CONTENT_TYPE_RICHTEXT.equals(type)) {
            dest.switchContentTypeTo(CONTENT_TYPE_RICHTEXT);
            String html = source.getContentRichtext() == null ? "" : source.getContentRichtext();
            dest.updateContent(HtmlSanitizer.sanitize(html));
            return;
        }
        if (CONTENT_TYPE_PDF.equals(type)) {
            Long pdfId = source.getPdfAttachmentId();
            if (pdfId == null) {
                throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
            }
            LessonAttachment att = attachmentRepository.findById(pdfId)
                    .orElseThrow(() -> new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE));
            LibraryAsset asset = resolveOrPromoteDocument(att, userId);
            LessonAttachment row = new LessonAttachment(
                    dest.getId(), asset.getOriginalFilename(), asset.getStoredPath(),
                    asset.getMimeType(), asset.getSizeBytes(), userId, asset.getId());
            LessonAttachment savedAtt = attachmentRepository.saveAndFlush(row);
            dest.setPdfAttachmentId(savedAtt.getId());
            dest.switchContentTypeTo(CONTENT_TYPE_PDF);
            dest.setPdfAttachmentId(savedAtt.getId());
            return;
        }
        if (CONTENT_TYPE_VIDEO.equals(type)) {
            String provider = source.getVideoProvider();
            if (VIDEO_PROVIDER_YOUTUBE.equals(provider) || VIDEO_PROVIDER_VIMEO.equals(provider)) {
                dest.switchContentTypeTo(CONTENT_TYPE_VIDEO);
                dest.setVideoProvider(provider);
                dest.setVideoUrl(source.getVideoUrl());
                return;
            }
            if (VIDEO_PROVIDER_UPLOAD.equals(provider)) {
                LibraryAsset asset;
                if (source.hasLibraryVideo()) {
                    asset = libraryService.getOwnedAsset(userId, source.getVideoLibraryAssetId());
                } else {
                    asset = promoteOneOffVideo(source, userId);
                }
                dest.switchContentTypeTo(CONTENT_TYPE_VIDEO);
                dest.setVideoProvider(VIDEO_PROVIDER_UPLOAD);
                dest.setVideoLibraryAssetId(asset.getId());
                dest.setVideoUrl(asset.getStoredPath());
                return;
            }
        }
        throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
    }

    // ── Promote helpers ─────────────────────────────────────────────────

    /**
     * Reuses an existing library asset FK or copies a one-off attachment into
     * the owner's library. Never returns a path under {@code lessons/}.
     */
    private LibraryAsset resolveOrPromoteDocument(LessonAttachment att, Long ownerId) {
        if (att.isLibraryBacked()) {
            return libraryService.getOwnedAsset(ownerId, att.getLibraryAssetId());
        }
        try {
            Path source = resolveAttachmentSource(att);
            StoredLibraryFile stored = libraryStorage.copyFromPath(
                    source, ownerId, att.getOriginalFilename(), KIND_DOCUMENT);
            LibraryAsset asset = new LibraryAsset(
                    ownerId, att.getOriginalFilename(), stored.originalFilename(),
                    stored.storedPath(), stored.mimeType(), stored.sizeBytes(), stored.kind());
            return assetRepository.save(asset);
        } catch (IOException ex) {
            throw new IllegalStateException(MSG_TEMPLATE_PROMOTE_FAILED, ex);
        }
    }

    private LibraryAsset promoteOneOffVideo(Lesson lesson, Long ownerId) {
        String url = lesson.getVideoUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
        }
        try {
            Path source;
            if (UploadFileHelper.isLibraryStoredPath(url)) {
                // Defensive: treat as already-library path without FK.
                source = libraryStorage.resolveAbsolutePath(url);
            } else {
                source = videoStorage.resolveAbsolutePath(url);
            }
            if (!Files.isRegularFile(source)) {
                throw new IllegalArgumentException(MSG_TEMPLATE_BODY_INCOMPLETE);
            }
            String filename = source.getFileName().toString();
            StoredLibraryFile stored = libraryStorage.copyFromPath(
                    source, ownerId, filename, KIND_VIDEO);
            LibraryAsset asset = new LibraryAsset(
                    ownerId, filename, stored.originalFilename(),
                    stored.storedPath(), stored.mimeType(), stored.sizeBytes(), stored.kind());
            return assetRepository.save(asset);
        } catch (IOException ex) {
            throw new IllegalStateException(MSG_TEMPLATE_PROMOTE_FAILED, ex);
        }
    }

    private Path resolveAttachmentSource(LessonAttachment att) {
        if (att.isLibraryBacked() || UploadFileHelper.isLibraryStoredPath(att.getStoredPath())) {
            return libraryStorage.resolveAbsolutePath(att.getStoredPath());
        }
        return attachmentStorage.resolveAbsolutePath(att.getStoredPath());
    }

    private Lesson materializeDraft(Long sectionId, String title, String contentType, Long userId) {
        short nextOrder = (short) (lessonRepository.findMaxDisplayOrder(sectionId) + 1);
        Lesson lesson = new Lesson(sectionId, title, nextOrder, userId);
        // Constructor defaults RICHTEXT+""; PDF/VIDEO body filled after first save
        // so we have a lesson id for attachment FKs.
        Lesson saved = lessonRepository.saveAndFlush(lesson);
        if (!CONTENT_TYPE_RICHTEXT.equals(contentType)) {
            // Keep as RICHTEXT empty until body is applied — avoids CHECK violation.
            return saved;
        }
        return saved;
    }

    private LessonTemplate getOwned(Long ownerId, Long templateId) {
        return templateRepository.findByIdAndOwnerId(templateId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_TEMPLATE_NOT_FOUND));
    }

    private static LessonTemplateRow toRow(LessonTemplate t, int attachmentCount) {
        return new LessonTemplateRow(
                t.getId(), t.getTitle(), t.getContentType(),
                t.getUpdatedAt(), attachmentCount);
    }

    private static PageRequest pageRequest(int page, int size) {
        int p = Math.max(page, 0);
        int s = size <= 0 ? DEFAULT_LIBRARY_PAGE_SIZE
                : Math.min(size, MAX_LIBRARY_PAGE_SIZE);
        return PageRequest.of(p, s);
    }

    private static String normalizeQ(String q) {
        if (q == null) return null;
        String t = q.trim();
        return t.isEmpty() ? null : t;
    }
}
