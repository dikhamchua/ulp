/* Exam mode (READING/MEDIA) + description Quill wiring for lecturer form.
 * Depends on window.LfQuill. Used by test-lecturer-form.js.
 */
(function () {
    'use strict';

    /**
     * Binds reading/media mode UI and description editor for one form mount.
     * @param {object} opts DOM refs + imageUrl
     */
    function create(opts) {
        var mediaBlock = opts.mediaBlock;
        var readingBlock = opts.readingBlock;
        var mediaTypeEl = opts.mediaTypeEl;
        var mediaUrlEl = opts.mediaUrlEl;
        var modeReading = opts.modeReading;
        var modeMedia = opts.modeMedia;
        var modeReadingCard = opts.modeReadingCard;
        var modeMediaCard = opts.modeMediaCard;
        var timeMode = opts.timeMode;
        var durationWrap = opts.durationWrap;
        var descHost = opts.descHost;
        var descHidden = opts.descHidden;
        var imageUrl = opts.imageUrl;
        var descriptionQuill = null;
        // Remember last media type when switching READING ↔ MEDIA so the lecturer
        // does not lose the selection if they toggle by mistake.
        var lastMediaType = 'YOUTUBE';

        function syncDuration() {
            durationWrap.style.display = timeMode.value === 'INDIVIDUAL' ? '' : 'none';
        }

        function isMediaMode() {
            return !!(modeMedia && modeMedia.checked);
        }

        function setExamMode(mode) {
            var media = mode === 'MEDIA';
            if (modeReading) modeReading.checked = !media;
            if (modeMedia) modeMedia.checked = media;
            if (modeReadingCard) modeReadingCard.classList.toggle('is-selected', !media);
            if (modeMediaCard) modeMediaCard.classList.toggle('is-selected', media);
            if (readingBlock) {
                if (media) readingBlock.setAttribute('hidden', 'hidden');
                else readingBlock.removeAttribute('hidden');
            }
            if (mediaBlock) {
                if (media) mediaBlock.removeAttribute('hidden');
                else mediaBlock.setAttribute('hidden', 'hidden');
            }
            if (media && mediaTypeEl) {
                // Ensure a concrete media type is selected when entering media mode.
                if (!mediaTypeEl.value) mediaTypeEl.value = lastMediaType || 'YOUTUBE';
                lastMediaType = mediaTypeEl.value;
            }
            if (!media && mediaTypeEl && mediaTypeEl.value) {
                lastMediaType = mediaTypeEl.value;
            }
        }

        function syncExamModeFromFields() {
            // Existing exams with mediaType/mediaUrl open as MEDIA; otherwise READING.
            var hasMedia = !!(mediaTypeEl && mediaTypeEl.value) || !!(mediaUrlEl && mediaUrlEl.value.trim());
            setExamMode(hasMedia ? 'MEDIA' : 'READING');
        }

        function bind() {
            if (modeReading) {
                modeReading.addEventListener('change', function () {
                    if (modeReading.checked) setExamMode('READING');
                });
            }
            if (modeMedia) {
                modeMedia.addEventListener('change', function () {
                    if (modeMedia.checked) setExamMode('MEDIA');
                });
            }
            // Card click (label) already toggles radio; keep selected style in sync.
            if (modeReadingCard) {
                modeReadingCard.addEventListener('click', function () { setExamMode('READING'); });
            }
            if (modeMediaCard) {
                modeMediaCard.addEventListener('click', function () { setExamMode('MEDIA'); });
            }
            if (mediaTypeEl) {
                mediaTypeEl.addEventListener('change', function () {
                    if (mediaTypeEl.value) lastMediaType = mediaTypeEl.value;
                });
            }
            timeMode.addEventListener('change', syncDuration);
        }

        function mountDescriptionEditor(html) {
            if (!descHost || !descHidden) return;
            descriptionQuill = window.LfQuill.createQuill(
                descHost,
                descHidden,
                'Soạn nội dung bài đọc (có thể chèn ảnh, định dạng chữ)…',
                [
                    [{ header: [2, 3, false] }],
                    ['bold', 'italic', 'underline'],
                    [{ list: 'ordered' }, { list: 'bullet' }],
                    ['link', 'image'],
                    ['clean']
                ],
                imageUrl
            );
            if (!descriptionQuill) return;
            if (html) {
                var seed = html;
                // Legacy plain-text descriptions become a paragraph for Quill.
                if (seed && seed.indexOf('<') === -1) {
                    seed = '<p>' + seed + '</p>';
                }
                descriptionQuill.root.innerHTML = seed;
                descHidden.value = seed;
            }
        }

        function readDescriptionHtml() {
            if (descriptionQuill) return descriptionQuill.root.innerHTML;
            return descHidden ? descHidden.value : '';
        }

        function applyMediaFields(f) {
            if (mediaTypeEl) {
                mediaTypeEl.value = f.mediaType || '';
                if (f.mediaType) lastMediaType = f.mediaType;
            }
            if (mediaUrlEl) mediaUrlEl.value = f.mediaUrl || '';
        }

        return {
            bind: bind,
            syncDuration: syncDuration,
            isMediaMode: isMediaMode,
            setExamMode: setExamMode,
            syncExamModeFromFields: syncExamModeFromFields,
            mountDescriptionEditor: mountDescriptionEditor,
            readDescriptionHtml: readDescriptionHtml,
            applyMediaFields: applyMediaFields,
            descriptionQuill: function () { return descriptionQuill; }
        };
    }

    window.LfMode = { create: create };
})();
