package com.ulp.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mechanical guard: {@code notifications.js} must stay the SINGLE OWNER of the
 * {@code #flash-data} → {@code UlpToast} drain.
 *
 * <p>Why this test exists: the lesson-edit page used to show the very same
 * success toast twice ("Đã cập nhật bài giảng"). It was not a double form
 * submit — exactly one POST reached the server. The cause was two independent
 * drains of the same flash payload: {@code class-detail.js} is loaded
 * synchronously and fired the first toast without clearing the payload, then
 * {@code notifications.js} (loaded by {@code fragments/app-header.html}) booted
 * afterwards, still saw an undrained payload, and fired the second toast.
 *
 * <p>Nine page scripts carried a copy of that drain, so the duplicate affected
 * every page loading any of them. They were all stripped;
 * {@code notifications.js} keeps the only drain, guarded by
 * {@code data-flash-drained} plus removal of the {@code data-flash-*}
 * attributes.
 *
 * <p>This test fails as soon as a page script grows a drain again. It reads
 * files only, so it needs no Spring context.
 *
 * @see <a href="file:../.claude/rules/flash-toast-drain.md">.claude/rules/flash-toast-drain.md</a>
 */
class FlashDrainSingleOwnerTest {

    /** Static assets root, relative to the Maven project root (the working dir for surefire). */
    private static final Path JS_DIR = Paths.get("src/main/resources/static/js");

    /** The only file allowed to read the flash payload attributes. */
    private static final Set<String> ALLOWED_DRAINERS = Set.of("notifications.js");

    /**
     * Markers of an actual flash→toast drain. Matching on the {@code data-flash-*}
     * attributes (and their camelCase {@code dataset} equivalents) rather than on
     * the bare {@code flash-data} id is deliberate: {@code admin-users.js}
     * legitimately reads {@code #flash-data} for unrelated modal re-open state
     * ({@code data-lock-reopen-user-id}), which is not a drain and must not fail
     * this test.
     */
    private static final List<String> DRAIN_MARKERS = List.of(
            "data-flash-success", "data-flash-error",
            "data-flash-info", "data-flash-warning",
            "flashsuccess", "flasherror", "flashinfo", "flashwarning");

    private static final String RULE_DOC = ".claude/rules/flash-toast-drain.md";

    @Test
    @DisplayName("only notifications.js drains the #flash-data payload into toasts")
    void onlyNotificationsJsDrainsFlashData() {
        assertThat(JS_DIR)
                .as("static JS directory must be resolvable from the project root; "
                        + "run the test suite from the Maven project root")
                .isDirectory();

        List<String> offenders = jsFiles().stream()
                .filter(file -> !ALLOWED_DRAINERS.contains(file.getFileName().toString()))
                .filter(FlashDrainSingleOwnerTest::containsDrainMarker)
                .map(file -> JS_DIR.relativize(file).toString().replace('\\', '/'))
                .sorted()
                .collect(Collectors.toList());

        assertThat(offenders)
                .as("These page scripts read the data-flash-* payload themselves. "
                        + "notifications.js (loaded by fragments/app-header.html) is the single "
                        + "owner of the flash to toast drain; a second drain fires a duplicate "
                        + "toast for the same flash. Remove the drain from these files and let "
                        + "notifications.js handle it. See " + RULE_DOC + ". Offending files")
                .isEmpty();
    }

    @Test
    @DisplayName("the allowlisted drainer still performs the drain")
    void allowlistedDrainerStillDrains() {
        for (String allowed : ALLOWED_DRAINERS) {
            Path file = JS_DIR.resolve(allowed);
            assertThat(file)
                    .as("allowlisted drainer %s must exist", allowed)
                    .isRegularFile();
            assertThat(containsDrainMarker(file))
                    .as("%s is the single owner of the flash drain but no longer reads the "
                            + "data-flash-* payload; flash messages would silently stop "
                            + "surfacing as toasts. See %s", allowed, RULE_DOC)
                    .isTrue();
        }
    }

    private static List<Path> jsFiles() {
        try (Stream<Path> paths = Files.walk(JS_DIR)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".js"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan " + JS_DIR.toAbsolutePath(), e);
        }
    }

    private static boolean containsDrainMarker(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file.toAbsolutePath(), e);
        }
        return DRAIN_MARKERS.stream().anyMatch(content::contains);
    }
}
