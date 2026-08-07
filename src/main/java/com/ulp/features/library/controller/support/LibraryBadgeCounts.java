package com.ulp.features.library.controller.support;

import com.ulp.features.tests.service.LecturerExamQueryService;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import static com.ulp.common.IConstant.ATTR_LIBRARY_DOCUMENT_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_EXAM_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_TEMPLATE_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_TOTAL_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_VIDEO_COUNT;

/**
 * Populates the Library kind rail's five badge counts on the shared
 * {@code templates/library/index.html} shell.
 *
 * <p>The rail is rendered by every Library controller, but the template reads
 * one model attribute per badge and falls back to {@code 0} when an attribute
 * is missing. Whichever controller serves the request therefore owns *all five*
 * badges: a controller that sets only its own leaves the rest rendering {@code 0},
 * which reads to the lecturer as "you have no documents" — a data-integrity lie,
 * not a cosmetic bug.
 *
 * <p>Centralising the write here means adding a sixth kind is one edit rather
 * than one per controller, which is exactly how the exam badge came to be
 * missing from four of the five rails.
 */
@Component
public class LibraryBadgeCounts {

    private final LecturerExamQueryService examQueryService;

    public LibraryBadgeCounts(LecturerExamQueryService examQueryService) {
        this.examQueryService = examQueryService;
    }

    /**
     * Writes all five badge attributes, sourcing the exam count from the
     * owner-scoped {@link LecturerExamQueryService} the rail itself lists
     * through — never from a hand-written query, which is where the ownership
     * predicate would drift.
     *
     * @param model  the view model of the Library shell being rendered
     * @param userId the lecturer whose library is on screen
     */
    public void populate(Model model, Long userId,
                         long totalCount, long documentCount,
                         long videoCount, long templateCount) {
        model.addAttribute(ATTR_LIBRARY_TOTAL_COUNT, totalCount);
        model.addAttribute(ATTR_LIBRARY_DOCUMENT_COUNT, documentCount);
        model.addAttribute(ATTR_LIBRARY_VIDEO_COUNT, videoCount);
        model.addAttribute(ATTR_LIBRARY_TEMPLATE_COUNT, templateCount);
        model.addAttribute(ATTR_LIBRARY_EXAM_COUNT, examQueryService.countOwned(userId));
    }
}
