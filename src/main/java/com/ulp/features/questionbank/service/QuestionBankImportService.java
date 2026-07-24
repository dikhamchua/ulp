package com.ulp.features.questionbank.service;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.questionbank.dto.QuestionBankImportDtos.ConfirmResult;
import com.ulp.features.questionbank.dto.QuestionBankImportDtos.PreviewRow;
import com.ulp.features.questionbank.entity.QuestionBankCategory;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.entity.QuestionBankOption;
import com.ulp.features.questionbank.imports.QuestionBankImportParser;
import com.ulp.features.questionbank.imports.QuestionBankImportParser.ParsedFile;
import com.ulp.features.questionbank.imports.QuestionBankImportParser.RawRow;
import com.ulp.features.questionbank.imports.QuestionBankImportSession;
import com.ulp.features.questionbank.imports.QuestionBankImportSession.ImportedItem;
import com.ulp.features.questionbank.imports.QuestionBankImportSession.ImportedOption;
import com.ulp.features.questionbank.imports.QuestionBankImportSessionStore;
import com.ulp.features.questionbank.repository.QuestionBankCategoryRepository;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import com.ulp.features.questionbank.repository.QuestionBankOptionRepository;
import com.ulp.security.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Two-step preview and confirm flow for department-scoped Excel imports. */
@Service
public class QuestionBankImportService {

    private static final String MSG_FORBIDDEN = "Bạn không có quyền import câu hỏi cộng tác cho bộ môn này";
    private static final String MSG_SESSION_EXPIRED =
            "Phiên import đã hết hạn hoặc không tồn tại. Vui lòng tải file lên lại";
    private static final String MSG_BLOCKING_ERRORS =
            "Bản xem trước còn lỗi chặn nên chưa thể xác nhận import";
    private static final String MSG_EMPTY_DEPARTMENT =
            "Bạn chưa được gán bộ môn để import câu hỏi cộng tác";
    private static final int MAX_PREVIEW_LENGTH = 80;

    private final UserRepository userRepository;
    private final QuestionBankAccessPolicy accessPolicy;
    private final QuestionBankCategoryRepository categoryRepository;
    private final QuestionBankItemRepository itemRepository;
    private final QuestionBankOptionRepository optionRepository;
    private final QuestionBankImportParser importParser;
    private final QuestionBankImportSessionStore sessionStore;

    public QuestionBankImportService(UserRepository userRepository,
                                     QuestionBankAccessPolicy accessPolicy,
                                     QuestionBankCategoryRepository categoryRepository,
                                     QuestionBankItemRepository itemRepository,
                                     QuestionBankOptionRepository optionRepository,
                                     QuestionBankImportParser importParser,
                                     QuestionBankImportSessionStore sessionStore) {
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.categoryRepository = categoryRepository;
        this.itemRepository = itemRepository;
        this.optionRepository = optionRepository;
        this.importParser = importParser;
        this.sessionStore = sessionStore;
    }

    @Transactional(readOnly = true)
    public QuestionBankImportSession previewUpload(Long userId, Role role, MultipartFile file) {
        User actor = requireActor(userId, role);
        Long departmentId = requireDepartment(actor);
        Map<String, QuestionBankCategory> categories = activeCategoriesByName(departmentId);
        String workflowStatus = importedWorkflowStatus(actor);
        ParsedFile parsed = importParser.parse(file);

        List<ImportedItem> acceptedItems = new ArrayList<>();
        List<PreviewRow> previewRows = new ArrayList<>();
        for (RawRow raw : parsed.rows()) {
            ValidationResult result = validateRow(raw, categories);
            previewRows.add(result.previewRow());
            if (!result.blocking()) {
                acceptedItems.add(result.item());
            }
        }

        QuestionBankImportSession session = new QuestionBankImportSession(
                UUID.randomUUID(),
                actor.getId(),
                departmentId,
                Instant.now(),
                parsed.fileName(),
                workflowStatus,
                acceptedItems,
                previewRows);
        sessionStore.save(session);
        return session;
    }

    @Transactional
    public ConfirmResult confirm(Long userId, Role role, UUID sessionId) {
        User actor = requireActor(userId, role);
        Long departmentId = requireDepartment(actor);
        QuestionBankImportSession session = sessionStore.get(sessionId, actor.getId())
                .orElseThrow(() -> new QuestionBankValidationException(MSG_SESSION_EXPIRED));
        if (!departmentId.equals(session.getDepartmentId())) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        if (!session.toPreview().confirmable()) {
            throw new QuestionBankValidationException(MSG_BLOCKING_ERRORS);
        }

        List<Long> itemIds = new ArrayList<>();
        for (ImportedItem importedItem : session.getItems()) {
            QuestionBankItem item = itemRepository.save(new QuestionBankItem(
                    departmentId,
                    importedItem.categoryId(),
                    actor.getId(),
                    importedItem.questionType(),
                    session.getWorkflowStatus(),
                    importedItem.contentHtml(),
                    importedItem.explanationHtml()));
            int order = 1;
            for (ImportedOption option : importedItem.options()) {
                optionRepository.save(new QuestionBankOption(
                        item.getId(), option.contentHtml(), option.correct(), order++));
            }
            itemIds.add(item.getId());
        }
        sessionStore.delete(sessionId);
        return new ConfirmResult(itemIds.size(), session.toPreview().totalRows(), session.getWorkflowStatus(), itemIds);
    }

    private ValidationResult validateRow(RawRow raw, Map<String, QuestionBankCategory> categories) {
        List<String> messages = new ArrayList<>();
        QuestionBankCategory category = categories.get(normalizeKey(raw.categoryName()));
        if (category == null) {
            messages.add("Danh mục không tồn tại hoặc đang bị ẩn");
        }

        String questionType = normalizeQuestionType(raw.questionType());
        if (questionType == null) {
            messages.add("Loại câu hỏi chỉ chấp nhận MCQ hoặc MR");
        }

        String contentHtml = plainTextToHtml(raw.content());
        if (contentHtml == null) {
            messages.add("Nội dung câu hỏi không được để trống");
        }

        String explanationHtml = plainTextToHtml(raw.explanation());
        if (explanationHtml != null && explanationHtml.length() > 5000) {
            messages.add("Giải thích tối đa 5000 ký tự");
        }

        List<ImportedOption> importedOptions = new ArrayList<>();
        Map<String, Integer> optionIndexByLabel = new LinkedHashMap<>();
        int visualIndex = 0;
        for (String value : raw.optionValues()) {
            String content = plainTextToHtml(value);
            String label = String.valueOf((char) ('A' + visualIndex));
            if (content != null) {
                optionIndexByLabel.put(label, importedOptions.size());
                importedOptions.add(new ImportedOption(content, false, importedOptions.size() + 1));
            }
            visualIndex++;
        }
        if (importedOptions.size() < 2) {
            messages.add("Cần ít nhất hai đáp án không rỗng");
        }

        Set<String> correctLabels = parseCorrectAnswers(raw.correctAnswers());
        if (correctLabels.isEmpty()) {
            messages.add("Phải khai báo ít nhất một đáp án đúng");
        }
        int correctCount = 0;
        for (String label : correctLabels) {
            Integer index = optionIndexByLabel.get(label);
            if (index == null) {
                messages.add("Đáp án đúng chứa lựa chọn không tồn tại: " + label);
                continue;
            }
            ImportedOption old = importedOptions.get(index);
            importedOptions.set(index, new ImportedOption(old.contentHtml(), true, old.sortOrder()));
            correctCount++;
        }
        if (QuestionBankItem.TYPE_MCQ.equals(questionType) && correctCount != 1) {
            messages.add("Câu hỏi MCQ phải có đúng một đáp án đúng");
        }
        if (QuestionBankItem.TYPE_MR.equals(questionType) && correctCount == 0) {
            messages.add("Câu hỏi MR phải có ít nhất một đáp án đúng");
        }

        boolean blocking = !messages.isEmpty();
        String message = blocking ? String.join("; ", messages) : "Sẵn sàng import";
        PreviewRow previewRow = new PreviewRow(
                raw.rowNumber(),
                raw.categoryName(),
                questionType == null ? blankToDash(raw.questionType()) : questionType,
                preview(raw.content()),
                blocking ? "ERROR" : "READY",
                message,
                importedOptions.size(),
                correctCount,
                blocking);
        ImportedItem item = blocking ? null : new ImportedItem(
                category.getId(),
                questionType,
                contentHtml,
                explanationHtml,
                importedOptions);
        return new ValidationResult(previewRow, item, blocking);
    }

    private Map<String, QuestionBankCategory> activeCategoriesByName(Long departmentId) {
        Map<String, QuestionBankCategory> categories = new LinkedHashMap<>();
        for (QuestionBankCategory category : categoryRepository.findByDepartmentIdAndActiveTrueOrderByNameAsc(departmentId)) {
            categories.put(normalizeKey(category.getName()), category);
        }
        return categories;
    }

    private User requireActor(Long userId, Role role) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException(MSG_FORBIDDEN));
        if (actor.getRole() != role) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        if (role != Role.LECTURER && role != Role.HEAD && role != Role.ADMIN) {
            throw new AccessDeniedException(MSG_FORBIDDEN);
        }
        return actor;
    }

    private Long requireDepartment(User actor) {
        Long departmentId = accessPolicy.resolveDepartmentId(actor);
        if (departmentId == null || !accessPolicy.canAccessDepartment(actor, departmentId)) {
            throw new QuestionBankValidationException(MSG_EMPTY_DEPARTMENT);
        }
        return departmentId;
    }

    private static String importedWorkflowStatus(User actor) {
        return actor.getRole() == Role.LECTURER
                ? QuestionBankItem.STATUS_REVIEW
                : QuestionBankItem.STATUS_APPROVED;
    }

    private static String normalizeQuestionType(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (QuestionBankItem.TYPE_MCQ.equals(normalized) || QuestionBankItem.TYPE_MR.equals(normalized)) {
            return normalized;
        }
        return null;
    }

    private static Set<String> parseCorrectAnswers(String raw) {
        Set<String> labels = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return labels;
        }
        for (String token : raw.toUpperCase(Locale.ROOT).split("[,;/\\s]+")) {
            if (!token.isBlank()) {
                labels.add(token.trim());
            }
        }
        return labels;
    }

    private static String plainTextToHtml(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String escaped = HtmlUtils.htmlEscape(value.trim());
        return "<p>" + escaped.replace("\n", "<br>") + "</p>";
    }

    private static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        return QuestionBankImportParser.normalize(value);
    }

    private static String preview(String value) {
        String trimmed = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return trimmed.length() > MAX_PREVIEW_LENGTH ? trimmed.substring(0, MAX_PREVIEW_LENGTH - 3) + "..." : trimmed;
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private record ValidationResult(PreviewRow previewRow,
                                    ImportedItem item,
                                    boolean blocking) {
    }
}
