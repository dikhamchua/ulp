package com.ulp.features.questionbank.service;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** HEAD review actions for department-scoped shared workflow transitions. */
@Service
public class QuestionBankReviewService {

    private static final String MSG_FORBIDDEN =
            "Bạn không có quyền duyệt câu hỏi cộng tác của bộ môn này";
    private static final String MSG_INVALID_STATE =
            "Không thể thực hiện thao tác ở trạng thái hiện tại";

    private final UserRepository userRepository;
    private final QuestionBankAccessPolicy accessPolicy;
    private final QuestionBankItemRepository itemRepository;

    public QuestionBankReviewService(UserRepository userRepository,
                                     QuestionBankAccessPolicy accessPolicy,
                                     QuestionBankItemRepository itemRepository) {
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.itemRepository = itemRepository;
    }

    @Transactional
    public void approve(Long userId, Long itemId) {
        User actor = requireCurator(userId);
        QuestionBankItem item = requireCuratedItem(itemId, actor);
        if (!QuestionBankItem.STATUS_REVIEW.equals(item.getWorkflowStatus())) {
            throw new QuestionBankValidationException(MSG_INVALID_STATE);
        }
        LocalDateTime now = LocalDateTime.now();
        item.transitionWorkflow(QuestionBankItem.STATUS_APPROVED, actor.getId(), null, now, now);
        itemRepository.save(item);
    }

    @Transactional
    public void reject(Long userId, Long itemId, String note) {
        User actor = requireCurator(userId);
        QuestionBankItem item = requireCuratedItem(itemId, actor);
        if (!QuestionBankItem.STATUS_REVIEW.equals(item.getWorkflowStatus())) {
            throw new QuestionBankValidationException(MSG_INVALID_STATE);
        }
        item.transitionWorkflow(
                QuestionBankItem.STATUS_REJECTED,
                actor.getId(),
                blankToNull(note),
                LocalDateTime.now(),
                null);
        itemRepository.save(item);
    }

    @Transactional
    public void archive(Long userId, Long itemId, String note) {
        User actor = requireCurator(userId);
        QuestionBankItem item = requireCuratedItem(itemId, actor);
        if (QuestionBankItem.STATUS_ARCHIVED.equals(item.getWorkflowStatus())) {
            throw new QuestionBankValidationException(MSG_INVALID_STATE);
        }
        // Remembers the pre-archive status so unarchive can restore it exactly.
        item.archive(actor.getId(), blankToNull(note), LocalDateTime.now());
        itemRepository.save(item);
    }

    /**
     * Restores an archived item to the workflow status it held before archiving
     * (APPROVED→APPROVED, REVIEW→REVIEW, ...). Legacy rows with no remembered
     * status fall back to REVIEW. Stamps the restoring reviewer for audit.
     */
    @Transactional
    public void unarchive(Long userId, Long itemId) {
        User actor = requireCurator(userId);
        QuestionBankItem item = requireCuratedItem(itemId, actor);
        if (!QuestionBankItem.STATUS_ARCHIVED.equals(item.getWorkflowStatus())) {
            throw new QuestionBankValidationException(MSG_INVALID_STATE);
        }
        item.unarchive(actor.getId(), LocalDateTime.now());
        itemRepository.save(item);
    }

    /** Outcome of a bulk review action: how many transitioned vs were skipped. */
    public record BulkResult(int succeeded, int skipped) {
    }

    /**
     * Approves each item, skipping any that are in an invalid state, cross-department,
     * or missing. Not {@code @Transactional}: each single call runs in its own tx so
     * one failing item never rolls back items already committed (partial success).
     */
    public BulkResult approveAll(Long userId, List<Long> itemIds) {
        int ok = 0;
        int skip = 0;
        for (Long id : dedupe(itemIds)) {
            try {
                approve(userId, id);
                ok++;
            } catch (RuntimeException ex) {
                skip++;
            }
        }
        return new BulkResult(ok, skip);
    }

    /** Bulk reject; see {@link #approveAll} for the skip/partial-success contract. */
    public BulkResult rejectAll(Long userId, List<Long> itemIds, String note) {
        int ok = 0;
        int skip = 0;
        for (Long id : dedupe(itemIds)) {
            try {
                reject(userId, id, note);
                ok++;
            } catch (RuntimeException ex) {
                skip++;
            }
        }
        return new BulkResult(ok, skip);
    }

    /** Bulk archive; see {@link #approveAll} for the skip/partial-success contract. */
    public BulkResult archiveAll(Long userId, List<Long> itemIds, String note) {
        int ok = 0;
        int skip = 0;
        for (Long id : dedupe(itemIds)) {
            try {
                archive(userId, id, note);
                ok++;
            } catch (RuntimeException ex) {
                skip++;
            }
        }
        return new BulkResult(ok, skip);
    }

    /** Bulk unarchive; see {@link #approveAll} for the skip/partial-success contract. */
    public BulkResult unarchiveAll(Long userId, List<Long> itemIds) {
        int ok = 0;
        int skip = 0;
        for (Long id : dedupe(itemIds)) {
            try {
                unarchive(userId, id);
                ok++;
            } catch (RuntimeException ex) {
                skip++;
            }
        }
        return new BulkResult(ok, skip);
    }

    /** Drops nulls and duplicates while preserving submission order. */
    private static List<Long> dedupe(List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long id : itemIds) {
            if (id != null) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }

    private User requireCurator(Long userId) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException(MSG_FORBIDDEN));
        Long departmentId = accessPolicy.resolveDepartmentId(actor);
        if (departmentId == null || !accessPolicy.canCurateDepartment(actor, departmentId)) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        return actor;
    }

    private QuestionBankItem requireCuratedItem(Long itemId, User actor) {
        Long departmentId = accessPolicy.resolveDepartmentId(actor);
        return itemRepository.findByIdAndDepartmentId(itemId, departmentId)
                .filter(item -> accessPolicy.canCurateDepartment(actor, item.getDepartmentId()))
                .orElseThrow(() -> new AccessDeniedException(MSG_FORBIDDEN));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
