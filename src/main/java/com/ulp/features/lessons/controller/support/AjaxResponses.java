package com.ulp.features.lessons.controller.support;

import com.ulp.features.lessons.dto.SectionDtos.AjaxResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static com.ulp.common.IConstant.MSG_FORBIDDEN_FOR_CLASS;
import static com.ulp.common.IConstant.MSG_GENERIC_RETRY;

/**
 * Shared JSON envelope builders for AJAX endpoints in the lessons feature.
 *
 * <p>Both {@code SectionsController} and {@code LessonsController} return the
 * same {@link AjaxResult} shape with identical status codes for the four
 * common failure modes (validation, authz, missing row, unexpected). Extracting
 * the builders here keeps the controllers focused on their happy paths.
 */
public final class AjaxResponses {

    private AjaxResponses() {
        // utility holder
    }

    /** 400 with a domain-supplied message (typically from IllegalArgumentException). */
    public static ResponseEntity<AjaxResult> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(AjaxResult.failure(message));
    }

    /** 403 with the shared "not your class" message. */
    public static ResponseEntity<AjaxResult> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(AjaxResult.failure(MSG_FORBIDDEN_FOR_CLASS));
    }

    /** 404 with a domain-supplied message (typically the entity-not-found text). */
    public static ResponseEntity<AjaxResult> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(AjaxResult.failure(message));
    }

    /** 500 with the generic "please retry" message; the caller logs the cause. */
    public static ResponseEntity<AjaxResult> internalError() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AjaxResult.failure(MSG_GENERIC_RETRY));
    }
}
