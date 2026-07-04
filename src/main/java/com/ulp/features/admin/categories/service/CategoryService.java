package com.ulp.features.admin.categories.service;

import com.ulp.entities.Category;
import com.ulp.features.admin.categories.dto.CategoryDtos.CategoryForm;
import com.ulp.features.admin.categories.dto.CategoryDtos.CategoryRow;
import com.ulp.features.admin.categories.dto.CategoryDtos.ParentOption;
import com.ulp.features.admin.categories.repository.CategoryRepository;
import com.ulp.utils.Slugify;
import com.ulp.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Business logic for the {@code /admin/categories} screen: builds the
 * two-level tree, generates unique slugs, enforces the hard 2-level hierarchy,
 * and guards deletes (children + course links).
 *
 * <p>All hierarchy rules are validated server-side even though the UI already
 * restricts the parent dropdown — form data is untrusted. Delete guards raise
 * a {@link CategoryValidationException} the controller maps to an error toast.
 */
@Service
public class CategoryService {

    // Validation / guard messages (Vietnamese UI text — surfaced as toasts).
    static final String MSG_PARENT_NOT_TOP_LEVEL =
            "Danh mục cha phải là danh mục cấp 1";
    static final String MSG_PARENT_NOT_FOUND =
            "Không tìm thấy danh mục cha";
    static final String MSG_SELF_PARENT =
            "Danh mục không thể là cha của chính nó";
    static final String MSG_PARENT_HAS_CHILDREN =
            "Danh mục đang có danh mục con nên phải giữ ở cấp 1";
    static final String MSG_NOT_FOUND =
            "Không tìm thấy danh mục";
    static final String MSG_DELETE_HAS_CHILDREN =
            "Không thể xoá: hãy xoá các danh mục con trước";
    static final String MSG_DELETE_IN_USE =
            "Không thể xoá: danh mục đang được gán cho khoá học";

    private final CategoryRepository repository;

    public CategoryService(CategoryRepository repository) {
        this.repository = repository;
    }

    // ── Read side ──────────────────────────────────────────────────────

    /**
     * Builds the parent → children two-level tree. Parents are ordered by name;
     * each parent carries its children plus child/course counts.
     */
    @Transactional(readOnly = true)
    public List<CategoryRow> tree() {
        List<Category> parents = repository.findByParentIdIsNullOrderByNameAsc();
        List<CategoryRow> rows = new ArrayList<>(parents.size());
        for (Category parent : parents) {
            List<Category> kids = repository.findByParentIdOrderByNameAsc(parent.getId());
            List<CategoryRow> childRows = new ArrayList<>(kids.size());
            for (Category kid : kids) {
                childRows.add(toRow(kid, 0L, repository.countCourseLinks(kid.getId()), List.of()));
            }
            rows.add(toRow(parent, kids.size(),
                    repository.countCourseLinks(parent.getId()), childRows));
        }
        return rows;
    }

    /** Top-level categories as dropdown options for the parent selector. */
    @Transactional(readOnly = true)
    public List<ParentOption> parentOptions() {
        return repository.findByParentIdIsNullOrderByNameAsc().stream()
                .map(c -> new ParentOption(c.getId(), c.getName()))
                .toList();
    }

    /** Loads a single category as an edit-form DTO, or {@code null} if absent. */
    @Transactional(readOnly = true)
    public CategoryForm loadForm(Long id) {
        Category c = repository.findById(id).orElse(null);
        if (c == null) {
            return null;
        }
        return new CategoryForm(c.getName(),
                c.getDescription(), c.getParentId(), c.isActive());
    }

    /** True when the given category currently has children (drives edit UI). */
    @Transactional(readOnly = true)
    public boolean hasChildren(Long id) {
        return repository.existsByParentId(id);
    }

    // ── Write side ─────────────────────────────────────────────────────

    /**
     * Creates a category from the form. Auto-generates a unique slug and
     * validates the parent rule (parent must be an existing top-level category).
     *
     * @return the persisted category name, for the success toast
     */
    @Transactional
    public String create(CategoryForm form) {
        Long parentId = normalizeAndValidateParent(form.parentId(), null);
        String slug = uniqueSlug(Slugify.slugify(form.name()), null);
        Category entity = new Category(
                form.name().trim(),
                slug,
                parentId,
                StringUtils.blankToNull(form.description()),
                form.active());
        return repository.save(entity).getName();
    }

    /**
     * Updates a category from the form. Re-generates a unique slug (excluding
     * self) and enforces the two-level invariant against the target row.
     */
    @Transactional
    public void update(Long id, CategoryForm form) {
        Category entity = repository.findById(id)
                .orElseThrow(() -> new CategoryValidationException(MSG_NOT_FOUND));
        Long parentId = normalizeAndValidateParent(form.parentId(), id);
        String slug = uniqueSlug(Slugify.slugify(form.name()), id);
        entity.applyEdit(
                form.name().trim(),
                slug,
                parentId,
                StringUtils.blankToNull(form.description()),
                form.active());
        repository.save(entity);
    }

    /**
     * Hard-deletes a category after both guards pass: it must have no children
     * and no course links. Otherwise a {@link CategoryValidationException} is
     * raised for the controller to surface as an error toast.
     */
    @Transactional
    public void delete(Long id) {
        Category entity = repository.findById(id)
                .orElseThrow(() -> new CategoryValidationException(MSG_NOT_FOUND));
        if (repository.existsByParentId(id)) {
            throw new CategoryValidationException(MSG_DELETE_HAS_CHILDREN);
        }
        if (repository.countCourseLinks(id) > 0) {
            throw new CategoryValidationException(MSG_DELETE_IN_USE);
        }
        repository.delete(entity);
    }

    /** Flips the active flag and returns the new state for a confirmation toast. */
    @Transactional
    public boolean toggleActive(Long id) {
        Category entity = repository.findById(id)
                .orElseThrow(() -> new CategoryValidationException(MSG_NOT_FOUND));
        boolean now = entity.toggleActive();
        repository.save(entity);
        return now;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Validates the chosen parent against the two-level rules and returns the
     * effective parent id ({@code null} for a top-level category).
     *
     * <ul>
     *   <li>No parent → top-level, always allowed.</li>
     *   <li>A category may not be its own parent (self-loop).</li>
     *   <li>The chosen parent must exist and be top-level (no depth 3).</li>
     *   <li>A category that already has children cannot be re-parented (that
     *       would push its children to depth 3).</li>
     * </ul>
     *
     * @param parentId requested parent id (nullable)
     * @param selfId   id of the row being edited, or {@code null} on create
     */
    private Long normalizeAndValidateParent(Long parentId, Long selfId) {
        if (parentId == null) {
            return null;
        }
        if (selfId != null && parentId.equals(selfId)) {
            throw new CategoryValidationException(MSG_SELF_PARENT);
        }
        Category parent = repository.findById(parentId)
                .orElseThrow(() -> new CategoryValidationException(MSG_PARENT_NOT_FOUND));
        if (!parent.isTopLevel()) {
            throw new CategoryValidationException(MSG_PARENT_NOT_TOP_LEVEL);
        }
        // Editing a category that has children: it must stay top-level.
        if (selfId != null && repository.existsByParentId(selfId)) {
            throw new CategoryValidationException(MSG_PARENT_HAS_CHILDREN);
        }
        return parentId;
    }

    /**
     * Returns {@code base} if free, otherwise appends the smallest available
     * numeric suffix ({@code -2}, {@code -3}…). {@code excludeId} lets an edit
     * keep its own slug without colliding with itself.
     */
    private String uniqueSlug(String base, Long excludeId) {
        if (isSlugFree(base, excludeId)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = base + "-" + suffix;
            if (isSlugFree(candidate, excludeId)) {
                return candidate;
            }
        }
    }

    private boolean isSlugFree(String slug, Long excludeId) {
        return excludeId == null
                ? !repository.existsBySlug(slug)
                : !repository.existsBySlugAndIdNot(slug, excludeId);
    }

    private static CategoryRow toRow(Category c, long childCount,
                                     long courseCount, List<CategoryRow> children) {
        return new CategoryRow(
                c.getId(), c.getName(), c.getSlug(), c.getDescription(),
                c.isActive(), c.getParentId(), childCount, courseCount, children);
    }
}
