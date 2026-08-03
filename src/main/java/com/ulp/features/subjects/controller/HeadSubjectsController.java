package com.ulp.features.subjects.controller;

import com.ulp.features.head.dto.HeadDtos.DepartmentSummary;
import com.ulp.features.subjects.dto.SubjectDtos.ChapterTitleForm;
import com.ulp.features.subjects.dto.SubjectDtos.SubjectForm;
import com.ulp.features.subjects.service.SubjectChapterService;
import com.ulp.features.subjects.service.SubjectChapterService.MoveDirection;
import com.ulp.features.subjects.service.SubjectService;
import com.ulp.features.subjects.service.SubjectValidationException;
import com.ulp.security.Role;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
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

import java.util.Locale;
import java.util.Set;

import static com.ulp.common.IConstant.*;

/**
 * HEAD-scoped subject catalog at {@code /head/subjects}.
 * Edit screen uses detail-page AJAX tabs (info / history / chapters).
 */
@Controller
@RequestMapping(URL_HEAD_SUBJECTS)
@PreAuthorize("hasRole('" + Roles.HEAD + "')")
public class HeadSubjectsController {

    private static final String REDIRECT_BASE = "redirect:" + URL_HEAD_SUBJECTS;
    private static final Set<String> VALID_DETAIL_TABS =
            Set.of(TAB_INFO, TAB_HISTORY, TAB_CHAPTERS);
    private static final int HISTORY_PAGE_SIZE = 20;

    private final SubjectService subjectService;
    private final SubjectChapterService chapterService;

    public HeadSubjectsController(SubjectService subjectService,
                                  SubjectChapterService chapterService) {
        this.subjectService = subjectService;
        this.chapterService = chapterService;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal UlpUserDetails user, Model model) {
        boolean empty = subjectService.isEmptyDepartmentForHead(user.getId());
        model.addAttribute(ATTR_HEAD_EMPTY, empty);
        subjectService.resolveHeadDepartment(user.getId()).ifPresent(d ->
                model.addAttribute(ATTR_HEAD_DEPARTMENT,
                        new DepartmentSummary(d.getId(), d.getCode(), d.getName())));
        if (!empty) {
            model.addAttribute(ATTR_SUBJECTS,
                    subjectService.listForActor(user.getId(), Role.HEAD));
        }
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SUBJECTS);
        return VIEW_HEAD_SUBJECTS;
    }

    @GetMapping("/new")
    public String createForm(@AuthenticationPrincipal UlpUserDetails user,
                             Model model,
                             RedirectAttributes ra) {
        if (subjectService.isEmptyDepartmentForHead(user.getId())) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_HEAD_NO_DEPARTMENT);
            return REDIRECT_BASE;
        }
        if (!model.containsAttribute(ATTR_FORM)) {
            Long deptId = subjectService.resolveHeadDepartment(user.getId())
                    .map(d -> d.getId()).orElse(null);
            model.addAttribute(ATTR_FORM, SubjectForm.emptyForDepartment(deptId));
        }
        populateFormModel(model, user, MODE_CREATE, null, TAB_INFO);
        return VIEW_HEAD_SUBJECTS_FORM;
    }

    @PostMapping
    public String create(@Valid @ModelAttribute(ATTR_FORM) SubjectForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateFormModel(model, user, MODE_CREATE, null, TAB_INFO);
            return VIEW_HEAD_SUBJECTS_FORM;
        }
        try {
            String title = subjectService.create(form, user.getId(), Role.HEAD);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SUBJECT_CREATED + title);
            return REDIRECT_BASE;
        } catch (SubjectValidationException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateFormModel(model, user, MODE_CREATE, null, TAB_INFO);
            return VIEW_HEAD_SUBJECTS_FORM;
        } catch (AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return REDIRECT_BASE;
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @RequestParam(name = "tab", required = false, defaultValue = TAB_INFO) String tab,
                           @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                           @AuthenticationPrincipal UlpUserDetails user,
                           Model model,
                           RedirectAttributes ra) {
        try {
            if (!model.containsAttribute(ATTR_FORM)) {
                model.addAttribute(ATTR_FORM,
                        subjectService.loadForm(id, user.getId(), Role.HEAD));
            }
            String activeTab = VALID_DETAIL_TABS.contains(tab) ? tab : TAB_INFO;
            populateFormModel(model, user, MODE_EDIT, id, activeTab);
            if (TAB_HISTORY.equals(activeTab)) {
                int safePage = Math.max(0, page);
                model.addAttribute(ATTR_ACTIVITIES_PAGE,
                        subjectService.listActivities(
                                id, user.getId(), Role.HEAD,
                                PageRequest.of(safePage, HISTORY_PAGE_SIZE)));
            }
            if (TAB_CHAPTERS.equals(activeTab)) {
                model.addAttribute(ATTR_SUBJECT_CHAPTERS,
                        chapterService.listChapters(id, user.getId(), Role.HEAD));
                if (!model.containsAttribute(ATTR_CHAPTER_FORM)) {
                    model.addAttribute(ATTR_CHAPTER_FORM, ChapterTitleForm.empty());
                }
            }
            return VIEW_HEAD_SUBJECTS_FORM;
        } catch (EntityNotFoundException | AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return REDIRECT_BASE;
        }
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute(ATTR_FORM) SubjectForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateFormModel(model, user, MODE_EDIT, id, TAB_INFO);
            return VIEW_HEAD_SUBJECTS_FORM;
        }
        try {
            subjectService.update(id, form, user.getId(), Role.HEAD);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SUBJECT_UPDATED);
            return "redirect:" + editUrl(id) + "?tab=" + TAB_INFO;
        } catch (SubjectValidationException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateFormModel(model, user, MODE_EDIT, id, TAB_INFO);
            return VIEW_HEAD_SUBJECTS_FORM;
        } catch (EntityNotFoundException | AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return REDIRECT_BASE;
        }
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        try {
            boolean now = subjectService.toggleActive(id, user.getId(), Role.HEAD);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS,
                    now ? MSG_SUBJECT_ACTIVATED : MSG_SUBJECT_DEACTIVATED);
        } catch (EntityNotFoundException | AccessDeniedException | SubjectValidationException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return REDIRECT_BASE;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        try {
            subjectService.softDelete(id, user.getId(), Role.HEAD);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SUBJECT_DELETED);
        } catch (EntityNotFoundException | AccessDeniedException | SubjectValidationException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return REDIRECT_BASE;
    }

    // ── Sample chapter outline (tab=chapters) ────────────────────────────

    @PostMapping("/{id}/chapters")
    public String createChapter(@PathVariable Long id,
                                @Valid @ModelAttribute(ATTR_CHAPTER_FORM) ChapterTitleForm form,
                                BindingResult result,
                                @AuthenticationPrincipal UlpUserDetails user,
                                Model model,
                                RedirectAttributes ra) {
        if (result.hasErrors()) {
            return reRenderChaptersTab(id, user, model, form, ra);
        }
        try {
            chapterService.createChapter(id, form.title(), user.getId(), Role.HEAD);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CHAPTER_CREATED);
            return redirectChapters(id);
        } catch (SubjectValidationException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return reRenderChaptersTab(id, user, model, form, ra);
        } catch (EntityNotFoundException | AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return REDIRECT_BASE;
        }
    }

    @PostMapping("/{id}/chapters/{chapterId}/rename")
    public String renameChapter(@PathVariable Long id,
                                @PathVariable Long chapterId,
                                @RequestParam("title") String title,
                                @AuthenticationPrincipal UlpUserDetails user,
                                RedirectAttributes ra) {
        try {
            chapterService.renameChapter(id, chapterId, title, user.getId(), Role.HEAD);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CHAPTER_RENAMED);
        } catch (SubjectValidationException | EntityNotFoundException | AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return redirectChapters(id);
    }

    @PostMapping("/{id}/chapters/{chapterId}/delete")
    public String deleteChapter(@PathVariable Long id,
                                @PathVariable Long chapterId,
                                @AuthenticationPrincipal UlpUserDetails user,
                                RedirectAttributes ra) {
        try {
            chapterService.softDeleteChapter(id, chapterId, user.getId(), Role.HEAD);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CHAPTER_DELETED);
        } catch (EntityNotFoundException | AccessDeniedException | SubjectValidationException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return redirectChapters(id);
    }

    @PostMapping("/{id}/chapters/{chapterId}/move")
    public String moveChapter(@PathVariable Long id,
                              @PathVariable Long chapterId,
                              @RequestParam("direction") String direction,
                              @AuthenticationPrincipal UlpUserDetails user,
                              RedirectAttributes ra) {
        try {
            MoveDirection dir = parseDirection(direction);
            chapterService.moveChapter(id, chapterId, dir, user.getId(), Role.HEAD);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_CHAPTER_MOVED);
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_CHAPTER_INVALID_DIRECTION);
        } catch (EntityNotFoundException | AccessDeniedException | SubjectValidationException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return redirectChapters(id);
    }

    private String reRenderChaptersTab(Long id, UlpUserDetails user, Model model,
                                       ChapterTitleForm form, RedirectAttributes ra) {
        try {
            if (!model.containsAttribute(ATTR_FORM)) {
                model.addAttribute(ATTR_FORM,
                        subjectService.loadForm(id, user.getId(), Role.HEAD));
            }
            model.addAttribute(ATTR_CHAPTER_FORM, form);
            populateFormModel(model, user, MODE_EDIT, id, TAB_CHAPTERS);
            model.addAttribute(ATTR_SUBJECT_CHAPTERS,
                    chapterService.listChapters(id, user.getId(), Role.HEAD));
            return VIEW_HEAD_SUBJECTS_FORM;
        } catch (EntityNotFoundException | AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return REDIRECT_BASE;
        }
    }

    private void populateFormModel(Model model, UlpUserDetails user,
                                   String mode, Long targetId, String detailTab) {
        subjectService.resolveHeadDepartment(user.getId()).ifPresent(d ->
                model.addAttribute(ATTR_HEAD_DEPARTMENT,
                        new DepartmentSummary(d.getId(), d.getCode(), d.getName())));
        model.addAttribute(ATTR_MODE, mode);
        model.addAttribute(ATTR_TARGET_ID, targetId);
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SUBJECTS);
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, detailTab);
    }

    private static MoveDirection parseDirection(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("direction");
        }
        return MoveDirection.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    private static String redirectChapters(Long id) {
        return "redirect:" + editUrl(id) + "?tab=" + TAB_CHAPTERS;
    }

    private static String editUrl(Long id) {
        return URL_HEAD_SUBJECTS + "/" + id + "/edit";
    }
}
