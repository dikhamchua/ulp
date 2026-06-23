package com.ulp.shared.upload;

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
 * Service for storing user avatar images on the local filesystem.
 *
 * <p>Validates content type ({@code image/jpeg}, {@code image/png}, {@code image/webp})
 * and file size (max 2 MB). Files are stored under a configurable upload directory
 * and assigned a random UUID-based name to prevent path-traversal and filename-spoofing attacks.
 */
@Service
public class AvatarStorageService {

    private static final Logger log = LoggerFactory.getLogger(AvatarStorageService.class);
    private static final long MAX_SIZE = 2 * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp");

    private final Path uploadDir;

    public AvatarStorageService(@Value("${app.upload.dir:uploads}") String dir) {
        this.uploadDir = Paths.get(dir, "avatars").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create avatar upload directory: " + uploadDir, e);
        }
    }

    /**
     * Stores an avatar file and returns its relative URL (e.g. {@code /uploads/avatars/x.jpg}).
     *
     * <p>Validation steps:
     * <ul>
     *   <li>File must not be empty.</li>
     *   <li>File size must not exceed 2 MB.</li>
     *   <li>Declared {@code Content-Type} must be {@code image/jpeg}, {@code image/png}, or {@code image/webp}.</li>
     *   <li>Magic bytes in the file header must match the declared type.</li>
     * </ul>
     *
     * @param file the multipart file uploaded by the client
     * @return a relative URL path to the stored avatar
     * @throws IllegalArgumentException if the file is empty, exceeds the size limit, has a disallowed content type,
     *                                  or its content does not match a valid image format
     * @throws IOException if the file cannot be written to the upload directory
     */
    public String store(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException("File exceeds the 2 MB size limit");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Only JPEG, PNG, or WebP images are accepted");
        }

        // Verify the actual content type by inspecting magic bytes
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

        return "/uploads/avatars/" + filename;
    }

    /**
     * Validates the file content by inspecting its magic bytes to confirm it is a real image.
     *
     * <p>Reads up to the first 12 bytes of the input stream and checks for known image signatures:
     * <ul>
     *   <li>JPEG: {@code FF D8 FF}</li>
     *   <li>PNG: {@code 89 50 4E 47}</li>
     *   <li>WebP: {@code RIFF} at bytes 0–3 and {@code WEBP} at bytes 8–11</li>
     * </ul>
     *
     * @param file the multipart file to inspect
     * @return {@code true} if the header matches a supported image format; {@code false} otherwise
     * @throws IOException if the file's input stream cannot be read
     */
    private boolean isValidImageContent(MultipartFile file) throws IOException {
        // WebP requires 12 leading bytes (RIFF....WEBP); JPEG/PNG only need 4–8.
        byte[] header;
        try (var in = file.getInputStream()) {
            header = in.readNBytes(12);
        }
        if (header.length < 4) return false;

        // JPEG: FF D8 FF
        if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF) return true;
        // PNG: 89 50 4E 47
        if (header[0] == (byte) 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') return true;
        // WebP: "RIFF" at bytes 0–3 and "WEBP" at bytes 8–11
        if (header.length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') return true;
        return false;
    }
}
