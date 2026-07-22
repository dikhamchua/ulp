package com.ulp.features.library.service;

import com.ulp.entities.LibraryAsset;
import com.ulp.features.library.dto.LibraryDtos.LibraryAssetRow;
import com.ulp.features.library.dto.LibraryDtos.LibraryPageView;
import com.ulp.features.library.dto.LibraryDtos.LibraryPickerItem;
import com.ulp.features.library.dto.LibraryDtos.LibraryPickerPage;
import com.ulp.features.library.repository.LessonTemplateAttachmentRepository;
import com.ulp.features.library.repository.LessonTemplateRepository;
import com.ulp.features.library.repository.LibraryAssetRepository;
import com.ulp.features.upload.LibraryStorageService;
import com.ulp.features.upload.LibraryStorageService.StoredLibraryFile;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.ulp.common.IConstant.DEFAULT_LIBRARY_PAGE_SIZE;
import static com.ulp.common.IConstant.MAX_LIBRARY_PAGE_SIZE;
import static com.ulp.common.IConstant.MSG_LIBRARY_ASSET_IN_USE;
import static com.ulp.common.IConstant.MSG_LIBRARY_ASSET_NOT_FOUND;
import static com.ulp.common.IConstant.MSG_LIBRARY_TITLE_BLANK;
import static com.ulp.entities.LibraryAsset.KIND_DOCUMENT;
import static com.ulp.entities.LibraryAsset.KIND_VIDEO;

/**
 * Owner-scoped CRUD for the personal lecturer file library.
 */
@Service
public class LibraryService {

    private final LibraryAssetRepository assetRepository;
    private final LibraryStorageService storage;
    private final LessonTemplateRepository templateRepository;
    private final LessonTemplateAttachmentRepository templateAttachmentRepository;

    public LibraryService(LibraryAssetRepository assetRepository,
                          LibraryStorageService storage,
                          LessonTemplateRepository templateRepository,
                          LessonTemplateAttachmentRepository templateAttachmentRepository) {
        this.assetRepository = assetRepository;
        this.storage = storage;
        this.templateRepository = templateRepository;
        this.templateAttachmentRepository = templateAttachmentRepository;
    }

    /** SSR page list with search / kind filter / pagination. */
    @Transactional(readOnly = true)
    public LibraryPageView list(Long ownerId, String q, String kind, int page, int size) {
        PageRequest pr = pageRequest(page, size);
        String qNorm = normalizeQ(q);
        String kindNorm = normalizeKind(kind);
        Page<LibraryAsset> result = assetRepository.searchOwned(ownerId, qNorm, kindNorm, pr);
        Page<LibraryAssetRow> rowPage = result.map(LibraryService::toRow);
        // Sidebar badges ignore the current search query so switching kinds
        // does not make folder counts jump while the user is filtering by name.
        long totalCount = assetRepository.countByOwnerId(ownerId);
        long documentCount = assetRepository.countByOwnerIdAndKind(ownerId, KIND_DOCUMENT);
        long videoCount = assetRepository.countByOwnerIdAndKind(ownerId, KIND_VIDEO);
        return new LibraryPageView(
                rowPage,
                qNorm == null ? "" : qNorm,
                kindNorm == null ? "" : kindNorm,
                totalCount,
                documentCount,
                videoCount);
    }

    /** JSON picker page — same filters as SSR list. */
    @Transactional(readOnly = true)
    public LibraryPickerPage listForPicker(Long ownerId, String q, String kind, int page, int size) {
        PageRequest pr = pageRequest(page, size);
        Page<LibraryAsset> result = assetRepository.searchOwned(
                ownerId, normalizeQ(q), normalizeKind(kind), pr);
        List<LibraryPickerItem> items = new ArrayList<>(result.getNumberOfElements());
        for (LibraryAsset a : result.getContent()) {
            items.add(new LibraryPickerItem(
                    a.getId(), a.getTitle(), a.getOriginalFilename(),
                    a.getKind(), a.getMimeType(), a.getSizeBytes()));
        }
        return new LibraryPickerPage(
                items, result.getNumber(), result.getSize(),
                result.getTotalPages(), result.getTotalElements());
    }

    /**
     * Stores the multipart file and persists metadata for the owner.
     *
     * @param kind optional DOCUMENT/VIDEO hint; inferred from extension when blank
     */
    @Transactional
    public LibraryAssetRow upload(Long ownerId, MultipartFile file, String kind) throws IOException {
        StoredLibraryFile stored = storage.store(file, ownerId, kind);
        String title = stored.originalFilename();
        LibraryAsset asset = new LibraryAsset(
                ownerId, title, stored.originalFilename(), stored.storedPath(),
                stored.mimeType(), stored.sizeBytes(), stored.kind());
        return toRow(assetRepository.save(asset));
    }

    /** Updates the display title only for an owned asset. */
    @Transactional
    public LibraryAssetRow rename(Long ownerId, Long assetId, String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException(MSG_LIBRARY_TITLE_BLANK);
        }
        LibraryAsset asset = getOwnedAsset(ownerId, assetId);
        asset.rename(newTitle.trim());
        return toRow(assetRepository.save(asset));
    }

    /**
     * Soft-deletes an owned asset and removes the on-disk file when no live
     * lesson references remain.
     */
    @Transactional
    public void delete(Long ownerId, Long assetId) {
        LibraryAsset asset = getOwnedAsset(ownerId, assetId);
        long refs = countReferences(assetId);
        if (refs > 0) {
            throw new IllegalStateException(MSG_LIBRARY_ASSET_IN_USE);
        }
        // Soft-delete first so lists drop the row even if disk delete fails.
        asset.markDeleted();
        assetRepository.save(asset);
        storage.delete(asset.getStoredPath());
    }

    /**
     * Loads an owned, non-deleted asset or throws not-found (also for other
     * owners — no existence leak beyond 404 messaging).
     */
    @Transactional(readOnly = true)
    public LibraryAsset getOwnedAsset(Long ownerId, Long assetId) {
        return assetRepository.findByIdAndOwnerId(assetId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_LIBRARY_ASSET_NOT_FOUND));
    }

    /**
     * Attachment rows + non-deleted lesson video FKs + template body/attachment
     * FKs that still use the asset.
     */
    @Transactional(readOnly = true)
    public long countReferences(Long assetId) {
        return assetRepository.countAttachmentReferences(assetId)
                + assetRepository.countLessonVideoReferences(assetId)
                + templateRepository.countPdfAssetReferences(assetId)
                + templateRepository.countVideoAssetReferences(assetId)
                + templateAttachmentRepository.countByLibraryAssetId(assetId);
    }

    private static PageRequest pageRequest(int page, int size) {
        int p = Math.max(page, 0);
        int s = size <= 0 ? DEFAULT_LIBRARY_PAGE_SIZE
                : Math.min(size, MAX_LIBRARY_PAGE_SIZE);
        return PageRequest.of(p, s);
    }

    private static String normalizeQ(String q) {
        if (q == null) return null;
        String t = q.trim();
        return t.isEmpty() ? null : t;
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) return null;
        String upper = kind.trim().toUpperCase(Locale.ROOT);
        if (KIND_DOCUMENT.equals(upper) || KIND_VIDEO.equals(upper)) {
            return upper;
        }
        return null;
    }

    private static LibraryAssetRow toRow(LibraryAsset a) {
        return new LibraryAssetRow(
                a.getId(), a.getTitle(), a.getOriginalFilename(),
                a.getKind(), a.getMimeType(), a.getSizeBytes(), a.getUpdatedAt());
    }
}
