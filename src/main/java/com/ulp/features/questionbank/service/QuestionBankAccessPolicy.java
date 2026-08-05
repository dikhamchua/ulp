package com.ulp.features.questionbank.service;

import com.ulp.entities.Department;
import com.ulp.entities.User;
import com.ulp.features.head.service.HeadDepartmentResolver;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.security.Role;
import org.springframework.stereotype.Component;

/**
 * Resolves department-scoped access for question bank actions across the two
 * ownership scopes: the HEAD bank ({@code ownerId IS NULL}, department-owned)
 * and lecturer-private banks ({@code ownerId = user.id}).
 */
@Component
public class QuestionBankAccessPolicy {

    private final HeadDepartmentResolver headDepartmentResolver;

    public QuestionBankAccessPolicy(HeadDepartmentResolver headDepartmentResolver) {
        this.headDepartmentResolver = headDepartmentResolver;
    }

    /** Resolves the caller's working department for question bank access. */
    public Long resolveDepartmentId(User user) {
        if (user == null) {
            return null;
        }
        if (user.getRole() == Role.HEAD) {
            Department department = headDepartmentResolver.resolve(user.getId()).orElse(null);
            return department != null ? department.getId() : null;
        }
        if (user.getRole() == Role.LECTURER) {
            return user.getDepartmentId();
        }
        if (user.getRole() == Role.ADMIN) {
            return user.getDepartmentId();
        }
        return null;
    }

    /** True when the caller may view the HEAD-bank items of the given department. */
    public boolean canReadHeadBank(User user, Long departmentId) {
        if (user == null || departmentId == null) {
            return false;
        }
        Role role = user.getRole();
        if (role != Role.HEAD && role != Role.LECTURER && role != Role.ADMIN) {
            return false;
        }
        Long resolvedDepartmentId = resolveDepartmentId(user);
        return departmentId.equals(resolvedDepartmentId);
    }

    /** True when the caller may curate (create/edit/archive) the HEAD bank of the department. */
    public boolean canManageHeadBank(User user, Long departmentId) {
        if (user == null || departmentId == null) {
            return false;
        }
        Role role = user.getRole();
        if (role != Role.HEAD && role != Role.ADMIN) {
            return false;
        }
        Long resolvedDepartmentId = resolveDepartmentId(user);
        return departmentId.equals(resolvedDepartmentId);
    }

    /** True when the item is the caller's own private-bank item. */
    public boolean canAccessLecturerBank(QuestionBankItem item, User user) {
        return user != null
                && item != null
                && item.getOwnerId() != null
                && item.getOwnerId().equals(user.getId());
    }

    /**
     * Read dispatch across both scopes: private items are owner-only, HEAD-bank
     * items are readable by any HEAD/LECTURER/ADMIN of the owning department.
     */
    public boolean canReadItem(QuestionBankItem item, User user) {
        if (item.getOwnerId() != null) {
            return canAccessLecturerBank(item, user);
        }
        return canReadHeadBank(user, item.getDepartmentId());
    }

    /**
     * Mutate dispatch across both scopes: private items are owner-only, HEAD-bank
     * items are manageable by any HEAD/ADMIN of the owning department.
     */
    public boolean canManageItem(QuestionBankItem item, User user) {
        if (item.getOwnerId() != null) {
            return canAccessLecturerBank(item, user);
        }
        return canManageHeadBank(user, item.getDepartmentId());
    }
}
