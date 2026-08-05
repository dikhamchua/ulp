package com.ulp.features.questionbank.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/** Form-backing DTO for SSR question bank authoring (subject + optional chapter). */
public class QuestionBankItemForm {

    private Long id;

    @NotNull(message = "Vui lòng chọn môn học")
    private Long subjectId;

    /** Optional; validated server-side to belong to the item's subject. */
    private Long chapterId;

    @NotBlank(message = "Nội dung câu hỏi không được để trống")
    private String content;

    @Size(max = 5000, message = "Giải thích tối đa 5000 ký tự")
    private String explanation;

    @Valid
    private List<OptionField> options = new ArrayList<>();

    public static QuestionBankItemForm empty() {
        QuestionBankItemForm form = new QuestionBankItemForm();
        for (int i = 0; i < 4; i++) {
            form.options.add(new OptionField());
        }
        return form;
    }

    public void ensureMinOptions(int min) {
        while (options.size() < min) {
            options.add(new OptionField());
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public Long getChapterId() {
        return chapterId;
    }

    public void setChapterId(Long chapterId) {
        this.chapterId = chapterId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public List<OptionField> getOptions() {
        return options;
    }

    public void setOptions(List<OptionField> options) {
        this.options = options == null ? new ArrayList<>() : options;
    }

    /** One editable option row in the SSR form. */
    public static class OptionField {

        private String content;
        private boolean correct;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public boolean isCorrect() {
            return correct;
        }

        public void setCorrect(boolean correct) {
            this.correct = correct;
        }
    }
}
