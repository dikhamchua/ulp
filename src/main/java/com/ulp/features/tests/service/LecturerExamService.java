package com.ulp.features.tests.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.TestActivity;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.tests.dto.LecturerTestDtos.ClassOption;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamForm;
import com.ulp.features.tests.dto.LecturerTestDtos.LecturerExamRow;
import com.ulp.features.tests.dto.LecturerTestDtos.OptionForm;
import com.ulp.features.tests.dto.LecturerTestDtos.QuestionForm;
import com.ulp.features.tests.dto.TestDtos.PreviewView;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.QuestionOption;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.repository.QuestionOptionRepository;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.repository.TestRepository;
import com.ulp.features.tests.repository.TestResponseRepository;
import com.ulp.common.HtmlSanitizer;
import com.ulp.features.tests.support.ExamFormValidator;
import com.ulp.features.tests.support.TestAccessResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ulp.common.IConstant.DEFAULT_EXAM_PAGE_SIZE;
import static com.ulp.common.IConstant.MSG_EXAM_QUESTION_BANK_LOCKED;

/**
 * Lecturer exam authoring: list owned exams, create/edit with a full question-set
 * replacement, and re-derive {@code total_questions}. Ownership is enforced via
 * {@link TestAccessResolver#requireManageable}.
 */
@Service
public class LecturerExamService {

    private final TestRepository testRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final TestResponseRepository responseRepository;
    private final ClassRepository classRepository;
    private final TestAccessResolver accessResolver;
    private final TestActivityWriter activityWriter;
    private final TakeViewBuilder takeViewBuilder;

    public LecturerExamService(TestRepository testRepository,
                               QuestionRepository questionRepository,
                               QuestionOptionRepository optionRepository,
                               TestResponseRepository responseRepository,
                               ClassRepository classRepository,
                               TestAccessResolver accessResolver,
                               TestActivityWriter activityWriter,
                               TakeViewBuilder takeViewBuilder) {
        this.testRepository = testRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.responseRepository = responseRepository;
        this.classRepository = classRepository;
        this.accessResolver = accessResolver;
        this.activityWriter = activityWriter;
        this.takeViewBuilder = takeViewBuilder;
    }

    /** One page of exams the lecturer owns (created or leads the class). */
    @Transactional(readOnly = true)
    public Page<LecturerExamRow> listOwned(Long userId, int page) {
        List<Long> ledClassIds = ledClassIds(userId);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), DEFAULT_EXAM_PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        return toRows(testRepository.findOwnedByLecturer(userId, ledClassIds, pageable));
    }

    /**
     * One page of exams belonging to a single class. Class-level authorization
     * is the caller's responsibility: {@code ClassDetailController} gates the
     * tests tab with {@code classesService.getViewable(...)} (LECTURER-owns /
     * HEAD / ADMIN), mirroring every other class-detail tab. This method trusts
     * that gate and only queries.
     */
    @Transactional(readOnly = true)
    public Page<LecturerExamRow> listForClass(Long classId, int page) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), DEFAULT_EXAM_PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        return toRows(testRepository.findByClassId(classId, pageable));
    }

    /** Maps a page of exams to list rows, resolving class names in one batch. */
    private Page<LecturerExamRow> toRows(Page<Test> tests) {
        Map<Long, String> classNames = resolveClassNames(tests.getContent());
        return tests.map(t -> new LecturerExamRow(t.getId(), t.getTitle(), t.getType(),
                t.getStatus(), classNames.get(t.getClassId()),
                t.getTotalQuestions() == null ? 0 : t.getTotalQuestions(), t.getEndAt()));
    }

    /** Classes the lecturer leads (the exam class picker). */
    @Transactional(readOnly = true)
    public List<ClassOption> ledClasses(Long userId) {
        List<ClassOption> options = new ArrayList<>();
        for (ClassEntity c : classRepository.findAllByLecturerId(userId)) {
            options.add(new ClassOption(c.getId(), c.getName()));
        }
        return options;
    }

    /** Loads an owned exam as an editable form (with its questions + options). */
    @Transactional(readOnly = true)
    public ExamForm getForEdit(Long testId, Long userId) {
        Test test = accessResolver.requireManageable(testId, userId);
        List<Question> questions = questionRepository
                .findByTestIdOrderBySortOrderAscIdAsc(testId);
        Map<Long, List<QuestionOption>> optionsByQuestion = loadOptions(questions);
        List<QuestionForm> qForms = new ArrayList<>();
        for (Question q : questions) {
            List<OptionForm> optForms = optionsByQuestion.getOrDefault(q.getId(), List.of())
                    .stream().map(o -> new OptionForm(o.getId(), o.getContent(), o.isCorrect()))
                    .toList();
            qForms.add(new QuestionForm(q.getId(), q.getQuestionType(), q.getContent(),
                    q.getExplanation(), q.getPoints(), optForms));
        }
        return new ExamForm(test.getId(), test.getTitle(), test.getDescription(),
                test.getClassId(), test.getType(), test.getStatus(), test.getTimeMode(),
                test.getDurationMinutes(), test.getStartAt(), test.getEndAt(),
                test.getPassingScore(), test.isShuffleQuestions(), test.isShuffleOptions(),
                test.getMediaType(), test.getMediaUrl(), qForms);
    }

    /**
     * Builds a student-style preview of an owned exam without starting an attempt.
     * Ownership is enforced via {@link TestAccessResolver#requireManageable}.
     */
    @Transactional(readOnly = true)
    public PreviewView previewAsStudent(Long testId, Long userId) {
        Test test = accessResolver.requireManageable(testId, userId);
        return takeViewBuilder.buildPreview(test);
    }

    /**
     * Creates or updates an exam. Always persists form fields (including media)
     * and question/option content. When student responses already exist the bank
     * shape is locked (no add/remove/reorder of questions or options) so FK rows
     * in {@code test_responses} stay valid — content text/HTML may still change.
     * Returns the persisted exam id.
     */
    @Transactional
    public Long save(Long userId, ExamForm form) {
        ExamFormValidator.validate(form);
        requireLeadsClass(userId, form.classId());

        boolean creating = form.id() == null;
        Test test = creating
                ? new Test(userId, defaultType(form.type()))
                : accessResolver.requireManageable(form.id(), userId);
        String previousStatus = creating ? null : test.getStatus();
        applyFields(test, form);
        Test saved = testRepository.save(test);

        if (creating || !hasStudentResponses(saved.getId())) {
            replaceQuestions(saved.getId(), form.questions());
        } else {
            updateQuestionContentsInPlace(saved.getId(), form.questions());
        }
        saved.setTotalQuestions(form.questions().size());
        testRepository.save(saved);

        recordSaveActivity(saved, userId, creating, previousStatus);
        return saved.getId();
    }

    /** True when any current question already has a student response. */
    private boolean hasStudentResponses(Long testId) {
        List<Question> existing = questionRepository.findByTestIdOrderBySortOrderAscIdAsc(testId);
        if (existing.isEmpty()) return false;
        return responseRepository.existsByQuestionIdIn(
                existing.stream().map(Question::getId).toList());
    }

    /**
     * Updates content of an existing question bank without deleting rows.
     * Shape (counts + ids) must match exactly; otherwise reject so graded
     * responses keep pointing at stable option ids.
     */
    private void updateQuestionContentsInPlace(Long testId, List<QuestionForm> questions) {
        List<Question> existing = questionRepository.findByTestIdOrderBySortOrderAscIdAsc(testId);
        if (existing.size() != questions.size()) {
            throw new IllegalArgumentException(MSG_EXAM_QUESTION_BANK_LOCKED);
        }
        Map<Long, List<QuestionOption>> optionsByQuestion = loadOptions(existing);
        for (int i = 0; i < existing.size(); i++) {
            Question q = existing.get(i);
            QuestionForm qf = questions.get(i);
            // Require stable ids when the bank is locked (client must round-trip them).
            if (qf.id() != null && !qf.id().equals(q.getId())) {
                throw new IllegalArgumentException(MSG_EXAM_QUESTION_BANK_LOCKED);
            }
            List<QuestionOption> opts = optionsByQuestion.getOrDefault(q.getId(), List.of());
            List<OptionForm> optForms = qf.options() == null ? List.of() : qf.options();
            if (opts.size() != optForms.size()) {
                throw new IllegalArgumentException(MSG_EXAM_QUESTION_BANK_LOCKED);
            }
            q.updateContent(defaultQuestionType(qf.type()),
                    HtmlSanitizer.sanitize(qf.content()),
                    trimToNull(qf.explanation()),
                    qf.points(),
                    i + 1);
            questionRepository.save(q);
            for (int j = 0; j < opts.size(); j++) {
                QuestionOption o = opts.get(j);
                OptionForm of = optForms.get(j);
                if (of.id() != null && !of.id().equals(o.getId())) {
                    throw new IllegalArgumentException(MSG_EXAM_QUESTION_BANK_LOCKED);
                }
                o.updateContent(HtmlSanitizer.sanitize(of.content()), of.correct(), j + 1);
                optionRepository.save(o);
            }
        }
    }

    /**
     * Appends audit rows for a save: always a CREATED/UPDATED row, plus a
     * PUBLISHED row when the status transitions into {@code PUBLISHED} on this
     * save (create-as-published counts as a transition too). Audit failures
     * never block the save — they are best-effort within the same transaction.
     */
    private void recordSaveActivity(Test saved, Long userId, boolean creating, String previousStatus) {
        String type = creating ? TestActivity.TYPE_CREATED : TestActivity.TYPE_UPDATED;
        String description = (creating ? "Tạo bài test \"" : "Cập nhật bài test \"")
                + saved.getTitle() + "\"";
        String metadata = activityWriter.serialize(Map.of(
                "status", saved.getStatus() == null ? "" : saved.getStatus(),
                "totalQuestions", saved.getTotalQuestions() == null ? 0 : saved.getTotalQuestions()));
        activityWriter.write(saved.getId(), type, description, metadata, userId);

        boolean nowPublished = Test.STATUS_PUBLISHED.equals(saved.getStatus());
        boolean wasPublished = Test.STATUS_PUBLISHED.equals(previousStatus);
        if (nowPublished && !wasPublished) {
            activityWriter.write(saved.getId(), TestActivity.TYPE_PUBLISHED,
                    "Phát hành bài test \"" + saved.getTitle() + "\"", null, userId);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void applyFields(Test test, ExamForm form) {
        test.setTitle(form.title().trim());
        // Description may hold a reading-passage HTML body from the Quill editor.
        String description = trimToNull(form.description());
        test.setDescription(description == null ? null : HtmlSanitizer.sanitize(description));
        test.setClassId(form.classId());
        test.setType(defaultType(form.type()));
        test.setStatus(form.status() == null ? Test.STATUS_DRAFT : form.status());
        test.setTimeMode(form.timeMode() == null ? Test.TIME_MODE_FIXED_WINDOW : form.timeMode());
        test.setDurationMinutes(form.durationMinutes());
        test.setStartAt(form.startAt());
        test.setEndAt(form.endAt());
        test.setPassingScore(form.passingScore());
        test.setShuffleQuestions(form.shuffleQuestions());
        test.setShuffleOptions(form.shuffleOptions());
        String mediaType = trimToNull(form.mediaType());
        String mediaUrl = trimToNull(form.mediaUrl());
        if (mediaType == null && mediaUrl == null) {
            test.setMediaType(null);
            test.setMediaUrl(null);
        } else {
            test.setMediaType(mediaType);
            test.setMediaUrl(mediaUrl);
        }
    }

    /** Deletes existing questions/options and inserts the submitted set in order. */
    private void replaceQuestions(Long testId, List<QuestionForm> questions) {
        List<Question> existing = questionRepository.findByTestIdOrderBySortOrderAscIdAsc(testId);
        if (!existing.isEmpty()) {
            optionRepository.deleteByQuestionIdIn(existing.stream().map(Question::getId).toList());
            questionRepository.deleteByTestId(testId);
        }
        int order = 1;
        for (QuestionForm qf : questions) {
            String contentHtml = HtmlSanitizer.sanitize(qf.content());
            Question q = new Question(testId, defaultQuestionType(qf.type()), contentHtml,
                    trimToNull(qf.explanation()), qf.points(), order++);
            Long qId = questionRepository.save(q).getId();
            int optOrder = 1;
            for (OptionForm of : qf.options()) {
                String optionHtml = HtmlSanitizer.sanitize(of.content());
                optionRepository.save(new QuestionOption(qId, optionHtml, of.correct(), optOrder++));
            }
        }
    }

    private void requireLeadsClass(Long userId, Long classId) {
        boolean leads = classRepository.findById(classId)
                .map(ClassEntity::getLecturerId).map(userId::equals).orElse(false);
        if (!leads) {
            throw new AccessDeniedException(TestAccessResolver.NF_MSG);
        }
    }

    private List<Long> ledClassIds(Long userId) {
        List<Long> ids = new ArrayList<>();
        classRepository.findAllByLecturerId(userId).forEach(c -> ids.add(c.getId()));
        // Sentinel keeps the JPQL IN clause valid when the lecturer leads no class.
        if (ids.isEmpty()) ids.add(-1L);
        return ids;
    }

    private Map<Long, String> resolveClassNames(List<Test> tests) {
        Map<Long, String> names = new HashMap<>();
        List<Long> ids = tests.stream().map(Test::getClassId)
                .filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) return names;
        for (ClassEntity c : classRepository.findAllById(ids)) {
            names.put(c.getId(), c.getName());
        }
        return names;
    }

    private Map<Long, List<QuestionOption>> loadOptions(List<Question> questions) {
        if (questions.isEmpty()) return Map.of();
        List<Long> ids = questions.stream().map(Question::getId).toList();
        Map<Long, List<QuestionOption>> map = new HashMap<>();
        for (QuestionOption o : optionRepository.findByQuestionIdInOrderBySortOrderAscIdAsc(ids)) {
            map.computeIfAbsent(o.getQuestionId(), k -> new ArrayList<>()).add(o);
        }
        return map;
    }

    private static String defaultType(String type) {
        return type == null || type.isBlank() ? Test.TYPE_MOCK : type;
    }

    private static String defaultQuestionType(String type) {
        return Question.TYPE_MR.equals(type) ? Question.TYPE_MR : Question.TYPE_MCQ;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
