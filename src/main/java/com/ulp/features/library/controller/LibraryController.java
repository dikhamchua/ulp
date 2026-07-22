package com.ulp.features.library.controller;

import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.library.dto.LibraryDtos.LibraryAssetRow;
import com.ulp.features.library.dto.LibraryDtos.LibraryPageView;
import com.ulp.features.library.dto.LibraryDtos.LibraryPickerPage;
import com.ulp.features.library.service.LibraryService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.ulp.common.IConstant.ATTR_FLASH_ERROR;
import static com.ulp.common.IConstant.ATTR_FLASH_SUCCESS;
import static com.ulp.common.IConstant.ATTR_LIBRARY_DOCUMENT_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_KIND;
import static com.ulp.common.IConstant.ATTR_LIBRARY_PAGE;
import static com.ulp.common.IConstant.ATTR_LIBRARY_QUERY;
import static com.ulp.common.IConstant.ATTR_LIBRARY_SIZE;
import static com.ulp.common.IConstant.ATTR_LIBRARY_TAB;
import static com.ulp.common.IConstant.ATTR_LIBRARY_TEMPLATE_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_TOTAL_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_VIDEO_COUNT;
import static com.ulp.common.IConstant.ATTR_PAGER_PARAMS;
import static com.ulp.common.IConstant.BASE_LECTURER;
import static com.ulp.common.IConstant.DEFAULT_LIBRARY_PAGE_SIZE;
import static com.ulp.common.IConstant.LIBRARY_TAB_TEMPLATES;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_TOO_LARGE;
import static com.ulp.common.IConstant.MSG_GENERIC_RETRY;
import static com.ulp.common.IConstant.MSG_LIBRARY_DELETED;
import static com.ulp.common.IConstant.MSG_LIBRARY_RENAMED;
import static com.ulp.common.IConstant.MSG_LIBRARY_UPLOADED;
import static com.ulp.common.IConstant.PATH_LIBRARY;
import static com.ulp.common.IConstant.URL_LIBRARY;
import static com.ulp.common.IConstant.VIEW_LIBRARY;

/**
 * SSR + JSON endpoints for the personal lecturer file library.
 *
 * <p>{@code GET /lecturer/library} renders the page; form POSTs handle upload /
 * rename / delete with flash redirects. {@code GET /lecturer/library/api}
 * feeds the lesson-form picker modal.
 */
@Controller
@RequestMapping(BASE_LECTURER + PATH_LIBRARY)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LibraryController {

    private static final Logger log = LoggerFactory.getLogger(LibraryController.class);
    private static final String REDIRECT_LIBRARY = "redirect:" + URL_LIBRARY;

    private final LibraryService libraryService;
    private final LessonRepository lessonRepository;

    public LibraryController(LibraryService libraryService,
                             LessonRepository lessonRepository) {
        this.libraryService = libraryService;
        this.lessonRepository = lessonRepository;
    }

    @GetMapping
    public String page(@RequestParam(name = "q", defaultValue = "") String q,
                       @RequestParam(name = "kind", defaultValue = "") String kind,
                       @RequestParam(name = "tab", defaultValue = "") String tab,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "size",
                               defaultValue = "" + DEFAULT_LIBRARY_PAGE_SIZE) int size,
                       @AuthenticationPrincipal UlpUserDetails user,
                       Model model) {
        // Dedicated templates rail lives on /library/templates — bounce ?tab=.
        if (LIBRARY_TAB_TEMPLATES.equalsIgnoreCase(blankToNull(tab))) {
            return "redirect:" + URL_LIBRARY + "/templates";
        }
        LibraryPageView view = libraryService.list(user.getId(), q, kind, page, size);
        model.addAttribute(ATTR_LIBRARY_PAGE, view.page());
        model.addAttribute(ATTR_LIBRARY_QUERY, view.q());
        model.addAttribute(ATTR_LIBRARY_KIND, view.kind());
        model.addAttribute(ATTR_LIBRARY_TAB, "");
        model.addAttribute(ATTR_LIBRARY_SIZE, view.page().getSize());
        model.addAttribute(ATTR_LIBRARY_TOTAL_COUNT, view.totalCount());
        model.addAttribute(ATTR_LIBRARY_DOCUMENT_COUNT, view.documentCount());
        model.addAttribute(ATTR_LIBRARY_VIDEO_COUNT, view.videoCount());
        // Sidebar "Bài giảng" badge = live lessons across owned classes.
        model.addAttribute(ATTR_LIBRARY_TEMPLATE_COUNT,
                lessonRepository.countByLecturerId(user.getId(), null));
        model.addAttribute(ATTR_PAGER_PARAMS, pagerParams(view.q(), view.kind(), view.page().getSize()));
        return VIEW_LIBRARY;
    }

    @GetMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public LibraryPickerPage api(@RequestParam(name = "q", defaultValue = "") String q,
                                 @RequestParam(name = "kind", defaultValue = "") String kind,
                                 @RequestParam(name = "page", defaultValue = "0") int page,
                                 @RequestParam(name = "size",
                                         defaultValue = "" + DEFAULT_LIBRARY_PAGE_SIZE) int size,
                                 @AuthenticationPrincipal UlpUserDetails user) {
        return libraryService.listForPicker(user.getId(), q, kind, page, size);
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(name = "kind", required = false) String kind,
                         @RequestParam(name = "returnKind", required = false) String returnKind,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        String stayKind = blankToNull(returnKind);
        try {
            LibraryAssetRow row = libraryService.upload(user.getId(), file, kind);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_LIBRARY_UPLOADED + ": " + row.title());
            // Land on the tab matching the saved asset so a video uploaded from
            // the Video rail is visible immediately (not only under "All").
            return redirectLibrary(row.kind());
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (MaxUploadSizeExceededException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_ATTACHMENT_TOO_LARGE);
        } catch (IOException ex) {
            log.error("Failed to store library file for user {}", user.getId(), ex);
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        } catch (RuntimeException ex) {
            log.error("Unexpected library upload error for user {}", user.getId(), ex);
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        }
        // On failure keep the sidebar filter the lecturer was browsing.
        return redirectLibrary(stayKind != null ? stayKind : kind);
    }

    @PostMapping("/{id}/rename")
    public String rename(@PathVariable Long id,
                         @RequestParam("title") String title,
                         @RequestParam(name = "returnKind", required = false) String returnKind,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        try {
            libraryService.rename(user.getId(), id, title);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_LIBRARY_RENAMED);
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (EntityNotFoundException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to rename library asset {} for user {}", id, user.getId(), ex);
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        }
        return redirectLibrary(returnKind);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(name = "returnKind", required = false) String returnKind,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        try {
            libraryService.delete(user.getId(), id);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_LIBRARY_DELETED);
        } catch (IllegalStateException | IllegalArgumentException | EntityNotFoundException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to delete library asset {} for user {}", id, user.getId(), ex);
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        }
        return redirectLibrary(returnKind);
    }

    /** JSON error body for oversized uploads hitting the library page via XHR. */
    @org.springframework.web.bind.annotation.ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseBody
    public ResponseEntity<?> oversized() {
        return ResponseEntity.badRequest().body(Map.of("message", MSG_ATTACHMENT_TOO_LARGE));
    }

    private static Map<String, Object> pagerParams(String q, String kind, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) params.put("q", q.trim());
        if (kind != null && !kind.isBlank()) params.put("kind", kind.trim());
        params.put("size", size);
        return params;
    }

    /**
     * Redirects back to the library list, optionally preserving a kind filter
     * so the lecturer stays on the DOCUMENT / VIDEO rail they were using.
     */
    private static String redirectLibrary(String kind) {
        String k = blankToNull(kind);
        if (k == null) {
            return REDIRECT_LIBRARY;
        }
        String upper = k.trim().toUpperCase();
        if (!"DOCUMENT".equals(upper) && !"VIDEO".equals(upper)) {
            return REDIRECT_LIBRARY;
        }
        return REDIRECT_LIBRARY + "?kind=" + upper;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
