/* Lesson content-type form behavior (add-lesson-content-types, Sprint 3).
 *
 * Responsibilities:
 *   1. Toggle visibility of the RICHTEXT / PDF / VIDEO sub-sections to
 *      match the currently selected type.
 *   2. Wire the PDF upload + MP4 upload + URL-set buttons to their
 *      respective content endpoints (XHR with progress for files, fetch
 *      for the URL endpoint).
 *   3. Pop the confirm modal when the lecturer attempts to switch the
 *      type for a persisted lesson — block the form submit until the
 *      confirmation is acknowledged.
 *
 * Notifications use window.UlpToast per project rule (no alert/inline).
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

    function getOriginalType() {
        var script = document.querySelector('script[data-original-type]');
        return script ? (script.getAttribute('data-original-type') || 'RICHTEXT') : 'RICHTEXT';
    }

    function getCsrfHeader() {
        var meta = document.querySelector('meta[name="_csrf"]');
        var headerMeta = document.querySelector('meta[name="_csrf_header"]');
        var hidden = document.querySelector('input[name="_csrf"]');
        var token = meta ? meta.getAttribute('content')
                 : (hidden ? hidden.value : null);
        var header = headerMeta ? headerMeta.getAttribute('content') : 'X-CSRF-TOKEN';
        return token ? { header: header, token: token } : null;
    }

    function csrfBodyPair() {
        var hidden = document.querySelector('input[name="_csrf"]');
        if (!hidden) return null;
        return { name: hidden.name, value: hidden.value };
    }

    function toast(kind, message) {
        if (window.UlpToast && typeof window.UlpToast[kind] === 'function') {
            window.UlpToast[kind](message);
        } else {
            // Non-fatal fallback so the user still sees the message.
            console.log('[' + kind + '] ' + message);
        }
    }

    function applySectionVisibility(activeType) {
        var sections = document.querySelectorAll('[data-content-type-section]');
        sections.forEach(function (s) {
            var t = s.getAttribute('data-content-type-section');
            s.classList.toggle('is-hidden', t !== activeType);
        });
    }

    function bindTypePicker() {
        var radios = document.querySelectorAll('input[type="radio"][name="contentType"]');
        if (!radios.length) return;
        var current = document.querySelector(
                'input[type="radio"][name="contentType"]:checked');
        applySectionVisibility(current ? current.value : 'RICHTEXT');
        radios.forEach(function (r) {
            r.addEventListener('change', function () {
                applySectionVisibility(r.value);
            });
        });
    }

    function bindVideoProviderToggle() {
        var providerInput = document.querySelector('input[type="hidden"][name="videoProvider"]');
        var radios = document.querySelectorAll('input[type="radio"][name="videoProviderUi"]');
        if (!radios.length) return;
        function syncBlocks(provider) {
            if (providerInput) providerInput.value = provider;
            var urlBlock = document.querySelector('.lct-video-url-block');
            var uploadBlock = document.querySelector('.lct-video-upload-block');
            var isUpload = provider === 'UPLOAD';
            if (urlBlock) urlBlock.classList.toggle('is-hidden', isUpload);
            if (uploadBlock) uploadBlock.classList.toggle('is-hidden', !isUpload);
        }
        var initial = document.querySelector(
                'input[type="radio"][name="videoProviderUi"]:checked');
        syncBlocks(initial ? initial.value : 'YOUTUBE');
        radios.forEach(function (r) {
            r.addEventListener('change', function () { syncBlocks(r.value); });
        });
    }

    function bindPdfUpload() {
        var btn = document.getElementById('lessonPdfUploadBtn');
        var input = document.getElementById('lessonPdfInput');
        if (!btn || !input) return;
        var section = btn.closest('[data-content-type-section="PDF"]');
        var url = section ? section.getAttribute('data-upload-url') : null;
        if (!url) return;
        btn.addEventListener('click', function () {
            if (!input.files || !input.files.length) {
                toast('warning', 'Vui lòng chọn tệp PDF');
                return;
            }
            var form = new FormData();
            form.append('file', input.files[0]);
            var csrf = csrfBodyPair();
            if (csrf) form.append(csrf.name, csrf.value);
            fetch(url, { method: 'POST', body: form, credentials: 'same-origin' })
                .then(function (r) { return r.json().then(function (b) { return [r, b]; }); })
                .then(function (parts) {
                    var r = parts[0];
                    var body = parts[1];
                    if (r.ok) {
                        toast('success', 'Đã tải PDF lên');
                        var status = document.getElementById('lessonPdfStatus');
                        if (status && body && body.pdfFilename) {
                            status.textContent = 'Đang dùng: ' + body.pdfFilename;
                        }
                    } else {
                        toast('error', (body && body.message) || 'Tải PDF thất bại');
                    }
                })
                .catch(function () { toast('error', 'Lỗi mạng khi tải PDF'); });
        });
    }

    function bindVideoUrlSave() {
        var btn = document.getElementById('lessonVideoUrlSaveBtn');
        if (!btn) return;
        var section = btn.closest('[data-content-type-section="VIDEO"]');
        var url = section ? section.getAttribute('data-video-url-url') : null;
        if (!url) return;
        btn.addEventListener('click', function () {
            var urlInput = document.getElementById('lessonVideoUrlInput');
            var providerInput = document.querySelector('input[type="hidden"][name="videoProvider"]');
            if (!urlInput || !providerInput) return;
            var body = new URLSearchParams();
            body.set('provider', providerInput.value || 'YOUTUBE');
            body.set('url', urlInput.value || '');
            var csrf = csrfBodyPair();
            if (csrf) body.set(csrf.name, csrf.value);
            fetch(url, {
                method: 'POST',
                body: body.toString(),
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
            })
                .then(function (r) { return r.json().then(function (b) { return [r, b]; }); })
                .then(function (parts) {
                    var r = parts[0];
                    var body = parts[1];
                    if (r.ok) {
                        toast('success', 'Đã lưu URL video');
                    } else {
                        toast('error', (body && body.message) || 'URL video không hợp lệ');
                    }
                })
                .catch(function () { toast('error', 'Lỗi mạng khi lưu URL'); });
        });
    }

    function bindVideoUpload() {
        var btn = document.getElementById('lessonVideoUploadBtn');
        var input = document.getElementById('lessonVideoInput');
        var progress = document.getElementById('lessonVideoProgress');
        if (!btn || !input) return;
        var section = btn.closest('[data-content-type-section="VIDEO"]');
        var url = section ? section.getAttribute('data-video-upload-url') : null;
        if (!url) return;
        btn.addEventListener('click', function () {
            if (!input.files || !input.files.length) {
                toast('warning', 'Vui lòng chọn tệp MP4');
                return;
            }
            var form = new FormData();
            form.append('file', input.files[0]);
            var csrf = csrfBodyPair();
            if (csrf) form.append(csrf.name, csrf.value);
            var xhr = new XMLHttpRequest();
            xhr.open('POST', url);
            if (progress) progress.hidden = false;
            xhr.upload.addEventListener('progress', function (e) {
                if (progress && e.lengthComputable) {
                    progress.value = (e.loaded / e.total) * 100;
                }
            });
            xhr.addEventListener('load', function () {
                if (progress) progress.hidden = true;
                if (xhr.status >= 200 && xhr.status < 300) {
                    toast('success', 'Đã tải MP4 lên');
                } else {
                    var body;
                    try { body = JSON.parse(xhr.responseText); } catch (e) { body = null; }
                    toast('error', (body && body.message) || 'Tải MP4 thất bại');
                }
            });
            xhr.addEventListener('error', function () {
                if (progress) progress.hidden = true;
                toast('error', 'Lỗi mạng khi tải MP4');
            });
            xhr.send(form);
        });
    }

    function bindConfirmModal() {
        var form = document.getElementById('lessonForm');
        var modal = document.getElementById('lessonTypeConfirmModal');
        if (!form || !modal) return;
        var confirmed = false;
        form.addEventListener('submit', function (e) {
            if (confirmed) return;
            var selected = document.querySelector(
                    'input[type="radio"][name="contentType"]:checked');
            var newType = selected ? selected.value : 'RICHTEXT';
            var original = getOriginalType();
            if (newType === original) return;
            // Block the submit and pop the modal. Confirm flips the flag
            // and re-submits programmatically.
            e.preventDefault();
            modal.hidden = false;
            var confirmBtn = modal.querySelector('[data-modal-confirm]');
            var cancelEls = modal.querySelectorAll('[data-modal-cancel]');
            function close() { modal.hidden = true; }
            cancelEls.forEach(function (el) {
                el.addEventListener('click', close, { once: true });
            });
            if (confirmBtn) {
                confirmBtn.addEventListener('click', function () {
                    confirmed = true;
                    close();
                    // submit() bypasses listeners so we dispatch a real
                    // submit event for any other handlers that wired up.
                    if (typeof form.requestSubmit === 'function') {
                        form.requestSubmit();
                    } else {
                        form.submit();
                    }
                }, { once: true });
            }
        });
    }

    ready(function () {
        bindTypePicker();
        bindVideoProviderToggle();
        bindPdfUpload();
        bindVideoUpload();
        bindVideoUrlSave();
        bindConfirmModal();
    });
})();
