/* Flashcards — shared helpers (ULP-5.x).
 *
 * Provides window.FcCommon: CSRF header lookup, a UlpToast wrapper, and a JSON
 * fetch helper. Also drains #flash-data into toasts on page load so redirect
 * flash messages surface via UlpToast (per project notification rule).
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

    function toast(kind, message) {
        if (!message) return;
        if (window.UlpToast && typeof window.UlpToast[kind] === 'function') {
            window.UlpToast[kind](message);
        } else {
            console.log('[' + kind + '] ' + message);
        }
    }

    function csrfHeader() {
        var meta = document.querySelector('meta[name="_csrf"]');
        var headerMeta = document.querySelector('meta[name="_csrf_header"]');
        if (!meta || !headerMeta) return null;
        return { header: headerMeta.getAttribute('content'), token: meta.getAttribute('content') };
    }

    /** POST JSON and resolve the parsed AjaxResult envelope; rejects on !ok. */
    function postJson(url, body) {
        var headers = { 'Content-Type': 'application/json' };
        var csrf = csrfHeader();
        if (csrf && csrf.token) headers[csrf.header] = csrf.token;
        return fetch(url, {
            method: 'POST',
            headers: headers,
            body: JSON.stringify(body || {})
        }).then(function (res) {
            return res.json().catch(function () { return { ok: false }; })
                .then(function (data) {
                    if (res.ok && data && data.ok) return data;
                    var msg = (data && data.message) || 'Có lỗi xảy ra, vui lòng thử lại.';
                    throw new Error(msg);
                });
        });
    }

    /** POST multipart form-data (image upload); resolves the AjaxResult envelope. */
    function postForm(url, formData) {
        var headers = {};
        var csrf = csrfHeader();
        if (csrf && csrf.token) headers[csrf.header] = csrf.token;
        return fetch(url, { method: 'POST', headers: headers, body: formData })
            .then(function (res) {
                return res.json().catch(function () { return { ok: false }; })
                    .then(function (data) {
                        if (res.ok && data && data.ok) return data;
                        var msg = (data && data.message) || 'Tải ảnh thất bại';
                        throw new Error(msg);
                    });
            });
    }

    function drainFlash() {
        var el = document.getElementById('flash-data');
        if (!el) return;
        var map = { 'data-flash-success': 'success', 'data-flash-error': 'error',
            'data-flash-info': 'info', 'data-flash-warning': 'warning' };
        Object.keys(map).forEach(function (attr) {
            var val = el.getAttribute(attr);
            if (val && val !== 'null') toast(map[attr], val);
        });
    }

    window.FcCommon = { toast: toast, csrfHeader: csrfHeader,
        postJson: postJson, postForm: postForm };

    ready(drainFlash);
})();
