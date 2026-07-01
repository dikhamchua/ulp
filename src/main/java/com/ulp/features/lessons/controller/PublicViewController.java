package com.ulp.features.lessons.controller;

import com.ulp.features.lessons.service.PublicViewTokenService;
import com.ulp.features.lessons.service.PublicViewTokenService.AttachmentHandle;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;

/**
 * Serves attachment files to anonymous viewers via short-lived tokens.
 * Endpoint is {@code permitAll} in SecurityConfig. Tokens expire after 1
 * hour and are cleaned up on a schedule.
 *
 * <p>Overrides {@code X-Frame-Options} and {@code Content-Security-Policy}
 * to allow embedding by {@code view.officeapps.live.com} (MS Office Online
 * Viewer). Spring Security's {@code HeaderWriterFilter} checks for an
 * existing header before writing, so the controller-level override wins.
 */
@Controller
public class PublicViewController {

    private static final Logger log = LoggerFactory.getLogger(PublicViewController.class);

    private final PublicViewTokenService tokenService;

    public PublicViewController(PublicViewTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping("/public/view/{token}")
    @ResponseBody
    public ResponseEntity<Resource> view(@PathVariable String token) {
        AttachmentHandle handle;
        try {
            handle = tokenService.resolve(token);
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        InputStream in;
        try {
            in = Files.newInputStream(handle.absolutePath());
        } catch (NoSuchFileException ex) {
            log.warn("Public view token resolved to missing file: {}", handle.absolutePath());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IOException ex) {
            log.error("Failed to read attachment file {}", handle.absolutePath(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(safeFilename(handle.originalFilename()), StandardCharsets.UTF_8)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        headers.setContentType(parseMime(handle.mimeType()));
        // Allow MS Office Online Viewer to embed this file in its iframe.
        // Only CSP frame-ancestors is honoured by modern browsers; the
        // deprecated X-Frame-Options ALLOW-FROM is intentionally omitted.
        headers.set("Content-Security-Policy",
                "frame-ancestors https://view.officeapps.live.com");
        return new ResponseEntity<>(new InputStreamResource(in), headers, HttpStatus.OK);
    }

    private static String safeFilename(String name) {
        return name == null || name.isBlank() ? "attachment" : name;
    }

    private static MediaType parseMime(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (RuntimeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
