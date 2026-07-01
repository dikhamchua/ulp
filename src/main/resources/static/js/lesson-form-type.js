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

    /** Reads the active content type from the select dropdown, falling back
     *  to a checked radio for backward compatibility. */
    function getSelectedContentType() {
        var select = document.querySelector('select[name="contentType"]');
        if (select) return select.value || 'RICHTEXT';
        var radio = document.querySelector(
                'input[type="radio"][name="contentType"]:checked');
        return radio ? radio.value : 'RICHTEXT';
    }

    function bindTypePicker() {
        var select = document.querySelector('select[name="contentType"]');
        if (select) {
            applySectionVisibility(select.value || 'RICHTEXT');
            select.addEventListener('change', function () {
                applySectionVisibility(select.value);
            });
            return;
        }
        // Fallback: legacy radio-card picker.
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

    // Holds the PDF the lecturer picked but has not uploaded yet. Upload is
    // deferred until the main form is saved (see bindSubmitFlow).
    var pendingPdfFile = null;

    function bindPdfUpload() {
        var input = document.getElementById('lessonPdfInput');
        var drop = document.getElementById('lessonPdfDrop');
        if (!input || !drop) return;

        // Record the picked file + show its name — DO NOT upload yet.
        function selectFile(file) {
            if (!file) return;
            pendingPdfFile = file;
            var label = document.getElementById('lessonPdfSelected');
            if (label) {
                label.textContent = 'Đã chọn: ' + file.name
                        + ' — bấm "Lưu thay đổi" để tải lên';
                label.hidden = false;
            }
        }

        drop.addEventListener('click', function () { input.click(); });
        drop.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                input.click();
            }
        });
        input.addEventListener('change', function () {
            selectFile(input.files && input.files[0]);
            // Clear so re-picking the same filename fires change again; the
            // File object is already held in pendingPdfFile.
            input.value = '';
        });

        // Drag-and-drop mirrors the attachments uploader.
        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(function (ev) {
            drop.addEventListener(ev, function (e) {
                e.preventDefault();
                e.stopPropagation();
                drop.classList.toggle('is-dragover',
                        ev === 'dragenter' || ev === 'dragover');
            });
        });
        drop.addEventListener('drop', function (e) {
            var files = e.dataTransfer && e.dataTransfer.files;
            if (files && files.length) selectFile(files[0]);
        });
    }

    /**
     * Uploads the pending PDF (if any) then invokes next(ok). Resolves
     * immediately with true when there is nothing to upload — or the lesson
     * is no longer a PDF type — so the form save proceeds.
     */
    function uploadPendingPdf(next) {
        if (!pendingPdfFile || getSelectedContentType() !== 'PDF') {
            next(true);
            return;
        }
        var drop = document.getElementById('lessonPdfDrop');
        var section = drop ? drop.closest('[data-content-type-section="PDF"]') : null;
        var url = section ? section.getAttribute('data-upload-url') : null;
        if (!url) { next(true); return; }
        var progress = document.getElementById('lessonPdfProgress');
        var fd = new FormData();
        fd.append('file', pendingPdfFile);
        var csrf = csrfBodyPair();
        if (csrf) fd.append(csrf.name, csrf.value);
        var xhr = new XMLHttpRequest();
        xhr.open('POST', url);
        if (progress) { progress.hidden = false; progress.value = 0; }
        xhr.upload.addEventListener('progress', function (e) {
            if (progress && e.lengthComputable) {
                progress.value = (e.loaded / e.total) * 100;
            }
        });
        xhr.addEventListener('load', function () {
            if (progress) progress.hidden = true;
            if (xhr.status >= 200 && xhr.status < 300) {
                pendingPdfFile = null;
                next(true);
            } else {
                var body;
                try { body = JSON.parse(xhr.responseText); } catch (e) { body = null; }
                toast('error', (body && body.message) || 'Tải PDF thất bại');
                next(false);
            }
        });
        xhr.addEventListener('error', function () {
            if (progress) progress.hidden = true;
            toast('error', 'Lỗi mạng khi tải PDF');
            next(false);
        });
        xhr.send(fd);
    }

    // Holds the MP4 the lecturer picked but has not uploaded yet. Upload is
    // deferred until the main form is saved (see bindSubmitFlow).
    var pendingVideoFile = null;

    function bindVideoUpload() {
        var input = document.getElementById('lessonVideoInput');
        var drop = document.getElementById('lessonVideoDrop');
        if (!input || !drop) return;

        // Record the picked file + show its name — DO NOT upload yet.
        function selectFile(file) {
            if (!file) return;
            pendingVideoFile = file;
            var label = document.getElementById('lessonVideoSelected');
            if (label) {
                label.textContent = 'Đã chọn: ' + file.name
                        + ' — bấm "Lưu thay đổi" để tải lên';
                label.hidden = false;
            }
        }

        drop.addEventListener('click', function () { input.click(); });
        drop.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                input.click();
            }
        });
        input.addEventListener('change', function () {
            selectFile(input.files && input.files[0]);
            // Clear so re-picking the same filename fires change again; the
            // File object is already held in pendingVideoFile.
            input.value = '';
        });

        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(function (ev) {
            drop.addEventListener(ev, function (e) {
                e.preventDefault();
                e.stopPropagation();
                drop.classList.toggle('is-dragover',
                        ev === 'dragenter' || ev === 'dragover');
            });
        });
        drop.addEventListener('drop', function (e) {
            var files = e.dataTransfer && e.dataTransfer.files;
            if (files && files.length) selectFile(files[0]);
        });
    }

    /**
     * Uploads the pending MP4 (if any) then invokes next(ok). When there is
     * nothing to upload — or the lesson is no longer a VIDEO type — it resolves
     * immediately with true so the form save proceeds.
     */
    function uploadPendingVideo(next) {
        if (!pendingVideoFile || getSelectedContentType() !== 'VIDEO') {
            next(true);
            return;
        }
        var drop = document.getElementById('lessonVideoDrop');
        var url = drop ? drop.getAttribute('data-video-upload-url') : null;
        if (!url) { next(true); return; }
        var progress = document.getElementById('lessonVideoProgress');
        var fd = new FormData();
        fd.append('file', pendingVideoFile);
        var csrf = csrfBodyPair();
        if (csrf) fd.append(csrf.name, csrf.value);
        var xhr = new XMLHttpRequest();
        xhr.open('POST', url);
        if (progress) { progress.hidden = false; progress.value = 0; }
        xhr.upload.addEventListener('progress', function (e) {
            if (progress && e.lengthComputable) {
                progress.value = (e.loaded / e.total) * 100;
            }
        });
        xhr.addEventListener('load', function () {
            if (progress) progress.hidden = true;
            if (xhr.status >= 200 && xhr.status < 300) {
                pendingVideoFile = null;
                next(true);
            } else {
                var body;
                try { body = JSON.parse(xhr.responseText); } catch (e) { body = null; }
                toast('error', (body && body.message) || 'Tải MP4 thất bại');
                next(false);
            }
        });
        xhr.addEventListener('error', function () {
            if (progress) progress.hidden = true;
            toast('error', 'Lỗi mạng khi tải MP4');
            next(false);
        });
        xhr.send(fd);
    }

    /**
     * Single submit orchestrator: gate 1 confirms a content-type switch (if
     * any), gate 2 uploads the pending MP4, then the real submit proceeds.
     * Merging both gates into one handler avoids the double-submit races two
     * independent submit listeners would create.
     */
    function bindSubmitFlow() {
        var form = document.getElementById('lessonForm');
        if (!form) return;
        var modal = document.getElementById('lessonTypeConfirmModal');
        var proceeding = false;

        function confirmTypeSwitch(next) {
            if (!modal) { next(true); return; }
            if (getSelectedContentType() === getOriginalType()) { next(true); return; }
            var settled = false;
            function settle(ok) {
                if (settled) return;
                settled = true;
                modal.hidden = true;
                next(ok);
            }
            modal.hidden = false;
            var confirmBtn = modal.querySelector('[data-modal-confirm]');
            modal.querySelectorAll('[data-modal-cancel]').forEach(function (el) {
                el.addEventListener('click', function () { settle(false); }, { once: true });
            });
            if (confirmBtn) {
                confirmBtn.addEventListener('click', function () { settle(true); }, { once: true });
            }
        }

        form.addEventListener('submit', function (e) {
            // Once all gates clear we flip proceeding and let the native submit
            // (and the Quill content-copy listener) run untouched.
            if (proceeding) return;
            e.preventDefault();
            confirmTypeSwitch(function (okType) {
                if (!okType) return;
                // Gate 2 + 3: upload whichever media is pending. Each helper
                // no-ops unless its own content type is selected, so only one
                // actually uploads for a given lesson.
                uploadPendingVideo(function (okVideo) {
                    if (!okVideo) return;
                    uploadPendingPdf(function (okPdf) {
                        if (!okPdf) return;
                        proceeding = true;
                        if (typeof form.requestSubmit === 'function') {
                            form.requestSubmit();
                        } else {
                            form.submit();
                        }
                    });
                });
            });
        });
    }

    ready(function () {
        bindTypePicker();
        bindVideoProviderToggle();
        bindPdfUpload();
        bindVideoUpload();
        bindSubmitFlow();
    });
})();
