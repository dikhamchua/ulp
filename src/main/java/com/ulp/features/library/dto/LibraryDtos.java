package com.ulp.features.library.dto;

import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs for the lecturer personal file library (SSR page + JSON picker).
 */
public final class LibraryDtos {

    private LibraryDtos() {
    }

    /** One asset row on the SSR library page. */
    public record LibraryAssetRow(
            Long id,
            String title,
            String originalFilename,
            String kind,
            String mimeType,
            long sizeBytes,
            LocalDateTime updatedAt
    ) {
    }

    /**
     * Paginated SSR list view model. {@code page} is a Spring Data page so the
     * shared pager fragment can consume it directly. Kind counts power the
     * left sidebar badges (all / document / video) independent of the active
     * search filter so the folder rail stays stable while browsing.
     */
    public record LibraryPageView(
            Page<LibraryAssetRow> page,
            String q,
            String kind,
            long totalCount,
            long documentCount,
            long videoCount
    ) {
    }

    /** JSON item returned by the picker API. */
    public record LibraryPickerItem(
            Long id,
            String title,
            String originalFilename,
            String kind,
            String mimeType,
            long sizeBytes
    ) {
    }

    /** JSON page envelope for the picker modal. */
    public record LibraryPickerPage(
            List<LibraryPickerItem> items,
            int page,
            int size,
            int totalPages,
            long totalElements
    ) {
    }
}
