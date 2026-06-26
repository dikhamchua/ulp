package com.ulp.features.lessons.controller.support;

import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.ATTR_FLASH_ERROR;

/**
 * Common mutation-failure handler for the lessons-feature controllers.
 *
 * <p>Both {@code SectionsController} and {@code LessonsController} react to
 * the same three exception classes on the mutation path:
 * <ul>
 *   <li>{@link AccessDeniedException} bubbles to {@code GlobalExceptionHandler}
 *       so the user sees a 403 page.</li>
 *   <li>{@link EntityNotFoundException} surfaces its message as a flash error
 *       and redirects back to the originating list page.</li>
 *   <li>Anything else is logged as an internal failure and shown as a generic
 *       retry message.</li>
 * </ul>
 */
public final class MutationFailureHandler {

    private MutationFailureHandler() {
        // utility holder
    }

    /**
     * Maps a mutation-time exception onto a redirect string with a flash
     * error attached. {@link AccessDeniedException} is rethrown so the
     * global handler can render the standard 403 page.
     *
     * @param ex             the thrown exception
     * @param redirectTarget the redirect URL without the {@code "redirect:"} prefix
     * @param ra             redirect attributes for the flash message
     * @param userMessage    fallback user-facing message for unexpected failures
     * @param logger         logger to use for the unexpected-failure case
     * @param logTemplate    SLF4J template (e.g. {@code "Failed to do X in class {}"})
     * @param logArgs        arguments for the template; the throwable is appended automatically
     * @return the redirect string (e.g. {@code "redirect:/lecturer/classes/123/lessons"})
     */
    public static String handle(RuntimeException ex,
                                 String redirectTarget,
                                 RedirectAttributes ra,
                                 String userMessage,
                                 Logger logger,
                                 String logTemplate,
                                 Object... logArgs) {
        if (ex instanceof AccessDeniedException) {
            throw ex; // bubble to GlobalExceptionHandler → 403
        }
        if (ex instanceof EntityNotFoundException notFound) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, notFound.getMessage());
            return "redirect:" + redirectTarget;
        }
        logger.error(logTemplate, appendThrowable(logArgs, ex));
        ra.addFlashAttribute(ATTR_FLASH_ERROR, userMessage);
        return "redirect:" + redirectTarget;
    }

    /** Appends the throwable as the last element so SLF4J prints its stack trace. */
    private static Object[] appendThrowable(Object[] args, Throwable t) {
        Object[] merged = new Object[args.length + 1];
        System.arraycopy(args, 0, merged, 0, args.length);
        merged[args.length] = t;
        return merged;
    }
}
