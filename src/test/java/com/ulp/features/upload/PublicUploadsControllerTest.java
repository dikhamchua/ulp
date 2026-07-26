package com.ulp.features.upload;

import com.ulp.features.storage.LocalObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PublicUploadsController}: avatars/exams OK, nested
 * private paths and unknown folders 404.
 */
class PublicUploadsControllerTest {

    @TempDir
    Path tempRoot;

    private LocalObjectStorage storage;
    private PublicUploadsController controller;

    @BeforeEach
    void setUp() throws Exception {
        storage = new LocalObjectStorage(tempRoot);
        controller = new PublicUploadsController(storage);

        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
        storage.put("avatars/pic.jpg", new ByteArrayInputStream(jpeg), "image/jpeg", jpeg.length);
        storage.put("exams/q.png", new ByteArrayInputStream(jpeg), "image/png", jpeg.length);
        storage.put("lessons/1/secret.pdf",
                new ByteArrayInputStream("%PDF-".getBytes(StandardCharsets.UTF_8)),
                "application/pdf", 5);
        storage.put("library/1/secret.pdf",
                new ByteArrayInputStream("%PDF-".getBytes(StandardCharsets.UTF_8)),
                "application/pdf", 5);
    }

    @Test
    void servesAvatar() {
        ResponseEntity<?> resp = controller.serveRelativePath("avatars/pic.jpg");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void servesExam() {
        ResponseEntity<?> resp = controller.serveRelativePath("exams/q.png");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectsLessonsFolderTwoSegment() {
        ResponseEntity<?> resp = controller.serveRelativePath("lessons/1");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsLibraryFolderTwoSegment() {
        ResponseEntity<?> resp = controller.serveRelativePath("library/1");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsNestedLessonsPath() {
        // Former bug: 3+ segments never hit the controller → static 500.
        ResponseEntity<?> resp = controller.serveRelativePath("lessons/1/foo.pdf");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsNestedLibraryPath() {
        ResponseEntity<?> resp = controller.serveRelativePath("library/1/x.pdf");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsNestedAvatarPath() {
        ResponseEntity<?> resp = controller.serveRelativePath("avatars/sub/x.jpg");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsTraversalFilename() {
        ResponseEntity<?> resp = controller.serveRelativePath("avatars/..");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsTraversalInPath() {
        ResponseEntity<?> resp = controller.serveRelativePath("avatars/../exams/q.png");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingAvatarReturns404() {
        ResponseEntity<?> resp = controller.serveRelativePath("avatars/nope.jpg");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void emptyRelativePathReturns404() {
        ResponseEntity<?> resp = controller.serveRelativePath("");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void singleSegmentReturns404() {
        ResponseEntity<?> resp = controller.serveRelativePath("avatars");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
