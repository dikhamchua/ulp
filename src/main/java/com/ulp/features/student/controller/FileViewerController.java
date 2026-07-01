package com.ulp.features.student.controller;

import com.ulp.features.lessons.service.LessonAttachmentsService;
import com.ulp.features.lessons.service.PublicViewTokenService;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Serves thin viewer pages for PDF (PDF.js) and DOCX (JSZip + docx-preview).
 * The viewer templates fetch the file from the existing download endpoint at
 * runtime — the server never buffers the whole file into the HTML, so large
 * PDFs stream instead of inflating memory.
 */
@Controller
public class FileViewerController {

    private final LessonAttachmentsService attachmentsService;
    private final PublicViewTokenService tokenService;

    public FileViewerController(LessonAttachmentsService attachmentsService,
                                PublicViewTokenService tokenService) {
        this.attachmentsService = attachmentsService;
        this.tokenService = tokenService;
    }

    /**
     * Renders the PDF.js or docx-preview page. The template client-side
     * fetches {@code downloadUrl} (fetch() is not a navigation, so download
     * managers like IDM do not intercept it) and renders from the buffer.
     */
    @GetMapping("/file-viewer")
    @PreAuthorize("isAuthenticated()")
    public String view(@RequestParam String type,
                       @RequestParam Long lessonId,
                       @RequestParam Long attachmentId,
                       @RequestParam(required = false) String filename,
                       @AuthenticationPrincipal UlpUserDetails user,
                       Model model) {
        try {
            // Reuse the download authz gate; discard the handle.
            attachmentsService.download(lessonId, attachmentId,
                    user.getId(), user.getRole());
        } catch (AccessDeniedException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        } catch (EntityNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("downloadUrl",
                "/api/lessons/" + lessonId + "/attachments/" + attachmentId + "/download");
        model.addAttribute("filename",
                filename != null ? filename : "Tài liệu");

        if ("docx".equalsIgnoreCase(type)) {
            return "student/docx-viewer";
        }
        return "student/pdfjs-viewer";
    }

    /**
     * Lazily mints a public view token and redirects to MS Office Online
     * Viewer. Authorization is enforced via {@link LessonAttachmentsService}
     * BEFORE any token is created, so merely listing a lesson never writes a
     * token — only an actual click does.
     */
    @GetMapping("/file-viewer/office")
    @PreAuthorize("isAuthenticated()")
    public String viewOffice(@RequestParam Long lessonId,
                             @RequestParam Long attachmentId,
                             @AuthenticationPrincipal UlpUserDetails user) {
        try {
            // Reuse the download authz gate; discard the handle.
            attachmentsService.download(lessonId, attachmentId,
                    user.getId(), user.getRole());
        } catch (AccessDeniedException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        } catch (EntityNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String publicUrl = tokenService.createPublicViewUrl(attachmentId);
        String embed = "https://view.officeapps.live.com/op/embed.aspx?src="
                + URLEncoder.encode(publicUrl, StandardCharsets.UTF_8);
        return "redirect:" + embed;
    }
}
