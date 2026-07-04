/* Exam taking (Epic #11): server-computed countdown per time_mode, ~30s
 * heartbeat feeding live monitoring, submit-all-at-once as one JSON payload,
 * and auto-submit when the countdown hits zero. Answers are held in the DOM
 * (no per-answer save). All user notifications go through UlpToast.
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

    ready(function () {
        var form = document.getElementById('tkForm');
        if (!form) return;

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

        function doSubmit(isAuto) {
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
            doSubmit(false);
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
                        doSubmit(true);
                    }
                }
            }, 1000);
        }

        // Lightweight heartbeat (no answers) so the lecturer monitor sees activity.
        setInterval(function () {
            if (submitting) return;
            window.FcCommon.postJson(heartbeatUrl, {}).catch(function () {
                // A missed heartbeat is non-fatal; the next tick retries.
            });
        }, HEARTBEAT_MS);
    });
})();
