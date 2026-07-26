package com.ulp.features.upload;

import com.ulp.features.storage.ObjectStorage;
import com.ulp.features.storage.StorageKeys;
import com.ulp.features.storage.StoredObject;
import com.ulp.features.storage.StoredObjectResource;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Serves the public subset of uploaded objects: {@code avatars/*} and
 * {@code exams/*} only (exactly two path segments). Nested keys such as
 * {@code lessons/1/x.pdf} or {@code library/1/x} intentionally 404 — those
 * require authenticated download/stream endpoints.
 *
 * <p>Maps {@code /uploads/**} so unmatched nested paths never fall through
 * to the static resource resolver (which would surface as a 500 via the
 * catch-all exception handler).
 */
@Controller
public class PublicUploadsController {

    private static final Logger log = LoggerFactory.getLogger(PublicUploadsController.class);

    private static final String UPLOADS_PREFIX = "/uploads/";
    private static final Set<String> PUBLIC_FOLDERS = Set.of("avatars", "exams");

    private final ObjectStorage objectStorage;

    public PublicUploadsController(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    /**
     * Streams a public upload under {@code /uploads/**}.
     * Only {@code avatars/{file}} and {@code exams/{file}} succeed; anything
     * else (unknown folder, nested path, traversal) returns 404.
     */
    @GetMapping("/uploads/**")
    @ResponseBody
    public ResponseEntity<?> serve(HttpServletRequest request) {
        return serveRelativePath(relativePathFrom(request));
    }

    /**
     * Resolves a path relative to {@code /uploads/} (e.g. {@code avatars/a.jpg}).
     * Package-visible for unit tests without spinning up MockMvc.
     */
    ResponseEntity<?> serveRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return notFound();
        }

        String rel = stripLeadingSlashes(relativePath);
        if (rel.isBlank() || rel.contains("..") || rel.contains("\\")) {
            return notFound();
        }

        // Public URLs are exactly folder/filename — no nested directories.
        String[] parts = rel.split("/");
        if (parts.length != 2) {
            return notFound();
        }

        String folderNorm = parts[0] == null ? "" : parts[0].trim().toLowerCase(Locale.ROOT);
        String filename = parts[1] == null ? "" : parts[1].trim();
        if (!PUBLIC_FOLDERS.contains(folderNorm)) {
            return notFound();
        }
        if (filename.isBlank() || filename.contains("..")
                || filename.contains("/") || filename.contains("\\")) {
            return notFound();
        }

        String key = folderNorm + "/" + filename;
        try {
            StorageKeys.requireSafeKey(key);
        } catch (IllegalArgumentException ex) {
            return notFound();
        }

        if (!objectStorage.exists(key)) {
            return notFound();
        }

        try {
            StoredObject obj = objectStorage.open(key);
            HttpHeaders headers = new HttpHeaders();
            String type = obj.contentType();
            if (type != null && !type.isBlank()) {
                try {
                    headers.setContentType(MediaType.parseMediaType(type));
                } catch (RuntimeException ignored) {
                    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
                }
            } else {
                headers.setContentType(guessImageType(filename));
            }
            if (obj.contentLength() >= 0) {
                headers.setContentLength(obj.contentLength());
            }
            headers.setCacheControl("public, max-age=86400");
            return new ResponseEntity<>(new StoredObjectResource(obj, key), headers, HttpStatus.OK);
        } catch (IOException ex) {
            log.warn("Failed to serve public upload {}: {}", key, ex.getMessage());
            return notFound();
        }
    }

    /** Strips context path and {@code /uploads/} prefix; URL-decodes the rest. */
    private static String relativePathFrom(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (!uri.startsWith(UPLOADS_PREFIX)) {
            // Exact /uploads with no trailing segment
            if ("/uploads".equals(uri) || "/uploads/".equals(uri)) {
                return "";
            }
            return uri;
        }
        String raw = uri.substring(UPLOADS_PREFIX.length());
        try {
            return UriUtils.decode(raw, StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            return raw;
        }
    }

    private static String stripLeadingSlashes(String path) {
        String rel = path;
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        return rel;
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    private static MediaType guessImageType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
