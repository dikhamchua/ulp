/* Lecturer exam form orchestration (Epic #11): hydrate/collect/submit.
 * Depends on window.LfQuill, window.LfBuilder, window.LfMode.
 *
 * Exposes window.LfForm.mount() so the AJAX tab orchestrator
 * (test-detail-tabs.js) can (re)initialise the builder after swapping the
 * #tabPanel content in place, without a full-page reload. The builder is a
 * no-op when #lfForm is absent (non-info tab), so mount() is safe to call
 * on every swap. Exam data is read from the #lfData JSON island (which lives
 * inside #tabPanel and therefore travels with each swap), not a global.
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

    function val(id) {
        var el = document.getElementById(id);
        return el ? el.value.trim() : '';
    }

    function numOrNull(id) {
        var v = val(id);
        return v === '' ? null : Number(v);
    }

    function toLocalInput(iso) {
        return iso ? String(iso).slice(0, 16) : '';
    }

    function readExamData() {
        var el = document.getElementById('lfData');
        if (!el) return null;
        var raw = el.textContent.trim();
        if (!raw || raw === 'null') return null;
        try {
            return JSON.parse(raw);
        } catch (e) {
            return null;
        }
    }

    function mount() {
        var form = document.getElementById('lfForm');
        if (!form) return;
        if (form.dataset.lfMounted === '1') return;
        form.dataset.lfMounted = '1';

        window.LfQuill.waitForQuill(function () {
            mountWithQuill(form);
        });
    }

    function mountWithQuill(form) {
        var questionsHost = document.getElementById('lfQuestions');
        var imageUrl = form.getAttribute('data-image-url') || '/lecturer/tests/images';
        var editId = null;

        var builder = window.LfBuilder.create({
            qTpl: document.getElementById('lfQuestionTpl'),
            oTpl: document.getElementById('lfOptionTpl'),
            questionsHost: questionsHost,
            noQuestions: document.getElementById('lfNoQuestions'),
            imageUrl: imageUrl
        });

        var mode = window.LfMode.create({
            mediaBlock: document.getElementById('lfMediaBlock'),
            readingBlock: document.getElementById('lfReadingBlock'),
            mediaTypeEl: document.getElementById('lfMediaType'),
            mediaUrlEl: document.getElementById('lfMediaUrl'),
            modeReading: document.getElementById('lfModeReading'),
            modeMedia: document.getElementById('lfModeMedia'),
            modeReadingCard: document.getElementById('lfModeReadingCard'),
            modeMediaCard: document.getElementById('lfModeMediaCard'),
            timeMode: document.getElementById('lfTimeMode'),
            durationWrap: document.getElementById('lfDurationWrap'),
            descHost: document.getElementById('lfDescriptionEditor'),
            descHidden: document.getElementById('lfDescription'),
            imageUrl: imageUrl
        });

        function collect() {
            return {
                id: editId,
                title: val('lfTitle'),
                // Reading mode stores the passage HTML; media mode keeps a plain note optional.
                description: window.LfQuill.isEmptyHtml(mode.readDescriptionHtml())
                    ? null
                    : mode.readDescriptionHtml(),
                classId: numOrNull('lfClass'),
                type: val('lfType'),
                status: val('lfStatus'),
                timeMode: val('lfTimeMode'),
                durationMinutes: numOrNull('lfDuration'),
                startAt: val('lfStartAt') || null,
                endAt: val('lfEndAt') || null,
                passingScore: numOrNull('lfPassing'),
                shuffleQuestions: document.getElementById('lfShuffleQ').checked,
                shuffleOptions: document.getElementById('lfShuffleO').checked,
                // Reading mode always clears media so backend stores null/null.
                mediaType: mode.isMediaMode() ? (val('lfMediaType') || null) : null,
                mediaUrl: mode.isMediaMode() ? (val('lfMediaUrl') || null) : null,
                questions: builder.collectQuestions()
            };
        }

        function hydrate(f) {
            editId = f.id;
            document.getElementById('lfTitle').value = f.title || '';
            if (f.classId != null) document.getElementById('lfClass').value = f.classId;
            if (f.type) document.getElementById('lfType').value = f.type;
            if (f.status) document.getElementById('lfStatus').value = f.status;
            if (f.timeMode) document.getElementById('lfTimeMode').value = f.timeMode;
            if (f.durationMinutes != null) document.getElementById('lfDuration').value = f.durationMinutes;
            document.getElementById('lfStartAt').value = toLocalInput(f.startAt);
            document.getElementById('lfEndAt').value = toLocalInput(f.endAt);
            if (f.passingScore != null) document.getElementById('lfPassing').value = f.passingScore;
            document.getElementById('lfShuffleQ').checked = !!f.shuffleQuestions;
            document.getElementById('lfShuffleO').checked = !!f.shuffleOptions;
            mode.applyMediaFields(f);
            mode.mountDescriptionEditor(f.description || '');
            mode.syncExamModeFromFields();
            (f.questions || []).forEach(function (q) { builder.addQuestion(q); });
        }

        mode.bind();
        document.getElementById('lfAddQuestion').addEventListener('click', function () {
            builder.addQuestion(null);
        });

        function rewriteAllDataImages() {
            var editors = [];
            var descQuill = mode.descriptionQuill();
            if (descQuill) editors.push(descQuill);
            builder.listQuills().forEach(function (q) { editors.push(q); });
            var chain = Promise.resolve(true);
            editors.forEach(function (quill) {
                chain = chain.then(function (ok) {
                    if (!ok) return false;
                    return window.LfQuill.rewriteDataImages(quill, imageUrl);
                });
            });
            return chain;
        }

        var submitting = false;
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            if (submitting) return;
            // Convert any leftover data:image embeds before collecting/saving.
            rewriteAllDataImages().then(function (ok) {
                if (!ok) return;
                var payload = collect();
                if (mode.isMediaMode()) {
                    if (!payload.mediaType) {
                        toast('error', 'Vui lòng chọn loại media');
                        return;
                    }
                    if (!payload.mediaUrl) {
                        toast('error', 'Vui lòng nhập URL media');
                        return;
                    }
                }
                var emptyQ = payload.questions.some(function (q) {
                    return window.LfQuill.isEmptyHtml(q.content);
                });
                if (emptyQ) {
                    toast('error', 'Nội dung câu hỏi không được để trống');
                    return;
                }
                var emptyO = payload.questions.some(function (q) {
                    return (q.options || []).some(function (o) {
                        return window.LfQuill.isEmptyHtml(o.content);
                    });
                });
                if (emptyO) {
                    toast('error', 'Nội dung đáp án không được để trống');
                    return;
                }
                var stillData = payload.questions.some(function (q) {
                    if (/data:image/i.test(q.content || '')) return true;
                    return (q.options || []).some(function (o) {
                        return /data:image/i.test(o.content || '');
                    });
                });
                if (stillData) {
                    toast('error', 'Ảnh dán chưa tải lên xong. Dùng nút ảnh hoặc thử lại.');
                    return;
                }
                submitting = true;
                var btn = document.getElementById('lfSave');
                if (btn) btn.disabled = true;
                window.FcCommon.postJson(form.getAttribute('data-save-url'), payload)
                    .then(function () {
                        window.location.href = form.getAttribute('data-list-url');
                    })
                    .catch(function (err) {
                        submitting = false;
                        if (btn) btn.disabled = false;
                        toast('error', err.message || 'Lưu bài test thất bại.');
                    });
            });
        });

        var data = readExamData();
        if (data) {
            hydrate(data);
        } else {
            // Create mode defaults to reading passage + empty question set.
            mode.mountDescriptionEditor('');
            mode.setExamMode('READING');
            builder.addQuestion(null);
        }
        mode.syncDuration();
        builder.refreshEmptyHint();
    }

    window.LfForm = { mount: mount };
    ready(mount);
})();
