package com.ulp.features.questionbank.service;

import com.ulp.common.HtmlSanitizer;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.questionbank.dto.QuestionBankItemForm;
import com.ulp.features.questionbank.dto.QuestionBankViews.CategoryDetailView;
import com.ulp.features.questionbank.dto.QuestionBankViews.CategoryOption;
import com.ulp.features.questionbank.dto.QuestionBankViews.ContributorOption;
import com.ulp.features.questionbank.dto.QuestionBankViews.ItemDetail;
import com.ulp.features.questionbank.dto.QuestionBankViews.ItemRow;
import com.ulp.features.questionbank.dto.QuestionBankViews.OptionView;
import com.ulp.features.questionbank.dto.QuestionBankViews.StatusCounts;
import com.ulp.features.questionbank.entity.QuestionBankCategory;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.entity.QuestionBankOption;
import com.ulp.features.questionbank.repository.QuestionBankCategoryRepository;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import com.ulp.features.questionbank.repository.QuestionBankOptionRepository;
import com.ulp.security.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Lecturer and HEAD authoring flow for department-scoped shared questions. */
@Service
public class QuestionBankItemService {

    private static final String MSG_EMPTY_DEPARTMENT =
            "Bạn chưa được gán bộ môn để cộng tác soạn câu hỏi";
    private static final String MSG_NOT_FOUND = "Không tìm thấy câu hỏi cộng tác";
    private static final String MSG_FORBIDDEN = "Bạn không có quyền thao tác với câu hỏi cộng tác này";

    private final UserRepository userRepository;
    private final QuestionBankAccessPolicy accessPolicy;
    private final QuestionBankCategoryService categoryService;
    private final QuestionBankCategoryRepository categoryRepository;
    private final QuestionBankItemRepository itemRepository;
    private final QuestionBankOptionRepository optionRepository;

    public QuestionBankItemService(UserRepository userRepository,
                                   QuestionBankAccessPolicy accessPolicy,
                                   QuestionBankCategoryService categoryService,
                                   QuestionBankCategoryRepository categoryRepository,
                                   QuestionBankItemRepository itemRepository,
                                   QuestionBankOptionRepository optionRepository) {
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.categoryService = categoryService;
        this.categoryRepository = categoryRepository;
        this.itemRepository = itemRepository;
        this.optionRepository = optionRepository;
    }

    @Transactional(readOnly = true)
    public List<ItemRow> list(Long userId, Role role, String status, Long categoryId,
                              Long contributorId, String query) {
        User actor = requireActor(userId, role);
        Long departmentId = requireDepartment(actor);
        Map<Long, QuestionBankCategory> categories = categoriesById(departmentId);
        List<QuestionBankItem> items = itemRepository.findByDepartmentIdOrderByUpdatedAtDescIdDesc(departmentId);
        Map<Long, String> userNames = userNames(items);
        String normalizedQuery = normalizeQuery(query);
        return items.stream()
                .filter(item -> matchesStatus(item, status))
                .filter(item -> categoryId == null || categoryId.equals(item.getCategoryId()))
                .filter(item -> contributorId == null || contributorId.equals(item.getContributorId()))
                .filter(item -> matchesQuery(item, categories, userNames, normalizedQuery))
                .map(item -> new ItemRow(
                        item.getId(),
                        preview(item.getContent()),
                        item.getQuestionType(),
                        item.getWorkflowStatus(),
                        item.getCategoryId(),
                        categoryLabel(categories.get(item.getCategoryId())),
                        userNames.getOrDefault(item.getContributorId(), "—"),
                        item.getUpdatedAt(),
                        canEdit(actor, item),
                        canReview(actor, item)))
                .toList();
    }

    /**
     * Master→detail payload for one category: its header plus the questions it
     * contains, filtered by the given status/contributor/query. The status tallies
     * and contributor options are scoped to the category and computed ignoring the
     * status filter so the chips always show the full breakdown.
     */
    @Transactional(readOnly = true)
    public CategoryDetailView categoryDetail(Long userId, Role role, Long categoryId,
                                             String status, Long contributorId, String query) {
        User actor = requireActor(userId, role);
        // Ownership: throws NOT_FOUND when the category is outside the actor's department.
        QuestionBankCategory category = categoryService.requireVisibleCategory(categoryId, actor);
        // Full detail per row so the detail screen can render a client-side view modal.
        List<ItemDetail> items = detailedItems(actor, status, categoryId, contributorId, query);
        // Tallies ignore the status filter to keep every chip count visible.
        List<ItemRow> unfilteredByStatus = list(userId, role, null, categoryId, null, null);
        StatusCounts counts = tallyStatus(unfilteredByStatus);
        List<ContributorOption> contributors = scopedContributors(actor, categoryId, unfilteredByStatus);
        return new CategoryDetailView(category.getId(), category.getName(), category.getDescription(),
                category.isActive(), items, counts, contributors);
    }

    /**
     * Materializes filtered category questions as full {@link ItemDetail} rows,
     * batch-loading options and contributor names once to avoid per-row queries.
     */
    private List<ItemDetail> detailedItems(User actor, String status, Long categoryId,
                                           Long contributorId, String query) {
        Long departmentId = requireDepartment(actor);
        Map<Long, QuestionBankCategory> categories = categoriesById(departmentId);
        List<QuestionBankItem> all = itemRepository.findByDepartmentIdOrderByUpdatedAtDescIdDesc(departmentId);
        Map<Long, String> userNames = userNames(all);
        String normalizedQuery = normalizeQuery(query);
        List<QuestionBankItem> filtered = all.stream()
                .filter(item -> matchesStatus(item, status))
                .filter(item -> categoryId == null || categoryId.equals(item.getCategoryId()))
                .filter(item -> contributorId == null || contributorId.equals(item.getContributorId()))
                .filter(item -> matchesQuery(item, categories, userNames, normalizedQuery))
                .toList();
        Map<Long, List<OptionView>> optionsByItem = optionsByItemId(filtered);
        return filtered.stream()
                .map(item -> new ItemDetail(
                        item.getId(),
                        item.getQuestionType(),
                        item.getWorkflowStatus(),
                        item.getContent(),
                        item.getExplanation(),
                        item.getReviewNote(),
                        categoryLabel(categories.get(item.getCategoryId())),
                        userNames.getOrDefault(item.getContributorId(), "—"),
                        userNames.get(item.getReviewedBy()),
                        item.getReviewedAt(),
                        item.getApprovedAt(),
                        item.getUpdatedAt(),
                        optionsByItem.getOrDefault(item.getId(), List.of()),
                        canEdit(actor, item),
                        canReview(actor, item),
                        canArchive(actor, item),
                        canUnarchive(actor, item)))
                .toList();
    }

    /** Batch-loads options for the given items, grouped by item id (sort order preserved). */
    private Map<Long, List<OptionView>> optionsByItemId(List<QuestionBankItem> items) {
        if (items.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = items.stream().map(QuestionBankItem::getId).toList();
        Map<Long, List<OptionView>> byItem = new LinkedHashMap<>();
        for (QuestionBankOption option : optionRepository.findByItemIdInOrderBySortOrderAscIdAsc(ids)) {
            byItem.computeIfAbsent(option.getItemId(), id -> new ArrayList<>())
                    .add(new OptionView(option.getContent(), option.isCorrect()));
        }
        return byItem;
    }

    /** Status tallies over an already-materialized item list (scoped, in-memory). */
    private static StatusCounts tallyStatus(List<ItemRow> items) {
        long review = countRowStatus(items, QuestionBankItem.STATUS_REVIEW);
        long approved = countRowStatus(items, QuestionBankItem.STATUS_APPROVED);
        long rejected = countRowStatus(items, QuestionBankItem.STATUS_REJECTED);
        long archived = countRowStatus(items, QuestionBankItem.STATUS_ARCHIVED);
        return new StatusCounts(review, approved, rejected, archived, items.size());
    }

    private static long countRowStatus(List<ItemRow> items, String status) {
        return items.stream()
                .filter(item -> status.equalsIgnoreCase(item.workflowStatus()))
                .count();
    }

    /**
     * Contributors with real ids scoped to a single category: intersect the raw
     * category items with the department's user names so the filter option carries
     * a usable contributorId (ItemRow only exposes the display name).
     */
    private List<ContributorOption> scopedContributors(User actor, Long categoryId, List<ItemRow> categoryRows) {
        if (categoryRows.isEmpty()) {
            return List.of();
        }
        Long departmentId = requireDepartment(actor);
        List<QuestionBankItem> items = itemRepository.findByDepartmentIdOrderByUpdatedAtDescIdDesc(departmentId);
        Map<Long, String> userNames = userNames(items);
        // Preserve first-seen order (newest-first) while de-duplicating by contributor id.
        Map<Long, ContributorOption> distinct = new LinkedHashMap<>();
        for (QuestionBankItem item : items) {
            if (!categoryId.equals(item.getCategoryId())) {
                continue;
            }
            Long contributorId = item.getContributorId();
            distinct.computeIfAbsent(contributorId,
                    id -> new ContributorOption(id, userNames.getOrDefault(id, "—")));
        }
        return new ArrayList<>(distinct.values());
    }

    /** Workflow-status tallies for the department, feeding the HEAD stat header. */
    @Transactional(readOnly = true)
    public StatusCounts countByStatus(Long userId, Role role) {
        User actor = requireActor(userId, role);
        Long departmentId = requireDepartment(actor);
        List<QuestionBankItem> items = itemRepository.findByDepartmentIdOrderByUpdatedAtDescIdDesc(departmentId);
        long review = countStatus(items, QuestionBankItem.STATUS_REVIEW);
        long approved = countStatus(items, QuestionBankItem.STATUS_APPROVED);
        long rejected = countStatus(items, QuestionBankItem.STATUS_REJECTED);
        long archived = countStatus(items, QuestionBankItem.STATUS_ARCHIVED);
        return new StatusCounts(review, approved, rejected, archived, items.size());
    }

    private static long countStatus(List<QuestionBankItem> items, String status) {
        return items.stream()
                .filter(item -> status.equalsIgnoreCase(item.getWorkflowStatus()))
                .count();
    }

    @Transactional(readOnly = true)
    public List<CategoryOption> categoriesFor(Long userId, Role role) {
        return categoryService.activeOptionsFor(requireActor(userId, role));
    }

    /** Distinct contributors of the department's bank items, for the HEAD filter. */
    @Transactional(readOnly = true)
    public List<ContributorOption> contributorsFor(Long userId, Role role) {
        User actor = requireActor(userId, role);
        Long departmentId = requireDepartment(actor);
        List<QuestionBankItem> items = itemRepository.findByDepartmentIdOrderByUpdatedAtDescIdDesc(departmentId);
        Map<Long, String> userNames = userNames(items);
        // Preserve first-seen order (list is already newest-first) while de-duplicating.
        Map<Long, ContributorOption> distinct = new LinkedHashMap<>();
        for (QuestionBankItem item : items) {
            Long contributorId = item.getContributorId();
            distinct.computeIfAbsent(contributorId,
                    id -> new ContributorOption(id, userNames.getOrDefault(id, "—")));
        }
        return new ArrayList<>(distinct.values());
    }

    @Transactional(readOnly = true)
    public QuestionBankItemForm loadForm(Long userId, Role role, Long itemId) {
        User actor = requireActor(userId, role);
        QuestionBankItem item = requireVisibleItem(itemId, actor);
        if (!canEdit(actor, item)) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        QuestionBankItemForm form = new QuestionBankItemForm();
        form.setId(item.getId());
        form.setCategoryId(item.getCategoryId());
        form.setQuestionType(item.getQuestionType());
        form.setContent(item.getContent());
        form.setExplanation(item.getExplanation());
        List<QuestionBankItemForm.OptionField> optionFields = new ArrayList<>();
        for (QuestionBankOption option : optionRepository.findByItemIdInOrderBySortOrderAscIdAsc(List.of(item.getId()))) {
            QuestionBankItemForm.OptionField field = new QuestionBankItemForm.OptionField();
            field.setContent(option.getContent());
            field.setCorrect(option.isCorrect());
            optionFields.add(field);
        }
        form.setOptions(optionFields);
        form.ensureMinOptions(4);
        form.setWorkflowAction(QuestionBankItem.STATUS_DRAFT);
        return form;
    }

    @Transactional(readOnly = true)
    public ItemDetail detail(Long userId, Role role, Long itemId) {
        User actor = requireActor(userId, role);
        QuestionBankItem item = requireVisibleItem(itemId, actor);
        QuestionBankCategory category = categoryRepository.findById(item.getCategoryId()).orElse(null);
        Map<Long, String> userNames = loadNames(Stream.of(item.getContributorId(), item.getReviewedBy())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        List<OptionView> options = optionRepository.findByItemIdInOrderBySortOrderAscIdAsc(List.of(item.getId())).stream()
                .map(option -> new OptionView(option.getContent(), option.isCorrect()))
                .toList();
        return new ItemDetail(
                item.getId(),
                item.getQuestionType(),
                item.getWorkflowStatus(),
                item.getContent(),
                item.getExplanation(),
                item.getReviewNote(),
                categoryLabel(category),
                userNames.getOrDefault(item.getContributorId(), "—"),
                userNames.get(item.getReviewedBy()),
                item.getReviewedAt(),
                item.getApprovedAt(),
                item.getUpdatedAt(),
                options,
                canEdit(actor, item),
                canReview(actor, item),
                canArchive(actor, item),
                canUnarchive(actor, item));
    }

    @Transactional
    public Long save(Long userId, Role role, QuestionBankItemForm form) {
        User actor = requireActor(userId, role);
        Long departmentId = requireDepartment(actor);
        QuestionBankCategory category = categoryService.requireVisibleCategory(form.getCategoryId(), actor);
        List<QuestionBankOption> options = validatedOptions(form);
        String workflowStatus = resolveWorkflowAction(form.getWorkflowAction());

        QuestionBankItem item;
        if (form.getId() == null) {
            item = new QuestionBankItem(
                    departmentId,
                    category.getId(),
                    actor.getId(),
                    normalizedQuestionType(form.getQuestionType()),
                    workflowStatus,
                    sanitizeRequired(form.getContent(), "Nội dung câu hỏi không được để trống"),
                    sanitizeOptional(form.getExplanation()));
        } else {
            item = requireVisibleItem(form.getId(), actor);
            if (!canEdit(actor, item)) {
                throw new AccessDeniedException(MSG_FORBIDDEN);
            }
            item.updateAuthoring(
                    category.getId(),
                    normalizedQuestionType(form.getQuestionType()),
                    sanitizeRequired(form.getContent(), "Nội dung câu hỏi không được để trống"),
                    sanitizeOptional(form.getExplanation()));
            item.transitionWorkflow(workflowStatus, null, null, null, null);
        }
        QuestionBankItem saved = itemRepository.save(item);
        optionRepository.deleteByItemIdIn(List.of(saved.getId()));
        int order = 1;
        for (QuestionBankOption option : options) {
            optionRepository.save(new QuestionBankOption(
                    saved.getId(), option.getContent(), option.isCorrect(), order++));
        }
        return saved.getId();
    }

    @Transactional(readOnly = true)
    public boolean hasDepartment(Long userId, Role role) {
        User actor = requireActor(userId, role);
        return accessPolicy.resolveDepartmentId(actor) != null;
    }

    QuestionBankItem requireVisibleItem(Long itemId, User actor) {
        Long departmentId = requireDepartment(actor);
        if (!accessPolicy.canAccessDepartment(actor, departmentId)) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        return itemRepository.findByIdAndDepartmentId(itemId, departmentId)
                .orElseThrow(() -> new QuestionBankValidationException(MSG_NOT_FOUND));
    }

    boolean canReview(User actor, QuestionBankItem item) {
        return accessPolicy.canCurateDepartment(actor, item.getDepartmentId())
                && QuestionBankItem.STATUS_REVIEW.equals(item.getWorkflowStatus());
    }

    boolean canArchive(User actor, QuestionBankItem item) {
        return accessPolicy.canCurateDepartment(actor, item.getDepartmentId())
                && !QuestionBankItem.STATUS_ARCHIVED.equals(item.getWorkflowStatus());
    }

    boolean canUnarchive(User actor, QuestionBankItem item) {
        return accessPolicy.canCurateDepartment(actor, item.getDepartmentId())
                && QuestionBankItem.STATUS_ARCHIVED.equals(item.getWorkflowStatus());
    }

    private boolean canEdit(User actor, QuestionBankItem item) {
        if (!accessPolicy.canAccessDepartment(actor, item.getDepartmentId())) {
            return false;
        }
        if (QuestionBankItem.STATUS_ARCHIVED.equals(item.getWorkflowStatus())) {
            return false;
        }
        if (accessPolicy.canCurateDepartment(actor, item.getDepartmentId())) {
            return !QuestionBankItem.STATUS_APPROVED.equals(item.getWorkflowStatus());
        }
        if (!actor.getId().equals(item.getContributorId())) {
            return false;
        }
        return !QuestionBankItem.STATUS_APPROVED.equals(item.getWorkflowStatus());
    }

    private User requireActor(Long userId, Role role) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException(MSG_FORBIDDEN));
        if (actor.getRole() != role) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        return actor;
    }

    private Long requireDepartment(User actor) {
        Long departmentId = accessPolicy.resolveDepartmentId(actor);
        if (departmentId == null) {
            throw new QuestionBankValidationException(MSG_EMPTY_DEPARTMENT);
        }
        return departmentId;
    }

    private Map<Long, QuestionBankCategory> categoriesById(Long departmentId) {
        Map<Long, QuestionBankCategory> categories = new HashMap<>();
        for (QuestionBankCategory category : categoryRepository.findByDepartmentIdOrderByNameAsc(departmentId)) {
            categories.put(category.getId(), category);
        }
        return categories;
    }

    private Map<Long, String> userNames(List<QuestionBankItem> items) {
        Set<Long> ids = items.stream()
                .flatMap(item -> Stream.of(item.getContributorId(), item.getReviewedBy()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return loadNames(ids);
    }

    private Map<Long, String> loadNames(Set<Long> ids) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return names;
        }
        for (User user : userRepository.findAllById(ids)) {
            names.put(user.getId(), user.getFullName());
        }
        return names;
    }

    private List<QuestionBankOption> validatedOptions(QuestionBankItemForm form) {
        List<QuestionBankOption> options = new ArrayList<>();
        int correctCount = 0;
        int order = 1;
        for (QuestionBankItemForm.OptionField field : form.getOptions()) {
            String content = sanitizeOptional(field.getContent());
            if (content == null) {
                continue;
            }
            if (field.isCorrect()) {
                correctCount++;
            }
            options.add(new QuestionBankOption(null, content, field.isCorrect(), order++));
        }
        if (options.size() < 2) {
            throw new QuestionBankValidationException("Mỗi câu hỏi phải có ít nhất hai đáp án");
        }
        if (correctCount == 0) {
            throw new QuestionBankValidationException("Mỗi câu hỏi phải có ít nhất một đáp án đúng");
        }
        String type = normalizedQuestionType(form.getQuestionType());
        if (QuestionBankItem.TYPE_MCQ.equals(type) && correctCount != 1) {
            throw new QuestionBankValidationException("Câu hỏi MCQ phải có đúng một đáp án đúng");
        }
        return options;
    }

    private static boolean matchesStatus(QuestionBankItem item, String status) {
        return status == null || status.isBlank() || status.equalsIgnoreCase(item.getWorkflowStatus());
    }

    private static boolean matchesQuery(QuestionBankItem item,
                                        Map<Long, QuestionBankCategory> categories,
                                        Map<Long, String> userNames,
                                        String query) {
        if (query == null) {
            return true;
        }
        String category = categoryLabel(categories.get(item.getCategoryId())).toLowerCase();
        String contributor = userNames.getOrDefault(item.getContributorId(), "").toLowerCase();
        String content = preview(item.getContent()).toLowerCase();
        return category.contains(query) || contributor.contains(query) || content.contains(query);
    }

    private static String preview(String html) {
        String plain = html == null ? "" : html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return plain.length() > 120 ? plain.substring(0, 117) + "..." : plain;
    }

    private static String categoryLabel(QuestionBankCategory category) {
        return category == null ? "—" : category.getName();
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return query.trim().toLowerCase();
    }

    private static String resolveWorkflowAction(String action) {
        return QuestionBankItem.STATUS_REVIEW.equalsIgnoreCase(action)
                ? QuestionBankItem.STATUS_REVIEW
                : QuestionBankItem.STATUS_DRAFT;
    }

    private static String normalizedQuestionType(String value) {
        return QuestionBankItem.TYPE_MR.equalsIgnoreCase(value)
                ? QuestionBankItem.TYPE_MR
                : QuestionBankItem.TYPE_MCQ;
    }

    private static String sanitizeRequired(String value, String message) {
        String sanitized = sanitizeOptional(value);
        if (sanitized == null) {
            throw new QuestionBankValidationException(message);
        }
        return sanitized;
    }

    private static String sanitizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String sanitized = HtmlSanitizer.sanitize(value.trim()).trim();
        if (sanitized.isBlank()) {
            return null;
        }
        return sanitized;
    }
}
