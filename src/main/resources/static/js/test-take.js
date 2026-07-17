/* Exam taking (Epic #11): left navigator + one-question panel, server-computed
 * countdown per time_mode, ~30s heartbeat, submit-all-at-once as one JSON payload,
 * and auto-submit when the countdown hits zero. Answers live in the DOM.
 * Also powers the lecturer read-only preview (no form / no submit).
 */
(function () {
    'use strict';

    var HEARTBEAT_MS = 30000;

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

    function pad(n) { return n < 10 ? '0' + n : '' + n; }

    function formatClock(totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        var h = Math.floor(totalSeconds / 3600);
        var m = Math.floor((totalSeconds % 3600) / 60);
        var s = totalSeconds % 60;
        return (h > 0 ? pad(h) + ':' : '') + pad(m) + ':' + pad(s);
    }

    /** Collects the student's answers from the DOM into the submit payload. */
    function collectAnswers(form) {
        var answers = [];
        var questions = form.querySelectorAll('.tk-question');
        questions.forEach(function (q) {
            var qid = parseInt(q.getAttribute('data-qid'), 10);
            var selected = [];
            q.querySelectorAll('.tk-option-input:checked').forEach(function (input) {
                selected.push(parseInt(input.value, 10));
            });
            answers.push({ questionId: qid, selectedOptionIds: selected });
        });
        return { answers: answers };
    }

    /**
     * Wires the left-rail navigator + prev/next for a question list.
     * Works for both the live take form and the lecturer preview shell.
     */
    function bindQuestionPager(root) {
        var questions = Array.prototype.slice.call(root.querySelectorAll('.tk-question'));
        var navItems = Array.prototype.slice.call(root.querySelectorAll('.tk-nav-item'));
        if (!questions.length) return { goTo: function () {}, refresh: function () {} };

        var prevBtn = root.querySelector('#tkPrev') || document.getElementById('tkPrev');
        var nextBtn = root.querySelector('#tkNext') || document.getElementById('tkNext');
        var progressEl = root.querySelector('#tkProgress') || document.getElementById('tkProgress');
        var current = 0;

        function isAnswered(qEl) {
            return !!qEl.querySelector('.tk-option-input:checked');
        }

        function refreshNavState() {
            var answered = 0;
            questions.forEach(function (q, i) {
                var answeredNow = isAnswered(q);
                if (answeredNow) answered += 1;
                var nav = navItems[i];
                if (!nav) return;
                nav.classList.toggle('is-active', i === current);
                nav.classList.toggle('is-answered', answeredNow);
            });
            if (progressEl) {
                // Keep "x/y" if the template already used that format; else just count.
                var total = questions.length;
                progressEl.textContent = answered + '/' + total;
            }
            if (prevBtn) prevBtn.disabled = current <= 0;
            if (nextBtn) nextBtn.disabled = current >= questions.length - 1;
        }

        function goTo(index) {
            if (index < 0 || index >= questions.length) return;
            questions.forEach(function (q, i) {
                var active = i === index;
                q.classList.toggle('is-active', active);
                // hidden attribute keeps non-active panels out of layout.
                if (active) q.removeAttribute('hidden');
                else q.setAttribute('hidden', 'hidden');
            });
            current = index;
            refreshNavState();
            // Keep the active question in view when switching from the left rail.
            questions[current].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }

        navItems.forEach(function (btn) {
            btn.addEventListener('click', function () {
                var idx = parseInt(btn.getAttribute('data-index'), 10);
                if (!isNaN(idx)) goTo(idx);
            });
        });

        if (prevBtn) {
            prevBtn.addEventListener('click', function () { goTo(current - 1); });
        }
        if (nextBtn) {
            nextBtn.addEventListener('click', function () { goTo(current + 1); });
        }

        // Mark answered state as the student picks options.
        root.addEventListener('change', function (e) {
            if (e.target && e.target.classList.contains('tk-option-input')) {
                refreshNavState();
            }
        });

        goTo(0);
        return { goTo: goTo, refresh: refreshNavState };
    }

    ready(function () {
        var form = document.getElementById('tkForm');
        var previewRoot = document.getElementById('tkPreview');

        // Lecturer preview: only the navigator, no submit / timer / heartbeat.
        if (!form && previewRoot) {
            bindQuestionPager(previewRoot);
            return;
        }
        if (!form) return;

        bindQuestionPager(form);

        var attemptId = form.getAttribute('data-attempt-id');
        var remaining = parseInt(form.getAttribute('data-remaining'), 10);
        var submitUrl = form.getAttribute('data-submit-url');
        var heartbeatUrl = form.getAttribute('data-heartbeat-url');
        var resultBase = form.getAttribute('data-result-base');
        var timerValue = document.getElementById('tkTimerValue');
        var submitBtn = document.getElementById('tkSubmit');

        var submitting = false;

        function goToResult() {
            window.location.href = resultBase + '/' + attemptId;
        }

        function doSubmit() {
            if (submitting) return;
            submitting = true;
            if (submitBtn) submitBtn.disabled = true;
            window.FcCommon.postJson(submitUrl, collectAnswers(form))
                .then(function () {
                    goToResult();
                })
                .catch(function (err) {
                    submitting = false;
                    if (submitBtn) submitBtn.disabled = false;
                    toast('error', err.message || 'Nộp bài thất bại, vui lòng thử lại.');
                });
        }

        form.addEventListener('submit', function (e) {
            e.preventDefault();
            doSubmit();
        });

        // Countdown + auto-submit only when the exam has a timer (remaining >= 0).
        if (remaining >= 0) {
            var left = remaining;
            if (timerValue) timerValue.textContent = formatClock(left);
            var tick = setInterval(function () {
                left -= 1;
                if (timerValue) timerValue.textContent = formatClock(left);
                if (left <= 0) {
                    clearInterval(tick);
                    if (!submitting) {
                        toast('info', 'Hết giờ — bài của bạn đang được nộp tự động.');
                        doSubmit();
                    }
                }
            }, 1000);
        }

        // Lightweight heartbeat so the lecturer monitor sees activity.
        if (heartbeatUrl) {
            setInterval(function () {
                if (submitting) return;
                window.FcCommon.postJson(heartbeatUrl, {}).catch(function () {
                    // A missed heartbeat is non-fatal; the next tick retries.
                });
            }, HEARTBEAT_MS);
        }
    });
})();
