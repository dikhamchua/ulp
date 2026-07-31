/**
 * Share modal on the deck-detail page (owner only).
 *
 * Every control acts on its own the moment it is used — there is no submit
 * button and no page reload. Each toggle POSTs or DELETEs its own endpoint and
 * reverts itself if the server refuses, so the UI can never claim a share that
 * did not happen.
 *
 * NOTE (.claude/rules/flash-toast-drain.md): this file must never read the
 * server flash payload. notifications.js is the single owner of that drain; a
 * second drain shows every toast twice. All feedback here comes from live AJAX
 * results and goes straight to window.UlpToast.
 */
(function () {
    'use strict';

    var dialog = document.getElementById('fcShareDialog');
    if (!dialog) return;

    var deckId = dialog.getAttribute('data-deck-id');
    var apiBase = '/api/flashcards/' + deckId;

    var linkBox = document.getElementById('fcShareLinkBox');
    var linkInput = document.getElementById('fcShareLinkInput');
    var publicToggle = document.getElementById('fcPublicToggle');

    /**
     * Escapes text before it reaches UlpToast. iziToast injects `message` as
     * HTML, so a class name — which an owner controls — must be neutralised
     * here rather than trusted.
     */
    function esc(s) {
        var d = document.createElement('div');
        d.textContent = s == null ? '' : String(s);
        return d.innerHTML;
    }

    /** Calls an endpoint and resolves the AjaxResult envelope; rejects on !ok. */
    function send(method, url) {
        var headers = {};
        // CSRF lookup is centralised in flashcard-common.js, loaded before this file.
        var c = window.FcCommon.csrfHeader();
        if (c && c.token) headers[c.header] = c.token;
        return fetch(url, { method: method, headers: headers })
            .then(function (res) {
                return res.json().catch(function () { return { ok: false }; })
                    .then(function (data) {
                        if (!res.ok || !data.ok) {
                            throw new Error(data.message || 'Có lỗi xảy ra, vui lòng thử lại');
                        }
                        return data;
                    });
            });
    }

    // ── Open / close ────────────────────────────────────────────────────

    function open() {
        if (typeof dialog.showModal === 'function') dialog.showModal();
        else dialog.setAttribute('open', '');
    }

    function close() {
        if (typeof dialog.close === 'function' && dialog.open) dialog.close();
        else dialog.removeAttribute('open');
    }

    Array.prototype.forEach.call(document.querySelectorAll('.fc-share-open'),
        function (btn) { btn.addEventListener('click', open); });

    var closeBtn = document.getElementById('fcShareClose');
    if (closeBtn) closeBtn.addEventListener('click', close);

    // Click on the backdrop (the dialog element itself) closes the modal.
    dialog.addEventListener('click', function (e) {
        if (e.target === dialog) close();
    });

    // ── Class rows ──────────────────────────────────────────────────────

    /** Shares/unshares one class; reverts the switch when the server refuses. */
    function bindClassRow(row) {
        var toggle = row.querySelector('.fc-share-toggle');
        if (!toggle) return;
        var classId = row.getAttribute('data-class-id');
        var name = row.getAttribute('data-class-name');

        toggle.addEventListener('change', function () {
            var turningOn = toggle.checked;
            var url = apiBase + '/share/class/' + classId;
            toggle.disabled = true;
            row.classList.add('is-pending');

            send(turningOn ? 'POST' : 'DELETE', url)
                .then(function () {
                    if (turningOn) {
                        // Class name is owner-controlled data — escape it.
                        window.UlpToast.success('Đã chia sẻ với lớp ' + esc(name));
                        return;
                    }
                    // Label is a constant literal; never interpolate user data there.
                    window.UlpToast.action('Đã gỡ chia sẻ khỏi lớp ' + esc(name), {
                        label: 'Hoàn tác',
                        onAction: function () {
                            send('POST', url).then(function () {
                                toggle.checked = true;
                                window.UlpToast.success('Đã chia sẻ với lớp ' + esc(name));
                            }).catch(function (err) {
                                window.UlpToast.error(esc(err.message));
                            });
                        }
                    });
                })
                .catch(function (err) {
                    toggle.checked = !turningOn;
                    window.UlpToast.error(esc(err.message));
                })
                .then(function () {
                    toggle.disabled = false;
                    row.classList.remove('is-pending');
                });
        });
    }

    var classRows = Array.prototype.slice.call(
        dialog.querySelectorAll('.fc-share-row[data-class-id]'));

    classRows.forEach(bindClassRow);

    // ── Class filter ────────────────────────────────────────────────────

    /** Combining diacritical marks (U+0300-U+036F), escaped to keep this file ASCII. */
    var COMBINING_MARKS = /[\u0300-\u036f]/g;

    /**
     * Folds a string to a diacritic-free lowercase form so "lop" matches "Lớp".
     *
     * NFD splits a base letter from its combining marks, which the range strip
     * then drops. đ/Đ has no decomposition, so it is mapped by hand. Engines
     * without normalize fall back to a plain lowercase compare.
     *
     * @param {string} s raw text
     * @returns {string} comparable form
     */
    function fold(s) {
        var out = (s == null ? '' : String(s)).toLowerCase();
        out = out.replace(/đ/g, 'd');
        if (typeof out.normalize !== 'function') return out;
        return out.normalize('NFD').replace(COMBINING_MARKS, '');
    }

    /**
     * Hides class rows whose name does not contain the query, and surfaces the
     * no-match note. Purely visual: toggle state and server state are untouched,
     * so a hidden row keeps whatever share value it already had.
     *
     * @param {string} query raw user input; empty restores every row
     */
    function filterClassRows(query) {
        var needle = fold(query).trim();
        var visible = 0;

        classRows.forEach(function (row) {
            // data-class-name is authoritative; textContent would drag in markup.
            var hit = !needle || fold(row.getAttribute('data-class-name')).indexOf(needle) !== -1;
            row.hidden = !hit;
            if (hit) visible++;
        });

        if (noMatch) noMatch.hidden = visible > 0;
    }

    var searchInput = document.getElementById('fcClassSearch');
    var noMatch = document.getElementById('fcClassNoMatch');

    if (searchInput) {
        // 'input' also covers paste and the native clear button on type=search.
        searchInput.addEventListener('input', function () {
            filterClassRows(searchInput.value);
        });
    }

    // ── Public link ─────────────────────────────────────────────────────

    /** Shows the URL box and fills it; hides it when the link is switched off. */
    function showLink(url) {
        if (!linkBox) return;
        if (url) linkInput.value = url;
        linkBox.hidden = false;
    }

    function hideLink() {
        if (linkBox) linkBox.hidden = true;
    }

    // The token only reaches the page for the owner; absent until first enabled.
    var initialToken = dialog.getAttribute('data-share-token');
    if (initialToken && linkInput) {
        linkInput.value = window.location.origin + '/s/' + initialToken;
    }

    if (publicToggle) {
        publicToggle.addEventListener('change', function () {
            var turningOn = publicToggle.checked;
            publicToggle.disabled = true;

            send(turningOn ? 'POST' : 'DELETE', apiBase + '/share/public')
                .then(function (data) {
                    if (turningOn) {
                        showLink(data.data);
                        window.UlpToast.success('Đã bật link công khai');
                    } else {
                        hideLink();
                        window.UlpToast.success('Đã tắt link công khai');
                    }
                })
                .catch(function (err) {
                    publicToggle.checked = !turningOn;
                    window.UlpToast.error(esc(err.message));
                })
                .then(function () { publicToggle.disabled = false; });
        });
    }

    var copyBtn = document.getElementById('fcShareCopy');
    if (copyBtn) {
        copyBtn.addEventListener('click', function () {
            if (!linkInput || !linkInput.value) return;
            var done = function () { window.UlpToast.success('Đã sao chép link'); };
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(linkInput.value).then(done, function () {
                    window.UlpToast.error('Không sao chép được, hãy copy thủ công');
                });
                return;
            }
            // Fallback for browsers without the async clipboard API.
            linkInput.select();
            try { document.execCommand('copy'); done(); }
            catch (e) { window.UlpToast.error('Không sao chép được, hãy copy thủ công'); }
        });
    }

    var regenBtn = document.getElementById('fcShareRegen');
    if (regenBtn) {
        regenBtn.addEventListener('click', function () {
            regenBtn.disabled = true;
            send('POST', apiBase + '/share/public/regenerate')
                .then(function (data) {
                    showLink(data.data);
                    // Regenerating also switches the link on server-side.
                    if (publicToggle) publicToggle.checked = true;
                    window.UlpToast.success('Đã tạo link mới, link cũ không dùng được nữa');
                })
                .catch(function (err) { window.UlpToast.error(esc(err.message)); })
                .then(function () { regenBtn.disabled = false; });
        });
    }
})();
