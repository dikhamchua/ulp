package com.ulp.features.questionbank.service;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Archive / unarchive for both bank scopes: a HEAD (or ADMIN with a department)
 * may archive HEAD-bank items, and an owner may archive their own private-bank
 * items. The dispatch happens on {@code item.ownerId}.
 */
@Service
public class QuestionBankReviewService {

    private static final String MSG_FORBIDDEN =
            "Bạn không có quyền thao tác với câu hỏi này";
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

    /** Archives an ACTIVE item the actor may manage (own private or HEAD bank). */
    @Transactional
    public void archive(Long userId, Long itemId) {
        User actor = requireActor(userId);
        QuestionBankItem item = requireManageableItem(itemId, actor);
        if (QuestionBankItem.STATUS_ARCHIVED.equals(item.getStatus())) {
            throw new QuestionBankValidationException(MSG_INVALID_STATE);
        }
        // Remembers the pre-archive status so unarchive can restore it exactly.
        item.archive();
        itemRepository.save(item);
    }

    /** Restores an ARCHIVED item the actor may manage to its prior ACTIVE status. */
    @Transactional
    public void unarchive(Long userId, Long itemId) {
        User actor = requireActor(userId);
        QuestionBankItem item = requireManageableItem(itemId, actor);
        if (!QuestionBankItem.STATUS_ARCHIVED.equals(item.getStatus())) {
            throw new QuestionBankValidationException(MSG_INVALID_STATE);
        }
        item.unarchive();
        itemRepository.save(item);
    }

    /** Outcome of a bulk archive action: how many transitioned vs were skipped. */
    public record BulkResult(int succeeded, int skipped) {
    }

    /**
     * Archives each item, skipping any that are unmanageable, already archived,
     * or missing. Not {@code @Transactional}: each single call runs in its own tx
     * so one failing item never rolls back items already committed (partial success).
     */
    public BulkResult archiveAll(Long userId, List<Long> itemIds) {
        int ok = 0;
        int skip = 0;
        for (Long id : dedupe(itemIds)) {
            try {
                archive(userId, id);
                ok++;
            } catch (RuntimeException ex) {
                skip++;
            }
        }
        return new BulkResult(ok, skip);
    }

    /** Bulk unarchive; see {@link #archiveAll} for the skip/partial-success contract. */
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

    private User requireActor(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException(MSG_FORBIDDEN));
    }

    private QuestionBankItem requireManageableItem(Long itemId, User actor) {
        QuestionBankItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new AccessDeniedException(MSG_FORBIDDEN));
        if (!accessPolicy.canManageItem(item, actor)) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        return item;
    }
}
