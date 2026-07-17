/* Lecturer exam builder (Epic #11): add/remove questions + options, mark
 * correct (MCQ enforces a single correct client-side), toggle the duration
 * field by time_mode, and save the whole exam as one JSON payload on submit
 * (single submit orchestrator; deferred save). Field errors render inline;
 * top-level errors go through UlpToast.
 *
 * Exposes window.LfForm.mount() so the AJAX tab orchestrator
 * (test-detail-tabs.js) can (re)initialise the builder after swapping the
 * #tabPanel content in place, without a full-page reload. The builder is a
 * no-op when #lfForm is absent (non-info tab), so mount() is safe to call
 * on every swap. Exam data is read from the #lfData JSON island (which lives
 * inside #tabPanel and therefore travels with each swap), not a global.
 */
(function () {
    'use strict';

    function ready(fn) {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', fn);
        } else {
            fn();
        }
    }

    function toast(kind, msg) {
        if (window.FcCommon) window.FcCommon.toast(kind, msg);
    }

    function val(id) {
        var el = document.getElementById(id);
        return el ? el.value.trim() : '';
    }

    function numOrNull(id) {
        var v = val(id);
        return v === '' ? null : Number(v);
    }

    function toLocalInput(iso) {
        // Jackson serialises LocalDateTime as "YYYY-MM-DDTHH:mm:ss"; the
        // datetime-local input wants the first 16 chars.
        return iso ? String(iso).slice(0, 16) : '';
    }

    /** Reads the exam payload from the #lfData JSON island (null in create mode). */
    function readExamData() {
        var el = document.getElementById('lfData');
        if (!el) return null;
        var raw = el.textContent.trim();
        if (!raw || raw === 'null') return null;
        try {
            return JSON.parse(raw);
        } catch (e) {
            return null;
        }
    }

    /** Initialises the question builder. Safe no-op when #lfForm is absent. */
    function mount() {
        var form = document.getElementById('lfForm');
        if (!form) return;
        // Guard against double-mount on the same DOM (orchestrator + DOMContentLoaded).
        if (form.dataset.lfMounted === '1') return;
        form.dataset.lfMounted = '1';

        var qTpl = document.getElementById('lfQuestionTpl');
        var oTpl = document.getElementById('lfOptionTpl');
        var questionsHost = document.getElementById('lfQuestions');
        var noQuestions = document.getElementById('lfNoQuestions');
        var timeMode = document.getElementById('lfTimeMode');
        var durationWrap = document.getElementById('lfDurationWrap');
        var editId = null;

        function refreshEmptyHint() {
            var has = questionsHost.querySelectorAll('.lf-question').length > 0;
            if (noQuestions) noQuestions.style.display = has ? 'none' : '';
        }

        function syncDuration() {
            durationWrap.style.display = timeMode.value === 'INDIVIDUAL' ? '' : 'none';
        }

        function addOption(qEl, data) {
            var node = oTpl.content.firstElementChild.cloneNode(true);
            if (data) {
                node.querySelector('.lf-o-content').value = data.content || '';
                node.querySelector('.lf-o-correct').checked = !!data.correct;
            }
            var correctCb = node.querySelector('.lf-o-correct');
            correctCb.addEventListener('change', function () {
                if (qEl.querySelector('.lf-q-type').value === 'MCQ' && correctCb.checked) {
                    qEl.querySelectorAll('.lf-o-correct').forEach(function (other) {
                        if (other !== correctCb) other.checked = false;
                    });
                }
            });
            node.querySelector('.lf-o-remove').addEventListener('click', function () {
                node.remove();
            });
            qEl.querySelector('.lf-options').appendChild(node);
        }

        function addQuestion(data) {
            var node = qTpl.content.firstElementChild.cloneNode(true);
            questionsHost.appendChild(node);
            if (data) {
                node.querySelector('.lf-q-type').value = data.type || 'MCQ';
                node.querySelector('.lf-q-content').value = data.content || '';
                node.querySelector('.lf-q-explanation').value = data.explanation || '';
                node.querySelector('.lf-q-points').value = data.points != null ? data.points : 1;
                (data.options || []).forEach(function (o) { addOption(node, o); });
            } else {
                addOption(node, null);
                addOption(node, null);
            }
            node.querySelector('.lf-add-option').addEventListener('click', function () {
                addOption(node, null);
            });
            node.querySelector('.lf-q-remove').addEventListener('click', function () {
                node.remove();
                refreshEmptyHint();
            });
            refreshEmptyHint();
        }

        function collect() {
            var questions = [];
            questionsHost.querySelectorAll('.lf-question').forEach(function (qEl) {
                var options = [];
                qEl.querySelectorAll('.lf-option').forEach(function (oEl) {
                    options.push({
                        id: null,
                        content: oEl.querySelector('.lf-o-content').value.trim(),
                        correct: oEl.querySelector('.lf-o-correct').checked
                    });
                });
                questions.push({
                    id: null,
                    type: qEl.querySelector('.lf-q-type').value,
                    content: qEl.querySelector('.lf-q-content').value.trim(),
                    explanation: qEl.querySelector('.lf-q-explanation').value.trim(),
                    points: Number(qEl.querySelector('.lf-q-points').value || 1),
                    options: options
                });
            });
            return {
                id: editId,
                title: val('lfTitle'),
                description: val('lfDescription'),
                classId: numOrNull('lfClass'),
                type: val('lfType'),
                status: val('lfStatus'),
                timeMode: val('lfTimeMode'),
                durationMinutes: numOrNull('lfDuration'),
                startAt: val('lfStartAt') || null,
                endAt: val('lfEndAt') || null,
                passingScore: numOrNull('lfPassing'),
                shuffleQuestions: document.getElementById('lfShuffleQ').checked,
                shuffleOptions: document.getElementById('lfShuffleO').checked,
                mediaType: val('lfMediaType') || null,
                mediaUrl: val('lfMediaUrl') || null,
                questions: questions
            };
        }

        function hydrate(f) {
            editId = f.id;
            document.getElementById('lfTitle').value = f.title || '';
            document.getElementById('lfDescription').value = f.description || '';
            if (f.classId != null) document.getElementById('lfClass').value = f.classId;
            if (f.type) document.getElementById('lfType').value = f.type;
            if (f.status) document.getElementById('lfStatus').value = f.status;
            if (f.timeMode) document.getElementById('lfTimeMode').value = f.timeMode;
            if (f.durationMinutes != null) document.getElementById('lfDuration').value = f.durationMinutes;
            document.getElementById('lfStartAt').value = toLocalInput(f.startAt);
            document.getElementById('lfEndAt').value = toLocalInput(f.endAt);
            if (f.passingScore != null) document.getElementById('lfPassing').value = f.passingScore;
            document.getElementById('lfShuffleQ').checked = !!f.shuffleQuestions;
            document.getElementById('lfShuffleO').checked = !!f.shuffleOptions;
            document.getElementById('lfMediaType').value = f.mediaType || '';
            document.getElementById('lfMediaUrl').value = f.mediaUrl || '';
            (f.questions || []).forEach(function (q) { addQuestion(q); });
        }

        // ── Wiring ─────────────────────────────────────────────────────
        timeMode.addEventListener('change', syncDuration);
        document.getElementById('lfAddQuestion').addEventListener('click', function () {
            addQuestion(null);
        });

        var submitting = false;
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            if (submitting) return;
            submitting = true;
            var btn = document.getElementById('lfSave');
            if (btn) btn.disabled = true;
            window.FcCommon.postJson(form.getAttribute('data-save-url'), collect())
                .then(function () {
                    window.location.href = form.getAttribute('data-list-url');
                })
                .catch(function (err) {
                    submitting = false;
                    if (btn) btn.disabled = false;
                    toast('error', err.message || 'Lưu bài test thất bại.');
                });
        });

        // ── Init ───────────────────────────────────────────────────────
        var data = readExamData();
        if (data) {
            hydrate(data);
        } else {
            addQuestion(null);
        }
        syncDuration();
        refreshEmptyHint();
    }

    window.LfForm = { mount: mount };
    ready(mount);
})();
