/* AI flashcard generator panel (ULP-12.4).
 *
 * Appends generated rows to the deck editor for review; it never saves. The save
 * path belongs entirely to the submit orchestrator in flashcard-deck-form.js, and
 * this module only reaches the editor through window.FcDeckEditor.addRow — so it
 * adds NO submit listener and cannot cause a double-submit race (per the
 * deferred-upload-on-save rule).
 *
 * NOTE: Do NOT read #flash-data here. notifications.js is the single owner of the
 * flash→toast drain; a second drain fires a duplicate toast — see
 * .claude/rules/flash-toast-drain.md
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

    ready(function () {
        var editor = window.FcDeckEditor;
        var panel = document.getElementById('fcAiPanel');
        var openBtn = document.getElementById('fcAiGenBtn');
        // Create mode has neither panel nor editor seam; nothing to wire up.
        if (!editor || !panel || !openBtn) return;

        var closeBtn = document.getElementById('fcAiClose');
        var genBtn = document.getElementById('fcAiGenerate');
        var textInput = document.getElementById('fcAiText');
        var fileInput = document.getElementById('fcAiFile');
        var countSelect = document.getElementById('fcAiCount');
        var languageSelect = document.getElementById('fcAiLanguage');
        var state = document.getElementById('fcAiState');
        var url = '/api/flashcards/' + editor.deckId + '/ai-generate';
        var busy = false;

        function setState(msg, isError) {
            if (!state) return;
            state.textContent = msg;
            state.classList.toggle('is-error', !!isError);
        }

        function setBusy(on) {
            busy = on;
            if (genBtn) genBtn.disabled = on;
        }

        openBtn.addEventListener('click', function () {
            panel.hidden = !panel.hidden;
            if (!panel.hidden && textInput) textInput.focus();
        });

        if (closeBtn) closeBtn.addEventListener('click', function () { panel.hidden = true; });

        if (genBtn) genBtn.addEventListener('click', function () {
            // Ignore repeat clicks while a call is in flight: a second generation
            // would append a second batch of rows over the first.
            if (busy) return;

            var file = fileInput && fileInput.files && fileInput.files[0];
            var text = textInput ? textInput.value.trim() : '';
            if (!file && !text) {
                setState('Vui lòng tải lên tài liệu hoặc dán nội dung để sinh thẻ', true);
                return;
            }

            var fd = new FormData();
            if (file) fd.append('file', file);
            if (text) fd.append('text', text);
            fd.append('count', countSelect ? countSelect.value : '20');
            if (languageSelect && languageSelect.value) fd.append('language', languageSelect.value);

            setBusy(true);
            setState('Đang sinh thẻ, quá trình này có thể mất vài chục giây…', false);

            window.FcCommon.postForm(url, fd)
                .then(function (res) {
                    var cards = (res.data && res.data.cards) || [];
                    cards.forEach(function (c) {
                        editor.addRow({ front: c.front, back: c.back });
                    });
                    setState('Đã thêm ' + cards.length + ' thẻ vào danh sách bên dưới.', false);
                    toast('success', 'Đã thêm ' + cards.length +
                        ' thẻ do AI sinh, kiểm tra rồi bấm Lưu');
                })
                .catch(function (err) {
                    var msg = err.message || 'Sinh thẻ bằng AI thất bại';
                    setState(msg, true);
                    toast('error', msg);
                })
                .then(function () { setBusy(false); });
        });
    });
})();
