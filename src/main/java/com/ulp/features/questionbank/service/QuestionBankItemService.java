package com.ulp.features.questionbank.service;

import com.ulp.common.HtmlSanitizer;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.questionbank.dto.QuestionBankItemForm;
import com.ulp.features.questionbank.dto.QuestionBankViews.ChapterOption;
import com.ulp.features.questionbank.dto.QuestionBankViews.ItemDetail;
import com.ulp.features.questionbank.dto.QuestionBankViews.ItemRow;
import com.ulp.features.questionbank.dto.QuestionBankViews.OptionView;
import com.ulp.features.questionbank.dto.QuestionBankViews.SubjectOption;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.entity.QuestionBankOption;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import com.ulp.features.questionbank.repository.QuestionBankOptionRepository;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.entity.SubjectChapter;
import com.ulp.features.subjects.repository.SubjectChapterRepository;
import com.ulp.features.subjects.repository.SubjectRepository;
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

/**
 * Authoring flow for the subject → chapter organised question bank across both
 * ownership scopes: lecturer-private items ({@code ownerId = actor.id}) and
 * HEAD-bank items ({@code ownerId = null}, department-owned). Items are created
 * ACTIVE; only ARCHIVED hides them.
 */
@Service
public class QuestionBankItemService {

    private static final String MSG_EMPTY_DEPARTMENT =
            "Bạn chưa được gán bộ môn để soạn câu hỏi";
    private static final String MSG_NOT_FOUND = "Không tìm thấy câu hỏi";
    private static final String MSG_FORBIDDEN = "Bạn không có quyền thao tác với câu hỏi này";
    private static final String MSG_SUBJECT_REQUIRED = "Vui lòng chọn môn học";
    private static final String MSG_SUBJECT_INVALID = "Môn học không tồn tại hoặc không thuộc bộ môn của bạn";
    private static final String MSG_CHAPTER_INVALID = "Chương không thuộc môn học đã chọn";

    private final UserRepository userRepository;
    private final QuestionBankAccessPolicy accessPolicy;
    private final QuestionBankItemRepository itemRepository;
    private final QuestionBankOptionRepository optionRepository;
    private final SubjectRepository subjectRepository;
    private final SubjectChapterRepository chapterRepository;

    public QuestionBankItemService(UserRepository userRepository,
                                   QuestionBankAccessPolicy accessPolicy,
                                   QuestionBankItemRepository itemRepository,
                                   QuestionBankOptionRepository optionRepository,
                                   SubjectRepository subjectRepository,
                                   SubjectChapterRepository chapterRepository) {
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.itemRepository = itemRepository;
        this.optionRepository = optionRepository;
        this.subjectRepository = subjectRepository;
        this.chapterRepository = chapterRepository;
    }

    /** The caller's own private-bank items, filtered by subject/chapter/query. */
    @Transactional(readOnly = true)
    public List<ItemRow> list(Long userId, Role role, Long subjectId, Long chapterId, String query) {
        User actor = requireActor(userId, role);
        List<QuestionBankItem> items = itemRepository.findByOwnerIdOrderByUpdatedAtDescIdDesc(actor.getId());
        return toRows(actor, items, subjectId, chapterId, query);
    }

    /** HEAD-bank items of the caller's department, filtered by subject/chapter/query. */
    @Transactional(readOnly = true)
    public List<ItemRow> listHead(Long userId, Role role, Long subjectId, Long chapterId, String query) {
        User actor = requireActor(userId, role);
        Long departmentId = requireHeadDepartment(actor);
        List<QuestionBankItem> items = itemRepository
                .findByOwnerIdIsNullAndDepartmentIdOrderByUpdatedAtDescIdDesc(departmentId);
        return toRows(actor, items, subjectId, chapterId, query);
    }

    /** Department subjects with in-scope item counts for the subject selector. */
    @Transactional(readOnly = true)
    public List<SubjectOption> subjectsFor(Long userId, Role role) {
        User actor = requireActor(userId, role);
        Long departmentId = accessPolicy.resolveDepartmentId(actor);
        if (departmentId == null) {
            return List.of();
        }
        List<Subject> subjects = subjectRepository.findAllByDepartmentIdOrderByCodeAsc(departmentId);
        List<SubjectOption> options = new ArrayList<>(subjects.size());
        for (Subject s : subjects) {
            long count = role == Role.LECTURER
                    ? itemRepository.countByOwnerIdAndSubjectId(actor.getId(), s.getId())
                    : itemRepository.countByOwnerIdIsNullAndDepartmentIdAndSubjectId(departmentId, s.getId());
            options.add(new SubjectOption(s.getId(), s.getCode(), s.getTitle(),
                    subjectLabel(s), count, s.isActive()));
        }
        return options;
    }

    /** Chapters of a subject with in-scope item counts (HEAD manage chapter chips). */
    @Transactional(readOnly = true)
    public List<ChapterOption> chaptersFor(Long userId, Role role, Long subjectId) {
        User actor = requireActor(userId, role);
        Long departmentId = accessPolicy.resolveDepartmentId(actor);
        if (departmentId == null || subjectId == null) {
            return List.of();
        }
        List<QuestionBankItem> subjectItems = role == Role.LECTURER
                ? itemRepository.findByOwnerIdAndSubjectIdOrderByUpdatedAtDescIdDesc(actor.getId(), subjectId)
                : itemRepository.findByOwnerIdIsNullAndDepartmentIdAndSubjectIdOrderByUpdatedAtDescIdDesc(
                        departmentId, subjectId);
        Map<Long, Long> counts = new HashMap<>();
        for (QuestionBankItem item : subjectItems) {
            counts.merge(item.getChapterId(), 1L, Long::sum);
        }
        List<SubjectChapter> chapters = chapterRepository.findBySubjectIdOrderByDisplayOrderAsc(subjectId);
        List<ChapterOption> options = new ArrayList<>(chapters.size());
        for (SubjectChapter c : chapters) {
            options.add(new ChapterOption(c.getId(), c.getTitle(), counts.getOrDefault(c.getId(), 0L)));
        }
        return options;
    }

    /**
     * All chapters of every department subject, keyed by subject id. Used by the
     * authoring form's dependent subject → chapter dropdown (embedded as JSON).
     */
    @Transactional(readOnly = true)
    public Map<Long, List<ChapterOption>> chaptersBySubjectFor(Long userId, Role role) {
        User actor = requireActor(userId, role);
        Long departmentId = accessPolicy.resolveDepartmentId(actor);
        if (departmentId == null) {
            return Map.of();
        }
        List<Subject> subjects = subjectRepository.findAllByDepartmentIdOrderByCodeAsc(departmentId);
        Map<Long, List<ChapterOption>> map = new LinkedHashMap<>();
        for (Subject s : subjects) {
            List<ChapterOption> chapters = chapterRepository.findBySubjectIdOrderByDisplayOrderAsc(s.getId())
                    .stream()
                    .map(c -> new ChapterOption(c.getId(), c.getTitle(), 0L))
                    .toList();
            map.put(s.getId(), chapters);
        }
        return map;
    }

    /** Full item payload for the detail screen (both scopes, dispatch on ownerId). */
    @Transactional(readOnly = true)
    public ItemDetail detail(Long userId, Role role, Long itemId) {
        User actor = requireActor(userId, role);
        QuestionBankItem item = requireVisibleItem(itemId, actor);
        Subject subject = subjectRepository.findById(item.getSubjectId()).orElse(null);
        SubjectChapter chapter = item.getChapterId() == null
                ? null
                : chapterRepository.findById(item.getChapterId()).orElse(null);
        String contributorName = userRepository.findById(item.getContributorId())
                .map(User::getFullName)
                .orElse("—");
        List<OptionView> options = optionRepository.findByItemIdInOrderBySortOrderAscIdAsc(List.of(item.getId()))
                .stream()
                .map(option -> new OptionView(option.getContent(), option.isCorrect()))
                .toList();
        return new ItemDetail(
                item.getId(),
                item.getQuestionType(),
                item.getStatus(),
                item.getContent(),
                item.getExplanation(),
                item.getSubjectId(),
                subjectLabel(subject),
                item.getChapterId(),
                chapterLabel(chapter),
                contributorName,
                item.getUpdatedAt(),
                options,
                canEdit(actor, item),
                canArchive(actor, item),
                canUnarchive(actor, item));
    }

    /** Loads an item into the authoring form (owner-only edits enforced). */
    @Transactional(readOnly = true)
    public QuestionBankItemForm loadForm(Long userId, Role role, Long itemId) {
        User actor = requireActor(userId, role);
        QuestionBankItem item = requireVisibleItem(itemId, actor);
        if (!accessPolicy.canManageItem(item, actor)) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        QuestionBankItemForm form = new QuestionBankItemForm();
        form.setId(item.getId());
        form.setSubjectId(item.getSubjectId());
        form.setChapterId(item.getChapterId());
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
        return form;
    }

    /**
     * Creates or updates an item. The target bank follows the actor's role:
     * LECTURER → own private bank (ownerId = actor.id), HEAD/ADMIN → HEAD bank
     * (ownerId = null). New items are created ACTIVE. The chapter must belong to
     * the item's subject.
     */
    @Transactional
    public Long save(Long userId, Role role, QuestionBankItemForm form) {
        User actor = requireActor(userId, role);
        Long departmentId = requireDepartment(actor);
        Subject subject = requireSubject(departmentId, form.getSubjectId());
        SubjectChapter chapter = requireChapter(subject.getId(), form.getChapterId());
        List<QuestionBankOption> options = validatedOptions(form);
        Long ownerId = role == Role.LECTURER ? actor.getId() : null;
        Long chapterId = chapter == null ? null : chapter.getId();

        QuestionBankItem item;
        if (form.getId() == null) {
            item = new QuestionBankItem(
                    departmentId,
                    subject.getId(),
                    ownerId,
                    chapterId,
                    actor.getId(),
                    deriveQuestionType(options),
                    QuestionBankItem.STATUS_ACTIVE,
                    sanitizeRequired(form.getContent(), "Nội dung câu hỏi không được để trống"),
                    sanitizeOptional(form.getExplanation()));
        } else {
            item = requireVisibleItem(form.getId(), actor);
            if (!accessPolicy.canManageItem(item, actor)) {
                throw new AccessDeniedException(MSG_FORBIDDEN);
            }
            // An archived item is hidden and no longer editable; mirror canEdit().
            if (QuestionBankItem.STATUS_ARCHIVED.equals(item.getStatus())) {
                throw new QuestionBankValidationException(MSG_FORBIDDEN);
            }
            item.updateAuthoring(
                    subject.getId(),
                    chapterId,
                    deriveQuestionType(options),
                    sanitizeRequired(form.getContent(), "Nội dung câu hỏi không được để trống"),
                    sanitizeOptional(form.getExplanation()));
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

    /** Loads an item the actor may read, dispatching on ownerId (private vs HEAD). */
    QuestionBankItem requireVisibleItem(Long itemId, User actor) {
        QuestionBankItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new QuestionBankValidationException(MSG_NOT_FOUND));
        if (!accessPolicy.canReadItem(item, actor)) {
            throw new QuestionBankValidationException(MSG_FORBIDDEN);
        }
        return item;
    }

    boolean canArchive(User actor, QuestionBankItem item) {
        return accessPolicy.canManageItem(item, actor)
                && !QuestionBankItem.STATUS_ARCHIVED.equals(item.getStatus());
    }

    boolean canUnarchive(User actor, QuestionBankItem item) {
        return accessPolicy.canManageItem(item, actor)
                && QuestionBankItem.STATUS_ARCHIVED.equals(item.getStatus());
    }

    private boolean canEdit(User actor, QuestionBankItem item) {
        return accessPolicy.canManageItem(item, actor)
                && !QuestionBankItem.STATUS_ARCHIVED.equals(item.getStatus());
    }

    private List<ItemRow> toRows(User actor, List<QuestionBankItem> items,
                                 Long subjectId, Long chapterId, String query) {
        Map<Long, Subject> subjects = subjectsById(items);
        Map<Long, SubjectChapter> chapters = chaptersById(items);
        String normalizedQuery = normalizeQuery(query);
        return items.stream()
                .filter(item -> subjectId == null || subjectId.equals(item.getSubjectId()))
                .filter(item -> chapterId == null || chapterId.equals(item.getChapterId()))
                .filter(item -> matchesQuery(item, subjects, chapters, normalizedQuery))
                .map(item -> new ItemRow(
                        item.getId(),
                        preview(item.getContent()),
                        item.getQuestionType(),
                        item.getStatus(),
                        item.getSubjectId(),
                        subjectLabel(subjects.get(item.getSubjectId())),
                        item.getChapterId(),
                        chapterLabel(chapters.get(item.getChapterId())),
                        item.getUpdatedAt(),
                        canEdit(actor, item),
                        canArchive(actor, item),
                        canUnarchive(actor, item)))
                .toList();
    }

    private static boolean matchesQuery(QuestionBankItem item,
                                        Map<Long, Subject> subjects,
                                        Map<Long, SubjectChapter> chapters,
                                        String query) {
        if (query == null) {
            return true;
        }
        String subject = subjectLabel(subjects.get(item.getSubjectId())).toLowerCase();
        String chapter = chapterLabel(chapters.get(item.getChapterId())).toLowerCase();
        String content = preview(item.getContent()).toLowerCase();
        return subject.contains(query) || chapter.contains(query) || content.contains(query);
    }

    private Map<Long, Subject> subjectsById(List<QuestionBankItem> items) {
        Map<Long, Subject> map = new HashMap<>();
        for (QuestionBankItem item : items) {
            if (item.getSubjectId() != null && !map.containsKey(item.getSubjectId())) {
                subjectRepository.findById(item.getSubjectId()).ifPresent(s -> map.put(s.getId(), s));
            }
        }
        return map;
    }

    private Map<Long, SubjectChapter> chaptersById(List<QuestionBankItem> items) {
        Map<Long, SubjectChapter> map = new HashMap<>();
        for (QuestionBankItem item : items) {
            if (item.getChapterId() != null && !map.containsKey(item.getChapterId())) {
                chapterRepository.findById(item.getChapterId()).ifPresent(c -> map.put(c.getId(), c));
            }
        }
        return map;
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
        return options;
    }

    /**
     * Derives the question type from the number of correct options: exactly one
     * correct answer means MCQ, two or more means MR. The authoring form no longer
     * asks the author to pick a type — the ticked answers decide it.
     */
    private static String deriveQuestionType(List<QuestionBankOption> options) {
        long correctCount = options.stream().filter(QuestionBankOption::isCorrect).count();
        return correctCount == 1 ? QuestionBankItem.TYPE_MCQ : QuestionBankItem.TYPE_MR;
    }

    private Subject requireSubject(Long departmentId, Long subjectId) {
        if (subjectId == null) {
            throw new QuestionBankValidationException(MSG_SUBJECT_REQUIRED);
        }
        return subjectRepository.findById(subjectId)
                .filter(Subject::isActive)
                .filter(s -> departmentId.equals(s.getDepartmentId()))
                .orElseThrow(() -> new QuestionBankValidationException(MSG_SUBJECT_INVALID));
    }

    private SubjectChapter requireChapter(Long subjectId, Long chapterId) {
        if (chapterId == null) {
            return null;
        }
        return chapterRepository.findByIdAndSubjectId(chapterId, subjectId)
                .orElseThrow(() -> new QuestionBankValidationException(MSG_CHAPTER_INVALID));
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

    private Long requireHeadDepartment(User actor) {
        Long departmentId = requireDepartment(actor);
        if (!accessPolicy.canManageHeadBank(actor, departmentId)) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        return departmentId;
    }

    private static String preview(String html) {
        String plain = html == null ? "" : html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return plain.length() > 120 ? plain.substring(0, 117) + "..." : plain;
    }

    private static String subjectLabel(Subject subject) {
        return subject == null ? "—" : subject.getCode() + " — " + subject.getTitle();
    }

    private static String chapterLabel(SubjectChapter chapter) {
        return chapter == null ? "—" : chapter.getTitle();
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return query.trim().toLowerCase();
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
