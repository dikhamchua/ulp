package com.ulp.features.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

/**
 * Stores images embedded in exam question HTML (Quill editor) under
 * {@code uploads/exams/}. Same validation style as {@link AvatarStorageService}:
 * JPEG/PNG/WebP, max 2 MB, magic-byte check, UUID filenames.
 */
@Service
public class ExamImageStorageService {

    private static final Logger log = LoggerFactory.getLogger(ExamImageStorageService.class);
    private static final long MAX_SIZE = 2 * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp");

    private final Path uploadDir;

    public ExamImageStorageService(@Value("${app.upload.dir:uploads}") String dir) {
        this.uploadDir = Paths.get(dir, "exams").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create exam image upload directory: " + uploadDir, e);
        }
    }

    /**
     * Stores the image and returns a public relative URL
     * (e.g. {@code /uploads/exams/&lt;uuid&gt;.png}).
     */
    public String store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException("File exceeds the 2 MB size limit");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Only JPEG, PNG, or WebP images are accepted");
        }
        if (!isValidImageContent(file)) {
            throw new IllegalArgumentException("File content does not match a valid image format");
        }
        String ext = switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
        String filename = UUID.randomUUID() + ext;
        Path dest = uploadDir.resolve(filename);
        file.transferTo(dest.toFile());
        log.debug("Stored exam image {}", filename);
        return "/uploads/exams/" + filename;
    }

    private boolean isValidImageContent(MultipartFile file) throws IOException {
        byte[] header;
        try (var in = file.getInputStream()) {
            header = in.readNBytes(12);
        }
        if (header.length < 4) return false;
        if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF) return true;
        if (header[0] == (byte) 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') return true;
        if (header.length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return true;
        }
        return false;
    }
}
