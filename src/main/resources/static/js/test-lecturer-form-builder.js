/* Question/option DOM builder for the lecturer exam form.
 * Depends on window.LfQuill for rich-text editors. Form orchestration
 * (hydrate/collect/submit) lives in test-lecturer-form.js.
 */
(function () {
    'use strict';

    function optionLetter(index) {
        // A..Z then fallback to number for unusually long option lists.
        return index < 26 ? String.fromCharCode(65 + index) : String(index + 1);
    }

    function refreshOptionLabels(qEl) {
        qEl.querySelectorAll('.lf-option').forEach(function (oEl, i) {
            var letter = oEl.querySelector('.lf-o-letter');
            if (letter) letter.textContent = optionLetter(i);
        });
    }

    function syncOptionCorrectState(oEl) {
        var checked = !!(oEl.querySelector('.lf-o-correct') || {}).checked;
        oEl.classList.toggle('is-correct', checked);
    }

    /**
     * Derives the question type from how many options are ticked: 2+ correct
     * answers means multi-response. Keeps the hidden input and badge in sync so
     * the lecturer never has to pick MCQ/MR by hand.
     */
    function syncQuestionType(qEl) {
        var correctCount = qEl.querySelectorAll('.lf-o-correct:checked').length;
        var type = correctCount > 1 ? 'MR' : 'MCQ';
        var hidden = qEl.querySelector('.lf-q-type');
        if (hidden) hidden.value = type;
        var badge = qEl.querySelector('.lf-q-type-badge');
        if (badge) {
            badge.dataset.type = type;
            badge.textContent = type === 'MR' ? 'Nhiều đáp án' : 'Một đáp án';
        }
    }

    function readEditorHtml(el, hiddenSelector, quillProp) {
        if (el[quillProp]) {
            return el[quillProp].root.innerHTML;
        }
        var hidden = el.querySelector(hiddenSelector);
        return hidden ? hidden.value : '';
    }

    function parseId(value) {
        if (value == null || value === '') return null;
        var n = Number(value);
        return Number.isFinite(n) ? n : null;
    }

    /**
     * Creates a question/option builder bound to the given form templates.
     * @param {object} opts
     * @param {HTMLElement} opts.qTpl
     * @param {HTMLElement} opts.oTpl
     * @param {HTMLElement} opts.questionsHost
     * @param {HTMLElement|null} opts.noQuestions
     * @param {string} opts.imageUrl
     */
    function create(opts) {
        var qTpl = opts.qTpl;
        var oTpl = opts.oTpl;
        var questionsHost = opts.questionsHost;
        var noQuestions = opts.noQuestions;
        var imageUrl = opts.imageUrl;

        function refreshEmptyHint() {
            var has = questionsHost.querySelectorAll('.lf-question').length > 0;
            if (noQuestions) noQuestions.style.display = has ? 'none' : '';
        }

        function refreshQuestionNumbers() {
            questionsHost.querySelectorAll('.lf-question').forEach(function (qEl, i) {
                var no = qEl.querySelector('.lf-q-no');
                if (no) no.textContent = 'Câu ' + (i + 1);
            });
        }

        function mountQuestionEditor(qEl, html) {
            var host = qEl.querySelector('.lf-q-editor');
            var hidden = qEl.querySelector('.lf-q-content');
            var quill = window.LfQuill.createQuill(host, hidden, 'Nội dung câu hỏi (có thể chèn ảnh)', [
                [{ header: [2, 3, false] }],
                ['bold', 'italic', 'underline'],
                [{ list: 'ordered' }, { list: 'bullet' }],
                ['link', 'image'],
                ['clean']
            ], imageUrl);
            if (!quill) return;
            if (html) {
                quill.root.innerHTML = html;
                hidden.value = html;
            }
            qEl._quill = quill;
        }

        function mountOptionEditor(oEl, html) {
            var host = oEl.querySelector('.lf-o-editor');
            var hidden = oEl.querySelector('.lf-o-content');
            var quill = window.LfQuill.createQuill(host, hidden, 'Nội dung đáp án…', [
                ['bold', 'italic', 'underline'],
                ['link', 'image'],
                ['clean']
            ], imageUrl);
            if (!quill) return;
            if (html) {
                // Plain legacy text becomes a paragraph so Quill/edit stays consistent.
                var seed = html;
                if (seed && seed.indexOf('<') === -1) {
                    seed = '<p>' + seed + '</p>';
                }
                quill.root.innerHTML = seed;
                hidden.value = seed;
            }
            oEl._quill = quill;

            // Compact UX: show formatting toolbar only while this answer is active.
            quill.root.addEventListener('focus', function () {
                oEl.classList.add('is-active');
            });
            quill.root.addEventListener('blur', function () {
                // Delay so toolbar clicks still count as interaction on this row.
                setTimeout(function () {
                    if (!oEl.contains(document.activeElement)) {
                        oEl.classList.remove('is-active');
                    }
                }, 120);
            });
            oEl.addEventListener('mousedown', function () {
                oEl.classList.add('is-active');
            });
        }

        function addOption(qEl, data) {
            var node = oTpl.content.firstElementChild.cloneNode(true);
            // Keep stable option ids so save can update in place after students submit.
            node.dataset.optionId = data && data.id != null ? String(data.id) : '';
            if (data) {
                node.querySelector('.lf-o-correct').checked = !!data.correct;
            }
            syncOptionCorrectState(node);
            var correctCb = node.querySelector('.lf-o-correct');
            correctCb.addEventListener('change', function () {
                syncOptionCorrectState(node);
                syncQuestionType(qEl);
            });
            node.querySelector('.lf-o-remove').addEventListener('click', function () {
                node.remove();
                refreshOptionLabels(qEl);
                // Removing a ticked option can flip MR back to MCQ.
                syncQuestionType(qEl);
            });
            qEl.querySelector('.lf-options').appendChild(node);
            mountOptionEditor(node, data ? (data.content || '') : '');
            refreshOptionLabels(qEl);
        }

        function addQuestion(data) {
            var node = qTpl.content.firstElementChild.cloneNode(true);
            // Keep stable question ids so save can update in place after students submit.
            node.dataset.questionId = data && data.id != null ? String(data.id) : '';
            questionsHost.appendChild(node);
            if (data) {
                node.querySelector('.lf-q-explanation').value = data.explanation || '';
                node.querySelector('.lf-q-points').value = data.points != null ? data.points : 1;
                mountQuestionEditor(node, data.content || '');
                (data.options || []).forEach(function (o) { addOption(node, o); });
            } else {
                mountQuestionEditor(node, '');
                addOption(node, null);
                addOption(node, null);
            }
            // Derive the badge/hidden type from the options just loaded.
            syncQuestionType(node);
            node.querySelector('.lf-add-option').addEventListener('click', function () {
                addOption(node, null);
            });
            node.querySelector('.lf-q-remove').addEventListener('click', function () {
                node.remove();
                refreshEmptyHint();
                refreshQuestionNumbers();
            });
            refreshEmptyHint();
            refreshQuestionNumbers();
        }

        function collectQuestions() {
            var questions = [];
            questionsHost.querySelectorAll('.lf-question').forEach(function (qEl) {
                var options = [];
                qEl.querySelectorAll('.lf-option').forEach(function (oEl) {
                    options.push({
                        id: parseId(oEl.dataset.optionId),
                        content: readEditorHtml(oEl, '.lf-o-content', '_quill'),
                        correct: oEl.querySelector('.lf-o-correct').checked
                    });
                });
                // Recompute rather than trusting the hidden input, so the saved
                // type always matches the ticked options.
                var correctCount = options.filter(function (o) { return o.correct; }).length;
                questions.push({
                    id: parseId(qEl.dataset.questionId),
                    type: correctCount > 1 ? 'MR' : 'MCQ',
                    content: readEditorHtml(qEl, '.lf-q-content', '_quill'),
                    explanation: qEl.querySelector('.lf-q-explanation').value.trim(),
                    points: Number(qEl.querySelector('.lf-q-points').value || 1),
                    options: options
                });
            });
            return questions;
        }

        function listQuills() {
            var editors = [];
            questionsHost.querySelectorAll('.lf-question').forEach(function (qEl) {
                if (qEl._quill) editors.push(qEl._quill);
                qEl.querySelectorAll('.lf-option').forEach(function (oEl) {
                    if (oEl._quill) editors.push(oEl._quill);
                });
            });
            return editors;
        }

        return {
            addQuestion: addQuestion,
            collectQuestions: collectQuestions,
            listQuills: listQuills,
            refreshEmptyHint: refreshEmptyHint
        };
    }

    window.LfBuilder = { create: create };
})();
