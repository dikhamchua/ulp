/* AI question generator panel on the lecturer exam edit screen (ULP-12.2).
 *
 * Two-step by design: "Sinh câu hỏi" only renders an unsaved preview, and
 * "Chèn câu đã chọn" posts the ticked indexes back so the server persists them.
 * Nothing reaches the exam until the lecturer confirms.
 *
 * Mounting mirrors test-lecturer-form.js: the panel lives inside #tabPanel, so
 * an AJAX tab swap replaces it. Rather than requiring the tab orchestrator to
 * know about this module, mount() is chained onto window.LfForm.mount — the
 * orchestrator already calls that on every swap. A per-element guard makes the
 * extra calls harmless.
 */
(function () {
    'use strict';

    var MSG_NO_MATERIAL = 'Vui lòng tải lên file PDF/DOCX hoặc dán nội dung tài liệu';
    var MSG_NO_SELECTION = 'Vui lòng chọn ít nhất một câu hỏi để chèn';
    var MSG_GENERATE_FAILED = 'Không sinh được câu hỏi, vui lòng thử lại';
    var MSG_CONFIRM_FAILED = 'Không chèn được câu hỏi vào bài test';

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

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function mount() {
        var panel = document.getElementById('lfAiGenPanel');
        var form = document.getElementById('lfForm');
        if (!panel || !form) return;
        if (panel.dataset.aiMounted === '1') return;
        panel.dataset.aiMounted = '1';

        var generateUrl = form.getAttribute('data-ai-generate-url') || '';
        var confirmUrl = form.getAttribute('data-ai-confirm-url') || '';
        var editUrl = form.getAttribute('data-edit-url') || '';

        var openBtn = document.getElementById('lfOpenAiGen');
        var closeBtn = document.getElementById('lfAiClose');
        var generateBtn = document.getElementById('lfAiGenerate');
        var confirmBtn = document.getElementById('lfAiConfirm');
        var selectAllBtn = document.getElementById('lfAiSelectAll');
        var confirmBar = document.getElementById('lfAiConfirmBar');
        var stateEl = document.getElementById('lfAiState');
        var resultsEl = document.getElementById('lfAiResults');
        var textEl = document.getElementById('lfAiText');
        var fileEl = document.getElementById('lfAiFile');

        var sessionId = null;

        function setState(msg) {
            if (stateEl) stateEl.textContent = msg;
        }

        function clearPreview() {
            sessionId = null;
            if (resultsEl) resultsEl.innerHTML = '';
            if (confirmBar) confirmBar.hidden = true;
        }

        /** The exam is read-only for new questions once students have answered. */
        function isLocked() {
            return form.dataset.questionBankLocked === '1';
        }

        function renderPreview(questions) {
            if (!resultsEl) return;
            resultsEl.innerHTML = questions.map(function (q, index) {
                var options = (q.options || []).map(function (opt) {
                    return '<li class="lf-bank-option' + (opt.correct ? ' is-correct' : '') + '">'
                        + escapeHtml(opt.content) + (opt.correct ? ' (Đúng)' : '') + '</li>';
                }).join('');
                var explanation = q.explanation
                    ? '<p class="lf-media-hint">Giải thích: ' + escapeHtml(q.explanation) + '</p>'
                    : '';
                return '<article class="lf-bank-item">'
                    + '<div class="lf-bank-item-head">'
                    + '<label class="lf-ai-pick">'
                    + '<input type="checkbox" class="lf-ai-check" value="' + index + '" checked>'
                    + '<span>'
                    + '<span class="lf-bank-meta"><span>' + escapeHtml(q.type || 'MCQ') + '</span></span>'
                    + '<span class="lf-bank-preview">' + escapeHtml(q.content) + '</span>'
                    + '</span>'
                    + '</label>'
                    + '</div>'
                    + '<ol class="lf-bank-options">' + options + '</ol>'
                    + explanation
                    + '</article>';
            }).join('');
            if (confirmBar) confirmBar.hidden = false;
            setState('Đã sinh ' + questions.length + ' câu hỏi. Bỏ tick những câu không muốn giữ rồi nhấn "Chèn câu đã chọn".');
        }

        function selectedIndexes() {
            var picked = [];
            resultsEl.querySelectorAll('.lf-ai-check').forEach(function (box) {
                if (box.checked) picked.push(Number(box.value));
            });
            return picked;
        }

        function generate() {
            if (!generateUrl) {
                toast('error', 'Lưu bài test trước khi sinh câu hỏi bằng AI.');
                return;
            }
            if (isLocked()) {
                toast('error', 'Bài test đã có bài nộp nên không thể thêm câu hỏi mới.');
                return;
            }
            var text = textEl ? textEl.value.trim() : '';
            var file = fileEl && fileEl.files.length ? fileEl.files[0] : null;
            if (!file && !text) {
                toast('error', MSG_NO_MATERIAL);
                return;
            }

            var payload = new FormData();
            if (file) payload.append('file', file);
            if (text) payload.append('text', text);
            payload.append('count', document.getElementById('lfAiCount').value);
            payload.append('type', document.getElementById('lfAiType').value);
            payload.append('difficulty', document.getElementById('lfAiDifficulty').value);

            clearPreview();
            generateBtn.disabled = true;
            setState('Đang sinh câu hỏi, quá trình này có thể mất một lúc...');

            // postForm carries the CSRF header and unwraps the AjaxResult envelope;
            // a raw fetch here would be rejected with 403 by the global CSRF filter.
            window.FcCommon.postForm(generateUrl, payload)
                .then(function (result) {
                    var preview = result.data || {};
                    generateBtn.disabled = false;
                    sessionId = preview.sessionId;
                    renderPreview(preview.questions || []);
                })
                .catch(function (err) {
                    generateBtn.disabled = false;
                    setState(err.message || MSG_GENERATE_FAILED);
                    toast('error', err.message || MSG_GENERATE_FAILED);
                });
        }

        function confirmSelection() {
            if (!sessionId || !confirmUrl) {
                toast('error', 'Phiên sinh câu hỏi đã hết hạn, vui lòng sinh lại.');
                return;
            }
            var indexes = selectedIndexes();
            if (!indexes.length) {
                toast('error', MSG_NO_SELECTION);
                return;
            }
            confirmBtn.disabled = true;
            window.FcCommon.postJson(confirmUrl, { sessionId: sessionId, indexes: indexes })
                .then(function () {
                    toast('success', 'Đã chèn ' + indexes.length + ' câu hỏi vào bài test.');
                    // Reload the info tab so the persisted questions appear in the builder.
                    window.location.href = editUrl ? (editUrl + '?tab=info') : window.location.href;
                })
                .catch(function (err) {
                    confirmBtn.disabled = false;
                    toast('error', err.message || MSG_CONFIRM_FAILED);
                });
        }

        if (openBtn) {
            openBtn.addEventListener('click', function () {
                if (isLocked()) {
                    toast('error', 'Bài test đã có bài nộp nên không thể thêm câu hỏi mới.');
                    return;
                }
                panel.hidden = false;
            });
        }
        if (closeBtn) {
            closeBtn.addEventListener('click', function () {
                panel.hidden = true;
            });
        }
        if (generateBtn) generateBtn.addEventListener('click', generate);
        if (confirmBtn) confirmBtn.addEventListener('click', confirmSelection);
        if (selectAllBtn) {
            selectAllBtn.addEventListener('click', function () {
                resultsEl.querySelectorAll('.lf-ai-check').forEach(function (box) {
                    box.checked = true;
                });
            });
        }
    }

    // Chain onto the form mount so an AJAX tab swap re-mounts this panel too,
    // without the tab orchestrator needing to know this module exists.
    if (window.LfForm && typeof window.LfForm.mount === 'function') {
        var formMount = window.LfForm.mount;
        window.LfForm.mount = function () {
            formMount.apply(null, arguments);
            mount();
        };
    }

    window.LfAiQuestions = { mount: mount };
    ready(mount);
})();
