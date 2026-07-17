package com.ulp.features.tests.service;

import com.ulp.features.lessons.support.YouTubeEmbedUrl;
import com.ulp.features.tests.dto.TestDtos.PreviewView;
import com.ulp.features.tests.dto.TestDtos.TakeOptionView;
import com.ulp.features.tests.dto.TestDtos.TakeQuestionView;
import com.ulp.features.tests.dto.TestDtos.TakeView;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.QuestionOption;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.entity.TestAttempt;
import com.ulp.features.tests.repository.QuestionOptionRepository;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.support.DeterministicShuffle;
import com.ulp.features.tests.support.ExamDeadline;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Assembles the taking screen for an attempt: questions + options (never
 * exposing {@code is_correct}), with deterministic shuffle honouring the exam's
 * {@code shuffle_*} flags, and the server-computed remaining time.
 */
@Service
public class TakeViewBuilder {

    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;

    public TakeViewBuilder(QuestionRepository questionRepository,
                           QuestionOptionRepository optionRepository) {
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
    }

    /** Builds the taking view for a live attempt. */
    public TakeView build(Test test, TestAttempt attempt) {
        List<TakeQuestionView> views = buildQuestions(test, attempt.getId(), true);
        long remaining = ExamDeadline.remainingSeconds(test, attempt, LocalDateTime.now());
        return new TakeView(attempt.getId(), test.getId(), test.getTitle(),
                test.getTimeMode(), remaining, test.getMediaType(), test.getMediaUrl(),
                mediaEmbedUrl(test), views);
    }

    /**
     * Builds a lecturer preview of the student screen. Keeps author order
     * (no shuffle) so the owner can verify the written sequence as stored.
     */
    public PreviewView buildPreview(Test test) {
        // Preview skips shuffle so the lecturer sees the authored order.
        List<TakeQuestionView> views = buildQuestions(test, null, false);
        return new PreviewView(test.getId(), test.getTitle(), test.getTimeMode(),
                test.getDurationMinutes(), test.getStartAt(), test.getEndAt(),
                test.getMediaType(), test.getMediaUrl(), mediaEmbedUrl(test), views);
    }

    private static String mediaEmbedUrl(Test test) {
        if (!Test.MEDIA_TYPE_YOUTUBE.equals(test.getMediaType()) || test.getMediaUrl() == null) {
            return null;
        }
        return YouTubeEmbedUrl.toEmbedUrl(test.getMediaUrl());
    }

    private List<TakeQuestionView> buildQuestions(Test test, Long attemptId, boolean allowShuffle) {
        List<Question> questions = questionRepository
                .findByTestIdOrderBySortOrderAscIdAsc(test.getId());
        Map<Long, List<QuestionOption>> optionsByQuestion = loadOptions(questions);

        List<Question> ordered = new ArrayList<>(questions);
        if (allowShuffle && attemptId != null && test.isShuffleQuestions()) {
            DeterministicShuffle.shuffle(ordered, DeterministicShuffle.questionSeed(attemptId));
        }

        List<TakeQuestionView> views = new ArrayList<>();
        for (Question q : ordered) {
            List<QuestionOption> opts = new ArrayList<>(
                    optionsByQuestion.getOrDefault(q.getId(), List.of()));
            if (allowShuffle && attemptId != null && test.isShuffleOptions()) {
                DeterministicShuffle.shuffle(opts,
                        DeterministicShuffle.optionSeed(attemptId, q.getId()));
            }
            List<TakeOptionView> optViews = opts.stream()
                    .map(o -> new TakeOptionView(o.getId(), o.getContent()))
                    .toList();
            views.add(new TakeQuestionView(q.getId(), q.getQuestionType(), q.getContent(),
                    q.getPoints(), optViews));
        }
        return views;
    }

    private Map<Long, List<QuestionOption>> loadOptions(List<Question> questions) {
        if (questions.isEmpty()) return Map.of();
        List<Long> ids = questions.stream().map(Question::getId).toList();
        return optionRepository.findByQuestionIdInOrderBySortOrderAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionId));
    }
}
