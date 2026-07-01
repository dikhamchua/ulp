package com.ulp.features.lessons.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.controller.support.LessonActivityPageLoader;
import com.ulp.features.lessons.controller.support.LessonFormSupport;
import com.ulp.features.lessons.controller.support.MutationFailureHandler;
import com.ulp.features.lessons.dto.LessonDtos.LessonForm;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.service.LessonAttachmentsService;
import com.ulp.features.lessons.service.LessonsService;
import com.ulp.features.lessons.support.VimeoEmbedUrl;
import com.ulp.features.lessons.support.YouTubeEmbedUrl;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.*;
import static com.ulp.features.lessons.controller.support.LessonFormSupport.lessonEditUrl;
import static com.ulp.features.lessons.controller.support.LessonFormSupport.lessonsBaseUrl;
import static com.ulp.features.lessons.controller.support.LessonFormSupport.lessonsTabUrl;

/**
 * View controller for the Lesson CRUD endpoints inside a class's lessons
 * tab (ULP-4.0b). JSON endpoints (delete, reorder) live on
 * {@link LessonsApiController}.
 *
 * <p>Create / edit run as full-page forms following the
 * {@link com.ulp.features.lessons.controller.SectionsController} pattern.
 * Publish / unpublish are form-POSTs that just call into the service and
 * redirect back.
 *
 * <p>Authorization runs at two layers: this class is protected by
 * {@code @PreAuthorize(PREAUTH_LECTURER_OR_ABOVE)} (blocks STUDENT and
 * anonymous), and {@link LessonsService} additionally enforces ownership
 * + the section↔class binding — blocking cross-class enumeration attempts.
 */
@Controller
@RequestMapping("/lecturer/classes/{classId}/sections/{sectionId}/lessons")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LessonsController {

    private static final Logger log = LoggerFactory.getLogger(LessonsController.class);

    // ── Flash messages (Vietnamese UI text — local to this controller) ─
    private static final String MSG_LESSON_CREATE_FAILED  = "Tạo bài giảng thất bại, vui lòng thử lại.";
    private static final String MSG_LESSON_UPDATE_FAILED  = "Cập nhật bài giảng thất bại, vui lòng thử lại.";

    private final LessonsService lessonsService;
    private final ClassesService classesService;
    private final LessonRepository lessonRepository;
    private final LessonActivityPageLoader activityPageLoader;
    private final LessonFormSupport formSupport;
    private final LessonAttachmentsService attachmentsService;

    public LessonsController(LessonsService lessonsService,
                             ClassesService classesService,
                             LessonRepository lessonRepository,
                             LessonActivityPageLoader activityPageLoader,
                             LessonFormSupport formSupport,
                             LessonAttachmentsService attachmentsService) {
        this.lessonsService = lessonsService;
        this.classesService = classesService;
        this.lessonRepository = lessonRepository;
        this.activityPageLoader = activityPageLoader;
        this.formSupport = formSupport;
        this.attachmentsService = attachmentsService;
    }

    // ── Create lesson — full-page form ─────────────────────────────────

    @GetMapping("/new")
    public String renderCreateForm(@PathVariable Long classId,
                                   @PathVariable Long sectionId,
                                   @AuthenticationPrincipal UlpUserDetails user,
                                   Model model) {
        ClassEntity clazz = classesService.getEditable(classId, user.getId(), user.getRole());
        Section section = formSupport.loadSection(classId, sectionId);
        if (!model.containsAttribute(ATTR_FORM)) {
            // Default to RICHTEXT so legacy submit behavior is preserved.
            model.addAttribute(ATTR_FORM,
                    new LessonForm("", LESSON_STATUS_DRAFT, "",
                            CONTENT_TYPE_RICHTEXT, null, null));
        }
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_SECTION, section);
        model.addAttribute(ATTR_MODE, MODE_CREATE);
        model.addAttribute(ATTR_FORM_ACTION, lessonsBaseUrl(classId, sectionId));
        model.addAttribute(ATTR_CANCEL_URL, lessonsTabUrl(classId, sectionId));
        return VIEW_LESSON_FORM;
    }

    @PostMapping
    public String createLesson(@PathVariable Long classId,
                               @PathVariable Long sectionId,
                               @Valid @ModelAttribute("form") LessonForm form,
                               BindingResult result,
                               @AuthenticationPrincipal UlpUserDetails user,
                               Model model,
                               RedirectAttributes ra) {
        if (result.hasErrors()) {
            return formSupport.reRenderForm(classId, sectionId, user, model, MODE_CREATE,
                    lessonsBaseUrl(classId, sectionId));
        }
        try {
            lessonsService.create(classId, sectionId,
                    form.title().trim(), form.status(), form.contentHtml(),
                    user.getId(), user.getRole());
        } catch (RuntimeException ex) {
            return MutationFailureHandler.handle(ex,
                    lessonsTabUrl(classId, sectionId), ra,
                    MSG_LESSON_CREATE_FAILED, log,
                    "Failed to create lesson in class " + classId
                            + " / section " + sectionId);
        }
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_LESSON_CREATED);
        return "redirect:" + lessonsTabUrl(classId, sectionId);
    }

    // ── Edit lesson — full-page form ───────────────────────────────────

    @GetMapping("/{lessonId}/edit")
    public String renderEditForm(@PathVariable Long classId,
                                 @PathVariable Long sectionId,
                                 @PathVariable Long lessonId,
                                 @RequestParam(defaultValue = TAB_INFO) String tab,
                                 @RequestParam(defaultValue = "0") int page,
                                 @AuthenticationPrincipal UlpUserDetails user,
                                 Model model) {
        ClassEntity clazz = classesService.getEditable(classId, user.getId(), user.getRole());
        Section section = formSupport.loadSection(classId, sectionId);
        Lesson lesson = lessonRepository.findByIdAndSectionId(lessonId, sectionId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_LESSON_NOT_FOUND));
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, new LessonForm(
                    lesson.getTitle(), lesson.getStatus(),
                    lesson.getContentRichtext() == null ? "" : lesson.getContentRichtext(),
                    lesson.getContentType() == null ? CONTENT_TYPE_RICHTEXT : lesson.getContentType(),
                    lesson.getVideoUrl(), lesson.getVideoProvider()));
        }
        String activeDetailTab = TAB_HISTORY.equals(tab) ? TAB_HISTORY : TAB_INFO;
        // Eager-load the activity page so the tab toggle is purely client-side.
        model.addAttribute(ATTR_ACTIVITY_PAGE,
                activityPageLoader.load(lessonId, Math.max(page, 0)));
        // Preload attachments so the form renders the existing list without a
        // second round-trip; the JS layer only needs to handle inserts/deletes.
        model.addAttribute(ATTR_ATTACHMENTS,
                attachmentsService.listForLesson(classId, sectionId, lessonId,
                        user.getId(), user.getRole()));
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_SECTION, section);
        model.addAttribute(ATTR_LESSON, lesson);
        model.addAttribute(ATTR_MODE, MODE_EDIT);
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, activeDetailTab);
        model.addAttribute(ATTR_FORM_ACTION, lessonEditUrl(classId, sectionId, lessonId));
        model.addAttribute(ATTR_CANCEL_URL, lessonsTabUrl(classId, sectionId));
        model.addAttribute(ATTR_EDIT_BASE_URL, lessonEditUrl(classId, sectionId, lessonId));
        return VIEW_LESSON_FORM;
    }

    @PostMapping("/{lessonId}/edit")
    public String editLesson(@PathVariable Long classId,
                             @PathVariable Long sectionId,
                             @PathVariable Long lessonId,
                             @Valid @ModelAttribute("form") LessonForm form,
                             BindingResult result,
                             @AuthenticationPrincipal UlpUserDetails user,
                             Model model,
                             RedirectAttributes ra) {
        // External video URL is now saved by this form (the "Lưu URL" AJAX
        // button is gone), so validate it here the same way the old endpoint
        // did. UPLOAD keeps its stored MP4 path, so only YouTube/Vimeo check.
        String videoProvider = form.videoProvider();
        if (CONTENT_TYPE_VIDEO.equals(form.effectiveContentType())
                && (VIDEO_PROVIDER_YOUTUBE.equals(videoProvider)
                    || VIDEO_PROVIDER_VIMEO.equals(videoProvider))) {
            String videoUrl = form.videoUrl();
            boolean validUrl = videoUrl != null
                    && (VIDEO_PROVIDER_YOUTUBE.equals(videoProvider)
                        ? YouTubeEmbedUrl.matches(videoUrl)
                        : VimeoEmbedUrl.matches(videoUrl));
            if (!validUrl) {
                result.rejectValue("videoUrl", "video.url.invalid", MSG_VIDEO_URL_INVALID);
            }
        }
        if (result.hasErrors()) {
            // Reload section + lesson so the form template can render header context.
            Section section = formSupport.loadSection(classId, sectionId);
            Lesson lesson = lessonRepository.findByIdAndSectionId(lessonId, sectionId)
                    .orElseThrow(() -> new EntityNotFoundException(MSG_LESSON_NOT_FOUND));
            model.addAttribute(ATTR_SECTION, section);
            model.addAttribute(ATTR_LESSON, lesson);
            model.addAttribute(ATTR_ACTIVITY_PAGE, activityPageLoader.load(lessonId, 0));
            return formSupport.reRenderForm(classId, sectionId, user, model, MODE_EDIT,
                    lessonEditUrl(classId, sectionId, lessonId));
        }
        try {
            LessonForm trimmed = new LessonForm(form.title().trim(), form.status(),
                    form.contentHtml(), form.effectiveContentType(),
                    form.videoUrl(), form.videoProvider());
            lessonsService.update(classId, sectionId, lessonId,
                    trimmed, user.getId(), user.getRole());
        } catch (RuntimeException ex) {
            return MutationFailureHandler.handle(ex,
                    lessonsTabUrl(classId, sectionId), ra,
                    MSG_LESSON_UPDATE_FAILED, log,
                    "Failed to update lesson " + lessonId + " in class " + classId);
        }
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_LESSON_UPDATED);
        // Stay on the edit page so newly-appended history rows are visible.
        return "redirect:" + lessonEditUrl(classId, sectionId, lessonId);
    }
}
