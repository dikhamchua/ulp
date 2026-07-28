package com.ulp.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mechanical guard for the "gate the submit, then re-fire it" pattern used by
 * the lesson and flashcard-deck forms.
 *
 * <p>Why this test exists: creating a lesson required <em>two</em> clicks on
 * "Tạo bài giảng". The first click did nothing at all; the second one saved.
 * Measured on the running app, the first submit event reached the bubble phase
 * with {@code defaultPrevented === true} and no request left the browser, while
 * the second reached it with {@code defaultPrevented === false} and navigated.
 *
 * <p>Root cause was in {@code lesson-form-type.js}. Its submit listener calls
 * {@code preventDefault()}, runs three async gates (type-switch confirm, video
 * upload, PDF upload), then re-fires the form. For a plain RICHTEXT lesson in
 * create mode all three gates short-circuit <em>synchronously</em>, so
 * {@code form.requestSubmit()} ran while the original submit dispatch was still
 * on the stack. That re-entrant submit is dropped by the browser. The
 * {@code proceeding} flag had already been flipped and was never reset, so the
 * second click skipped the listener entirely and the native submit went
 * through — which is exactly the "works on the second click" symptom.
 *
 * <p>Two invariants keep that from coming back:
 * <ol>
 *   <li>the re-fire must be deferred out of the submit dispatch (a
 *       {@code setTimeout} macrotask, or a promise callback as in
 *       {@code flashcard-deck-form.js} — both unwind the dispatch first);</li>
 *   <li>the {@code proceeding} guard must be released on every abort path, or a
 *       cancelled gate leaves the form permanently unsubmittable.</li>
 * </ol>
 *
 * <p>Reads files only, so it needs no Spring context.
 */
class DeferredResubmitGuardTest {

    /** Static assets root, relative to the Maven project root (the working dir for surefire). */
    private static final Path JS_DIR = Paths.get("src/main/resources/static/js");

    /**
     * Scripts that gate a submit behind async work and then re-fire the form.
     * Both flip a {@code proceeding}-style flag and call {@code requestSubmit()}.
     */
    private static final List<String> GATED_SUBMIT_SCRIPTS =
            List.of("lesson-form-type.js", "flashcard-deck-form.js");

    /**
     * Matches a {@code requestSubmit()} / {@code submit()} re-fire that sits
     * directly in the listener body rather than inside a deferring callback.
     * The check below is structural, not regex-only: this pattern just locates
     * the call sites so their enclosing context can be inspected.
     */
    private static final Pattern RESUBMIT_CALL =
            Pattern.compile("\\b(?:form|f)\\s*\\.\\s*(?:requestSubmit|submit)\\s*\\(");

    /** Constructs that push work out of the current dispatch. */
    private static final List<String> DEFERRAL_MARKERS =
            List.of("setTimeout", ".then(", "requestAnimationFrame", "queueMicrotask");

    @Test
    @DisplayName("gated submit forms re-fire outside the submit dispatch")
    void gatedSubmitFormsDeferTheResubmit() {
        assertThat(JS_DIR)
                .as("static JS directory must be resolvable from the project root; "
                        + "run the test suite from the Maven project root")
                .isDirectory();

        List<String> offenders = new ArrayList<>();
        for (String name : GATED_SUBMIT_SCRIPTS) {
            String content = read(JS_DIR.resolve(name));
            Matcher matcher = RESUBMIT_CALL.matcher(content);
            while (matcher.find()) {
                if (!isDeferred(content, matcher.start())) {
                    offenders.add(name + " (offset " + matcher.start() + ")");
                }
            }
        }

        assertThat(offenders)
                .as("These re-fire the form without leaving the current submit dispatch. "
                        + "Calling requestSubmit() synchronously from inside a submit listener "
                        + "is a re-entrant submit and the browser drops it, so the first click "
                        + "silently does nothing and only the second one saves. Wrap the call in "
                        + "setTimeout(..., 0) (or a promise callback) so the dispatch unwinds "
                        + "first. Offending call sites")
                .isEmpty();
    }

    @Test
    @DisplayName("the lesson form releases its submit guard on every abort path")
    void lessonFormReleasesGuardOnAbort() {
        String content = read(JS_DIR.resolve("lesson-form-type.js"));

        assertThat(content)
                .as("lesson-form-type.js must expose a guard-release helper; without it a "
                        + "cancelled type-switch or a failed media upload leaves 'proceeding' "
                        + "stuck at true and the form can never be submitted again")
                .contains("abortSubmit");

        long earlyReturns = content.lines()
                .filter(line -> line.contains("if (!ok"))
                .count();
        long guardedReturns = content.lines()
                .filter(line -> line.contains("if (!ok") && line.contains("abortSubmit()"))
                .count();

        assertThat(guardedReturns)
                .as("every gate-failure branch (if (!okType/okVideo/okPdf)) must call "
                        + "abortSubmit() before returning, otherwise the submit guard stays "
                        + "latched and the form is permanently stuck. Found %d gate-failure "
                        + "branches but only %d release the guard",
                        earlyReturns, guardedReturns)
                .isEqualTo(earlyReturns)
                .isGreaterThan(0);
    }

    /**
     * Reports whether the re-fire at {@code callOffset} sits inside a deferring
     * construct. Scans backwards counting brackets to find each unclosed
     * {@code (} that still encloses the call site; the text immediately before
     * such a bracket is the callee. If any enclosing callee is a deferral
     * ({@code setTimeout(...)}, {@code .then(...)}, …) the call runs in a later
     * task and the submit dispatch has already unwound.
     *
     * <p>Bracket counting rather than a plain backwards {@code indexOf} because
     * the deferral keyword and the {@code function} literal it wraps are
     * routinely separated by newlines and indentation.
     */
    private static boolean isDeferred(String content, int callOffset) {
        int parenDepth = 0;
        for (int i = callOffset - 1; i >= 0; i--) {
            char c = content.charAt(i);
            if (c == ')') {
                parenDepth++;
            } else if (c == '(') {
                if (parenDepth > 0) {
                    parenDepth--;
                    continue;
                }
                // Unclosed '(' — this call encloses the re-fire. Its callee is
                // the identifier chain immediately to the left.
                String callee = calleeBefore(content, i);
                if (DEFERRAL_MARKERS.stream()
                        .anyMatch(marker -> callee.endsWith(marker.replace("(", "")))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Extracts the identifier chain (e.g. {@code window.setTimeout}) ending at {@code parenIndex}. */
    private static String calleeBefore(String content, int parenIndex) {
        int end = parenIndex;
        while (end > 0 && Character.isWhitespace(content.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0) {
            char c = content.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') {
                start--;
            } else {
                break;
            }
        }
        return content.substring(start, end);
    }

    private static String read(Path file) {
        assertThat(file)
                .as("expected script %s to exist", file)
                .isRegularFile();
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file.toAbsolutePath(), e);
        }
    }
}
