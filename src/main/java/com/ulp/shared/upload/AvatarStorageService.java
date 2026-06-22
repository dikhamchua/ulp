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
 * Luu tru avatar local. Validate type (image/jpeg|png|webp) va size (<= 2 MB).
 * File duoc dat ten ngau nhien de tranh path traversal/spoofing.
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
     * Luu file avatar. Tra ve URL tuong doi (vi du: /uploads/avatars/x.jpg).
     *
     * @throws IllegalArgumentException neu file vuot kich thuoc hoac sai type
     * @throws IOException neu khong the ghi file
     */
    public String store(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File rỗng");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException("File vượt quá 2 MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Chỉ chấp nhận JPEG, PNG hoặc WebP");
        }

        // Validate content-type thuc te (sniff magic bytes)
        if (!isValidImageContent(file)) {
            throw new IllegalArgumentException("File không phải ảnh hợp lệ");
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

    /** Kiem tra magic bytes de xac nhan file thuc su la anh. */
    private boolean isValidImageContent(MultipartFile file) throws IOException {
        // WebP can 12 byte dau (RIFF....WEBP); JPEG/PNG chi can 4-8.
        byte[] header;
        try (var in = file.getInputStream()) {
            header = in.readNBytes(12);
        }
        if (header.length < 4) return false;

        // JPEG: FF D8 FF
        if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF) return true;
        // PNG: 89 50 4E 47
        if (header[0] == (byte) 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') return true;
        // WebP: "RIFF" (0-3) .... "WEBP" (8-11)
        if (header.length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') return true;
        return false;
    }
}
