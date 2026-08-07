package com.ulp.features.tests.service;

import com.ulp.features.tests.dto.LecturerTestDtos.ClassOption;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.QuestionOption;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.repository.QuestionOptionRepository;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.repository.TestAttemptRepository;
import com.ulp.features.tests.repository.TestRepository;
import com.ulp.features.tests.support.TestAccessResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.ulp.common.IConstant.EXAM_CLONE_TITLE_SUFFIX;
import static com.ulp.common.IConstant.MSG_EXAM_CLONE_FORBIDDEN;
import static com.ulp.common.IConstant.MSG_EXAM_CLONE_NEEDS_CLASS;
import static com.ulp.common.IConstant.MSG_EXAM_DELETE_HAS_ATTEMPTS;

/**
 * Write operations exposed by the Library "Bài test" rail: deep-copying an exam
 * into another class the lecturer leads, and soft-deleting an exam.
 *
 * <p>Both are owner-scoped through {@link TestAccessResolver#requireManageable}
 * (layer 2); the controller's {@code @PreAuthorize} is layer 1 only. Neither
 * operation exists on the older {@code /lecturer/tests} screen — they are the
 * two genuinely new behaviours this feature introduces.
 */
@Service
public class ExamCloneService {

    private final TestRepository testRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final TestAttemptRepository attemptRepository;
    private final TestAccessResolver accessResolver;
    private final LecturerExamQueryService examQueryService;

    public ExamCloneService(TestRepository testRepository,
                            QuestionRepository questionRepository,
                            QuestionOptionRepository optionRepository,
                            TestAttemptRepository attemptRepository,
                            TestAccessResolver accessResolver,
                            LecturerExamQueryService examQueryService) {
        this.testRepository = testRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.attemptRepository = attemptRepository;
        this.accessResolver = accessResolver;
        this.examQueryService = examQueryService;
    }

    /**
     * Deep-copies an owned exam — the {@link Test} plus every {@link Question}
     * and every {@link QuestionOption} beneath it — into {@code targetClassId}.
     *
     * <p>The copy lands as an unscheduled {@code DRAFT}: {@code status},
     * {@code startAt} and {@code endAt} are reset so it is never live in the
     * target class the instant it appears, and is safe to edit before
     * publishing. Everything else about the exam and its questions carries over
     * verbatim, including question images — those are embedded inline in
     * {@code Question.content} (MEDIUMTEXT), so copying the string copies the
     * images and no uploaded file is duplicated.
     *
     * <p>The whole three-level walk runs in one transaction: a failure part-way
     * through rolls back rather than leaving a headless {@code Test} whose
     * questions are missing. The target-class check runs before the first save,
     * so a rejected clone writes nothing at all.
     *
     * @throws jakarta.persistence.EntityNotFoundException source exam missing or soft-deleted
     * @throws AccessDeniedException source not owned, or target not a class the lecturer leads
     * @throws IllegalArgumentException no target class supplied
     * @return the id of the newly created exam
     */
    @Transactional
    public Long cloneToClass(Long sourceTestId, Long targetClassId, Long userId) {
        if (targetClassId == null) {
            throw new IllegalArgumentException(MSG_EXAM_CLONE_NEEDS_CLASS);
        }
        // Layer 2 on the source: created by this lecturer, or their class.
        Test source = accessResolver.requireManageable(sourceTestId, userId);
        // Layer 2 on the target, BEFORE any save, so a rejection leaves zero
        // rows behind. ledClasses also feeds the rail's class dropdown, so the
        // guard and the dropdown can never disagree.
        requireLedClass(userId, targetClassId);

        Test copy = copyOf(source, targetClassId, userId);
        Test savedCopy = testRepository.saveAndFlush(copy);
        copyQuestions(sourceTestId, savedCopy.getId());
        return savedCopy.getId();
    }

    /**
     * Soft-deletes an owned exam, flipping only {@code is_deleted}. The entity's
     * {@code @SQLRestriction("is_deleted = 0")} then removes it from every
     * default query — including the rail's own listing — with no extra filter.
     *
     * <p>Hard-blocked when the exam already has attempts: a student's graded
     * work must not become unreachable because a lecturer tidied their list.
     * There is deliberately no confirm-and-proceed path. Questions and options
     * are never touched, so a future restore would find the exam intact.
     *
     * @throws jakarta.persistence.EntityNotFoundException exam missing or already soft-deleted
     * @throws AccessDeniedException exam not owned by the acting lecturer
     * @throws IllegalStateException the exam has at least one attempt
     */
    @Transactional
    public void softDelete(Long testId, Long userId) {
        Test test = accessResolver.requireManageable(testId, userId);
        // Existence query, not findByTestId(...).isEmpty(): the attempt rows are
        // never needed, only whether any exists.
        if (attemptRepository.existsByTestId(testId)) {
            throw new IllegalStateException(MSG_EXAM_DELETE_HAS_ATTEMPTS);
        }
        test.markDeleted();
        testRepository.save(test);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Throws unless {@code classId} is one of the lecturer's led classes. */
    private void requireLedClass(Long userId, Long classId) {
        List<ClassOption> led = examQueryService.ledClasses(userId);
        if (led.stream().noneMatch(c -> classId.equals(c.id()))) {
            throw new AccessDeniedException(MSG_EXAM_CLONE_FORBIDDEN);
        }
    }

    /** Builds the exam-level copy: settings carried over, schedule reset. */
    private static Test copyOf(Test source, Long targetClassId, Long userId) {
        Test copy = new Test(userId, source.getType());
        copy.setTitle(source.getTitle() + EXAM_CLONE_TITLE_SUFFIX);
        copy.setDescription(source.getDescription());
        copy.setDurationMinutes(source.getDurationMinutes());
        copy.setPassingScore(source.getPassingScore());
        copy.setTotalQuestions(source.getTotalQuestions());
        copy.setShuffleQuestions(source.isShuffleQuestions());
        copy.setShuffleOptions(source.isShuffleOptions());
        copy.setTimeMode(source.getTimeMode());
        copy.setMediaType(source.getMediaType());
        copy.setMediaUrl(source.getMediaUrl());
        copy.setClassId(targetClassId);
        // Reset the schedule: a copy inheriting PUBLISHED plus the source's
        // window would go live in the target class the moment it is created.
        copy.setStatus(Test.STATUS_DRAFT);
        copy.setStartAt(null);
        copy.setEndAt(null);
        return copy;
    }

    /**
     * Copies every question of {@code sourceTestId} under {@code targetTestId},
     * then every option under each new question. Sequential by necessity: the
     * FK columns carry no {@code @OneToMany} cascade, and each child level needs
     * the parent's generated id.
     */
    private void copyQuestions(Long sourceTestId, Long targetTestId) {
        List<Question> sources =
                questionRepository.findByTestIdOrderBySortOrderAscIdAsc(sourceTestId);
        for (Question source : sources) {
            Question copy = new Question(targetTestId, source.getQuestionType(),
                    source.getContent(), source.getExplanation(),
                    source.getPoints(), source.getSortOrder());
            Question savedQuestion = questionRepository.saveAndFlush(copy);
            copyOptions(source.getId(), savedQuestion.getId());
        }
    }

    /** Copies every option of one question, preserving the correct flag and order. */
    private void copyOptions(Long sourceQuestionId, Long targetQuestionId) {
        List<QuestionOption> sources =
                optionRepository.findByQuestionIdOrderBySortOrderAscIdAsc(sourceQuestionId);
        for (QuestionOption source : sources) {
            optionRepository.save(new QuestionOption(targetQuestionId, source.getContent(),
                    source.isCorrect(), source.getSortOrder()));
        }
    }
}
