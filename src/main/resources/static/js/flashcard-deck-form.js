/* Flashcard deck editor (ULP-5.x).
 *
 * Quizlet-style, text-only card rows: add / remove / reorder. The SINGLE submit
 * orchestrator validates the rows, saves the card set via AJAX, then lets the
 * native metadata POST run (requestSubmit). One submit listener only, so there
 * is no double-submit race (per deferred-upload-on-save rule).
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
        var form = document.getElementById('deckForm');
        if (!form) return;
        var deckId = form.getAttribute('data-deck-id');
        var list = document.getElementById('fcCardList');
        // Create mode (no deckId / no card list): plain metadata submit.
        if (!deckId || !list) return;

        var template = document.getElementById('fcRowTemplate');
        var emptyBox = document.getElementById('fcCardsEmpty');
        var addBtn = document.getElementById('fcAddCard');
        var cardsUrl = '/api/flashcards/' + deckId + '/cards';
        var proceeding = false;

        function rows() {
            return Array.prototype.slice.call(list.querySelectorAll('.fc-card-row'));
        }

        function renumber() {
            rows().forEach(function (row, i) {
                var idx = row.querySelector('.fc-row-index');
                if (idx) idx.textContent = String(i + 1);
            });
            var empty = rows().length === 0;
            if (emptyBox) emptyBox.classList.toggle('is-hidden', !empty);
        }

        function bindRow(row) {
            var removeBtn = row.querySelector('.fc-remove-card');
            if (removeBtn) removeBtn.addEventListener('click', function () {
                row.remove();
                renumber();
            });
            var up = row.querySelector('.fc-move-up');
            if (up) up.addEventListener('click', function () {
                var prev = row.previousElementSibling;
                if (prev) { list.insertBefore(row, prev); renumber(); }
            });
            var down = row.querySelector('.fc-move-down');
            if (down) down.addEventListener('click', function () {
                var next = row.nextElementSibling;
                if (next) { list.insertBefore(next, row); renumber(); }
            });
        }

        // values (optional): { front, back } pre-fills a bulk-imported row and
        // suppresses auto-focus; omit it for the manual "Thêm thẻ" button.
        function addRow(values) {
            var frag = template.content.cloneNode(true);
            var row = frag.querySelector('.fc-card-row');
            list.appendChild(frag);
            bindRow(row);
            if (values) {
                var f = row.querySelector('.fc-front');
                var b = row.querySelector('.fc-back');
                if (f) f.value = values.front || '';
                if (b) b.value = values.back || '';
            }
            renumber();
            if (!values) {
                var front = row.querySelector('.fc-front');
                if (front) front.focus();
            }
            return row;
        }

        // ── Excel import: append parsed rows for review (no auto-save) ──
        function bindImport() {
            var importBtn = document.getElementById('fcImportBtn');
            var importInput = document.getElementById('fcImportInput');
            if (!importBtn || !importInput) return;
            var importUrl = '/api/flashcards/' + deckId + '/import';

            importBtn.addEventListener('click', function () { importInput.click(); });
            importInput.addEventListener('change', function () {
                var file = importInput.files && importInput.files[0];
                if (!file) return;
                var fd = new FormData();
                fd.append('file', file);
                window.FcCommon.postForm(importUrl, fd)
                    .then(function (res) {
                        var cards = (res.data && res.data.cards) || [];
                        cards.forEach(function (c) { addRow({ front: c.front, back: c.back }); });
                        toast('success', 'Đã thêm ' + cards.length +
                            ' thẻ từ Excel, kiểm tra rồi bấm Lưu');
                    })
                    .catch(function (err) {
                        toast('error', err.message || 'Import Excel thất bại');
                    })
                    // Reset the picker so re-selecting the same file re-triggers change.
                    .then(function () { importInput.value = ''; });
            });
        }

        // ── Save orchestrator ──────────────────────────────────────────
        function collectCards() {
            var cards = [];
            var invalid = false;
            rows().forEach(function (row) {
                var front = row.querySelector('.fc-front').value.trim();
                var back = row.querySelector('.fc-back').value.trim();
                row.classList.remove('is-invalid');
                if (!front || !back) { invalid = true; row.classList.add('is-invalid'); }
                var idAttr = row.getAttribute('data-card-id');
                cards.push({
                    id: idAttr ? Number(idAttr) : null,
                    front: front,
                    back: back
                });
            });
            return { cards: cards, invalid: invalid };
        }

        form.addEventListener('submit', function (e) {
            if (proceeding) return; // real submit: let native POST run
            e.preventDefault();

            var title = document.getElementById('deckTitle');
            if (title && !title.value.trim()) {
                toast('error', 'Tiêu đề không được để trống');
                return;
            }
            var collected = collectCards();
            if (collected.invalid) {
                toast('error', 'Mỗi thẻ phải có cả mặt trước và mặt sau');
                return;
            }

            // Save the card set, then submit the metadata form.
            window.FcCommon.postJson(cardsUrl, { cards: collected.cards })
                .then(function () {
                    proceeding = true;
                    if (typeof form.requestSubmit === 'function') form.requestSubmit();
                    else form.submit();
                })
                .catch(function (err) {
                    toast('error', err.message || 'Lưu bộ thẻ thất bại');
                });
        });

        // Bind existing rows + controls.
        rows().forEach(bindRow);
        // Wrap so the click event object is not misread as pre-fill values.
        if (addBtn) addBtn.addEventListener('click', function () { addRow(); });
        bindImport();
        renumber();
    });
})();
