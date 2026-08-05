package com.ulp.features.tests.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.entity.QuestionBankOption;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import com.ulp.features.questionbank.repository.QuestionBankOptionRepository;
import com.ulp.features.questionbank.service.QuestionBankAccessPolicy;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.repository.SubjectChapterRepository;
import com.ulp.features.subjects.repository.SubjectRepository;
import com.ulp.features.tests.dto.LecturerTestDtos.BankChapterOption;
import com.ulp.features.tests.dto.LecturerTestDtos.BankItemSnapshot;
import com.ulp.features.tests.dto.LecturerTestDtos.BankOptionSnapshot;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.repository.TestRepository;
import com.ulp.security.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sources ACTIVE bank items for exam authoring, scoped to the class subject:
 * the author's own lecturer-private bank plus ACTIVE HEAD-bank items of the
 * class subject. When the class subject is unknown the picker falls back to
 * the class department and offers HEAD-bank items only (no private items).
 * Results are returned as immutable snapshots with a {@code source} label so
 * the exam builder can copy them into exam-owned rows without live-link
 * semantics.
 */
@Service
public class ExamQuestionBankPickerService {

    public static final String SOURCE_HEAD_BANK = "HEAD_BANK";
    public static final String SOURCE_LECTURER_BANK = "LECTURER_BANK";

    private static final String MSG_FORBIDDEN = "Bạn không có quyền dùng câu hỏi ngân hàng";

    private final UserRepository userRepository;
    private final QuestionBankAccessPolicy accessPolicy;
    private final TestRepository testRepository;
    private final ClassRepository classRepository;
    private final SubjectRepository subjectRepository;
    private final SubjectChapterRepository chapterRepository;
    private final QuestionBankItemRepository itemRepository;
    private final QuestionBankOptionRepository optionRepository;

    public ExamQuestionBankPickerService(UserRepository userRepository,
                                         QuestionBankAccessPolicy accessPolicy,
                                         TestRepository testRepository,
                                         ClassRepository classRepository,
                                         SubjectRepository subjectRepository,
                                         SubjectChapterRepository chapterRepository,
                                         QuestionBankItemRepository itemRepository,
                                         QuestionBankOptionRepository optionRepository) {
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.testRepository = testRepository;
        this.classRepository = classRepository;
        this.subjectRepository = subjectRepository;
        this.chapterRepository = chapterRepository;
        this.itemRepository = itemRepository;
        this.optionRepository = optionRepository;
    }

    /** Chapters of the class subject the actor may browse in the picker. */
    @Transactional(readOnly = true)
    public List<BankChapterOption> chaptersFor(Long userId, Role role, Long testId) {
        User actor = requireActor(userId, role);
        Scope scope = resolveAccessibleScope(actor, testId);
        if (scope.subjectId() == null) {
            return List.of();
        }
        return chapterRepository.findBySubjectIdOrderByDisplayOrderAsc(scope.subjectId()).stream()
                .map(chapter -> new BankChapterOption(chapter.getId(), chapter.getTitle()))
                .toList();
    }

    /**
     * ACTIVE items scoped to the class subject from both banks (own private +
     * HEAD bank), labelled by source. Filtered by optional chapter + query.
     * When the class has no subject, falls back to the department and offers
     * HEAD-bank items only.
     */
    @Transactional(readOnly = true)
    public List<BankItemSnapshot> searchActive(Long userId, Role role, Long testId,
                                               Long chapterId, String query) {
        User actor = requireActor(userId, role);
        Scope scope = resolveAccessibleScope(actor, testId);
        String normalizedQuery = normalizeQuery(query);
        List<QuestionBankItem> candidates = new ArrayList<>();
        if (scope.subjectId() != null) {
            candidates.addAll(itemRepository
                    .findByOwnerIdAndSubjectIdOrderByUpdatedAtDescIdDesc(actor.getId(), scope.subjectId()));
            candidates.addAll(itemRepository
                    .findByOwnerIdIsNullAndDepartmentIdAndSubjectIdOrderByUpdatedAtDescIdDesc(
                            scope.departmentId(), scope.subjectId()));
        } else {
            // No class subject: fall back to the department, HEAD-bank items only.
            candidates.addAll(itemRepository
                    .findByOwnerIdIsNullAndDepartmentIdOrderByUpdatedAtDescIdDesc(scope.departmentId()));
        }
        List<QuestionBankItem> items = candidates.stream()
                .filter(item -> isSelectable(item, actor, scope))
                .filter(item -> chapterId == null || chapterId.equals(item.getChapterId()))
                .filter(item -> matchesQuery(item, normalizedQuery))
                .limit(20)
                .toList();
        return toSnapshots(items);
    }

    /**
     * Loads ACTIVE snapshots for the given bank item ids, selectable by the actor
     * for the resolved scope (own private item of the class subject, or HEAD-bank
     * item of the resolved department — restricted to the class subject when one
     * is resolved; HEAD-bank items of the department only when the subject is
     * unknown). Any id outside that set is silently dropped so the caller inserts
     * a safe subset.
     */
    @Transactional(readOnly = true)
    public List<BankItemSnapshot> activeSnapshotsByIds(Long userId, Role role, Long testId, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        User actor = requireActor(userId, role);
        Scope scope = resolveAccessibleScope(actor, testId);
        Set<Long> wanted = new LinkedHashSet<>(itemIds);
        List<QuestionBankItem> readable = new ArrayList<>();
        for (Long id : wanted) {
            itemRepository.findById(id).ifPresent(item -> {
                if (isSelectable(item, actor, scope)) {
                    readable.add(item);
                }
            });
        }
        Map<Long, BankItemSnapshot> byId = new LinkedHashMap<>();
        for (BankItemSnapshot snapshot : toSnapshots(readable)) {
            byId.put(snapshot.id(), snapshot);
        }
        List<BankItemSnapshot> ordered = new ArrayList<>();
        for (Long id : itemIds) {
            BankItemSnapshot snapshot = byId.get(id);
            if (snapshot != null) {
                ordered.add(snapshot);
            }
        }
        return ordered;
    }

    /**
     * True when the item may be snapshot-selected for the given scope: ACTIVE and
     * either the actor's own private item of the class subject, or a HEAD-bank
     * item of the resolved department. When a class subject is resolved, both
     * sources are further restricted to that subject; without one, only HEAD-bank
     * items of the department qualify (private items are never selectable).
     */
    private boolean isSelectable(QuestionBankItem item, User actor, Scope scope) {
        if (!QuestionBankItem.STATUS_ACTIVE.equals(item.getStatus())) {
            return false;
        }
        if (scope.subjectId() != null) {
            if (!scope.subjectId().equals(item.getSubjectId())) {
                return false;
            }
            if (item.getOwnerId() != null) {
                return item.getOwnerId().equals(actor.getId());
            }
            return scope.departmentId().equals(item.getDepartmentId());
        }
        return item.getOwnerId() == null && scope.departmentId().equals(item.getDepartmentId());
    }

    private List<BankItemSnapshot> toSnapshots(List<QuestionBankItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<Long, List<QuestionBankOption>> optionsByItem = loadOptions(items);
        Map<Long, Subject> subjects = loadSubjects(items);
        return items.stream()
                .map(item -> {
                    Subject subject = subjects.get(item.getSubjectId());
                    List<BankOptionSnapshot> options = optionsByItem
                            .getOrDefault(item.getId(), List.of()).stream()
                            .map(option -> new BankOptionSnapshot(option.getContent(), option.isCorrect()))
                            .toList();
                    String source = item.getOwnerId() == null ? SOURCE_HEAD_BANK : SOURCE_LECTURER_BANK;
                    return new BankItemSnapshot(item.getId(), source, subjectLabel(subject),
                            item.getQuestionType(), item.getContent(), item.getExplanation(), options);
                })
                .toList();
    }

    private Map<Long, List<QuestionBankOption>> loadOptions(List<QuestionBankItem> items) {
        Map<Long, List<QuestionBankOption>> map = new LinkedHashMap<>();
        List<Long> ids = items.stream().map(QuestionBankItem::getId).toList();
        for (QuestionBankOption option : optionRepository.findByItemIdInOrderBySortOrderAscIdAsc(ids)) {
            map.computeIfAbsent(option.getItemId(), ignored -> new ArrayList<>()).add(option);
        }
        return map;
    }

    private Map<Long, Subject> loadSubjects(List<QuestionBankItem> items) {
        Map<Long, Subject> map = new LinkedHashMap<>();
        for (QuestionBankItem item : items) {
            Long subjectId = item.getSubjectId();
            if (subjectId != null && !map.containsKey(subjectId)) {
                subjectRepository.findById(subjectId).ifPresent(s -> map.put(subjectId, s));
            }
        }
        return map;
    }

    private boolean matchesQuery(QuestionBankItem item, String query) {
        if (query == null) {
            return true;
        }
        return preview(item.getContent()).toLowerCase().contains(query);
    }

    private User requireActor(Long userId, Role role) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException(MSG_FORBIDDEN));
        if (actor.getRole() != role) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        return actor;
    }

    private Scope resolveAccessibleScope(User actor, Long testId) {
        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new AccessDeniedException(MSG_FORBIDDEN));
        ClassEntity clazz = classRepository.findById(test.getClassId())
                .orElseThrow(() -> new AccessDeniedException(MSG_FORBIDDEN));
        Long departmentId = clazz.getDepartmentId();
        if (departmentId == null) {
            departmentId = accessPolicy.resolveDepartmentId(actor);
        }
        if (departmentId == null || !accessPolicy.canReadHeadBank(actor, departmentId)) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        return new Scope(departmentId, clazz.getSubjectId());
    }

    private static String subjectLabel(Subject subject) {
        return subject == null ? "—" : subject.getCode() + " — " + subject.getTitle();
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return query.trim().toLowerCase();
    }

    private static String preview(String html) {
        return html == null ? "" : html.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Class subject (nullable) + its department; the picker scope. */
    private record Scope(Long departmentId, Long subjectId) {
    }
}
