/* Lecturer exam builder (Epic #11): add/remove questions + options, mark
 * correct (MCQ enforces a single correct client-side), toggle the duration
 * field by time_mode, and save the whole exam as one JSON payload on submit
 * (single submit orchestrator; deferred save). Question content uses Quill
 * rich text with server image upload. Field errors render inline; top-level
 * errors go through UlpToast.
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

    function isEmptyHtml(html) {
        if (!html) return true;
        // Image-only Quill payloads (e.g. <p><img src="..."></p>) are valid content.
        if (/<img\b[^>]*\bsrc\s*=/i.test(String(html))) return false;
        var text = String(html)
            .replace(/<[^>]*>/g, ' ')
            .replace(/&nbsp;/gi, ' ')
            .replace(/\u00a0/g, ' ')
            .trim();
        return text.length === 0;
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

    function waitForQuill(cb) {
        if (window.Quill) {
            cb();
            return;
        }
        var tries = 0;
        var t = setInterval(function () {
            tries += 1;
            if (window.Quill || tries > 80) {
                clearInterval(t);
                if (window.Quill) cb();
                else toast('error', 'Không tải được trình soạn thảo. Tải lại trang.');
            }
        }, 50);
    }

    function mount() {
        var form = document.getElementById('lfForm');
        if (!form) return;
        if (form.dataset.lfMounted === '1') return;
        form.dataset.lfMounted = '1';

        waitForQuill(function () {
            mountWithQuill(form);
        });
    }

    function mountWithQuill(form) {
        var qTpl = document.getElementById('lfQuestionTpl');
        var oTpl = document.getElementById('lfOptionTpl');
        var questionsHost = document.getElementById('lfQuestions');
        var noQuestions = document.getElementById('lfNoQuestions');
        var timeMode = document.getElementById('lfTimeMode');
        var durationWrap = document.getElementById('lfDurationWrap');
        var imageUrl = form.getAttribute('data-image-url') || '/lecturer/tests/images';
        var editId = null;

        function refreshEmptyHint() {
            var has = questionsHost.querySelectorAll('.lf-question').length > 0;
            if (noQuestions) noQuestions.style.display = has ? 'none' : '';
        }

        function syncDuration() {
            durationWrap.style.display = timeMode.value === 'INDIVIDUAL' ? '' : 'none';
        }

        function uploadImage(file) {
            var fd = new FormData();
            fd.append('file', file);
            return window.FcCommon.postForm(imageUrl, fd).then(function (res) {
                if (res && res.data && res.data.url) return res.data.url;
                throw new Error('Tải ảnh thất bại');
            });
        }

        function insertImageAtCursor(quill, url) {
            var range = quill.getSelection(true);
            var index = range ? range.index : quill.getLength();
            quill.insertEmbed(index, 'image', url, 'user');
            quill.setSelection(index + 1);
        }

        function imageHandler(quill) {
            var input = document.createElement('input');
            input.setAttribute('type', 'file');
            input.setAttribute('accept', 'image/jpeg,image/png,image/webp');
            input.click();
            input.onchange = function () {
                var file = input.files && input.files[0];
                if (!file) return;
                uploadImage(file)
                    .then(function (url) { insertImageAtCursor(quill, url); })
                    .catch(function (err) {
                        toast('error', err.message || 'Tải ảnh thất bại');
                    });
            };
        }

        function dataUrlToFile(dataUrl) {
            var parts = String(dataUrl).split(',');
            if (parts.length < 2) return null;
            var meta = parts[0];
            var mimeMatch = /data:(.*?);base64/i.exec(meta);
            var mime = (mimeMatch && mimeMatch[1]) || 'image/png';
            if (mime.indexOf('image/') !== 0) return null;
            try {
                var binary = atob(parts[1]);
                var len = binary.length;
                var bytes = new Uint8Array(len);
                for (var i = 0; i < len; i++) bytes[i] = binary.charCodeAt(i);
                var ext = mime === 'image/jpeg' ? 'jpg' : (mime === 'image/webp' ? 'webp' : 'png');
                return new File([bytes], 'paste.' + ext, { type: mime });
            } catch (err) {
                return null;
            }
        }

        // Replace any data:image... already in the editor with uploaded /uploads/... URLs.
        function rewriteDataImages(quill) {
            var imgs = quill.root.querySelectorAll('img[src^="data:image"]');
            if (!imgs.length) return Promise.resolve(true);
            var chain = Promise.resolve(true);
            Array.prototype.forEach.call(imgs, function (img) {
                chain = chain.then(function () {
                    var file = dataUrlToFile(img.getAttribute('src'));
                    if (!file) {
                        img.remove();
                        return true;
                    }
                    return uploadImage(file).then(function (url) {
                        img.setAttribute('src', url);
                        return true;
                    });
                });
            });
            return chain.catch(function (err) {
                toast('error', err.message || 'Tải ảnh thất bại');
                return false;
            });
        }

        // Paste/drop images go through the same upload endpoint (no huge data: URIs).
        function bindImagePaste(quill, hidden) {
            quill.root.addEventListener('paste', function (e) {
                var items = e.clipboardData && e.clipboardData.items;
                if (items) {
                    for (var i = 0; i < items.length; i++) {
                        var item = items[i];
                        if (!item.type || item.type.indexOf('image/') !== 0) continue;
                        e.preventDefault();
                        e.stopPropagation();
                        var file = item.getAsFile();
                        if (!file) return;
                        uploadImage(file)
                            .then(function (url) { insertImageAtCursor(quill, url); })
                            .catch(function (err) {
                                toast('error', err.message || 'Tải ảnh thất bại');
                            });
                        return;
                    }
                }
                // HTML paste may embed data:image; rewrite after Quill inserts it.
                setTimeout(function () {
                    rewriteDataImages(quill).then(function (ok) {
                        if (ok) hidden.value = quill.root.innerHTML;
                    });
                }, 0);
            });
        }

        function createQuill(host, hidden, placeholder, toolbar) {
            if (!host || !hidden || host.dataset.quillMounted === '1') return null;
            host.dataset.quillMounted = '1';
            var quill = new window.Quill(host, {
                theme: 'snow',
                placeholder: placeholder,
                modules: {
                    toolbar: {
                        container: toolbar,
                        handlers: {
                            image: function () {
                                imageHandler(quill);
                            }
                        }
                    }
                }
            });
            quill.on('text-change', function () {
                hidden.value = quill.root.innerHTML;
            });
            bindImagePaste(quill, hidden);
            return quill;
        }

        function mountQuestionEditor(qEl, html) {
            var host = qEl.querySelector('.lf-q-editor');
            var hidden = qEl.querySelector('.lf-q-content');
            var quill = createQuill(host, hidden, 'Nội dung câu hỏi (có thể chèn ảnh)', [
                [{ header: [2, 3, false] }],
                ['bold', 'italic', 'underline'],
                [{ list: 'ordered' }, { list: 'bullet' }],
                ['link', 'image'],
                ['clean']
            ]);
            if (!quill) return;
            if (html) {
                quill.root.innerHTML = html;
                hidden.value = html;
            }
            qEl._quill = quill;
        }

        function optionLetter(index) {
            // A..Z then fallback to number for unusually long option lists.
            return index < 26 ? String.fromCharCode(65 + index) : String(index + 1);
        }

        function refreshOptionLabels(qEl) {
            qEl.querySelectorAll('.lf-option').forEach(function (oEl, i) {
                var letter = oEl.querySelector('.lf-o-letter');
                if (letter) letter.textContent = optionLetter(i);
            });
        }

        function syncOptionCorrectState(oEl) {
            var checked = !!(oEl.querySelector('.lf-o-correct') || {}).checked;
            oEl.classList.toggle('is-correct', checked);
        }

        function mountOptionEditor(oEl, html) {
            var host = oEl.querySelector('.lf-o-editor');
            var hidden = oEl.querySelector('.lf-o-content');
            var quill = createQuill(host, hidden, 'Nội dung đáp án…', [
                ['bold', 'italic', 'underline'],
                ['link', 'image'],
                ['clean']
            ]);
            if (!quill) return;
            if (html) {
                // Plain legacy text becomes a paragraph so Quill/edit stays consistent.
                var seed = html;
                if (seed && seed.indexOf('<') === -1) {
                    seed = '<p>' + seed + '</p>';
                }
                quill.root.innerHTML = seed;
                hidden.value = seed;
            }
            oEl._quill = quill;

            // Compact UX: show formatting toolbar only while this answer is active.
            quill.root.addEventListener('focus', function () {
                oEl.classList.add('is-active');
            });
            quill.root.addEventListener('blur', function () {
                // Delay so toolbar clicks still count as interaction on this row.
                setTimeout(function () {
                    if (!oEl.contains(document.activeElement)) {
                        oEl.classList.remove('is-active');
                    }
                }, 120);
            });
            oEl.addEventListener('mousedown', function () {
                oEl.classList.add('is-active');
            });
        }

        function readEditorHtml(el, hiddenSelector, quillProp) {
            if (el[quillProp]) {
                return el[quillProp].root.innerHTML;
            }
            var hidden = el.querySelector(hiddenSelector);
            return hidden ? hidden.value : '';
        }

        function addOption(qEl, data) {
            var node = oTpl.content.firstElementChild.cloneNode(true);
            // Keep stable option ids so save can update in place after students submit.
            node.dataset.optionId = data && data.id != null ? String(data.id) : '';
            if (data) {
                node.querySelector('.lf-o-correct').checked = !!data.correct;
            }
            syncOptionCorrectState(node);
            var correctCb = node.querySelector('.lf-o-correct');
            correctCb.addEventListener('change', function () {
                if (qEl.querySelector('.lf-q-type').value === 'MCQ' && correctCb.checked) {
                    qEl.querySelectorAll('.lf-option').forEach(function (other) {
                        var cb = other.querySelector('.lf-o-correct');
                        if (cb && cb !== correctCb) {
                            cb.checked = false;
                            syncOptionCorrectState(other);
                        }
                    });
                }
                syncOptionCorrectState(node);
            });
            node.querySelector('.lf-o-remove').addEventListener('click', function () {
                node.remove();
                refreshOptionLabels(qEl);
            });
            qEl.querySelector('.lf-options').appendChild(node);
            mountOptionEditor(node, data ? (data.content || '') : '');
            refreshOptionLabels(qEl);
        }

        function addQuestion(data) {
            var node = qTpl.content.firstElementChild.cloneNode(true);
            // Keep stable question ids so save can update in place after students submit.
            node.dataset.questionId = data && data.id != null ? String(data.id) : '';
            questionsHost.appendChild(node);
            if (data) {
                node.querySelector('.lf-q-type').value = data.type || 'MCQ';
                node.querySelector('.lf-q-explanation').value = data.explanation || '';
                node.querySelector('.lf-q-points').value = data.points != null ? data.points : 1;
                mountQuestionEditor(node, data.content || '');
                (data.options || []).forEach(function (o) { addOption(node, o); });
            } else {
                mountQuestionEditor(node, '');
                addOption(node, null);
                addOption(node, null);
            }
            node.querySelector('.lf-add-option').addEventListener('click', function () {
                addOption(node, null);
            });
            node.querySelector('.lf-q-remove').addEventListener('click', function () {
                node.remove();
                refreshEmptyHint();
            });
            refreshEmptyHint();
        }

        function parseId(value) {
            if (value == null || value === '') return null;
            var n = Number(value);
            return Number.isFinite(n) ? n : null;
        }

        function collect() {
            var questions = [];
            questionsHost.querySelectorAll('.lf-question').forEach(function (qEl) {
                var options = [];
                qEl.querySelectorAll('.lf-option').forEach(function (oEl) {
                    options.push({
                        id: parseId(oEl.dataset.optionId),
                        content: readEditorHtml(oEl, '.lf-o-content', '_quill'),
                        correct: oEl.querySelector('.lf-o-correct').checked
                    });
                });
                questions.push({
                    id: parseId(qEl.dataset.questionId),
                    type: qEl.querySelector('.lf-q-type').value,
                    content: readEditorHtml(qEl, '.lf-q-content', '_quill'),
                    explanation: qEl.querySelector('.lf-q-explanation').value.trim(),
                    points: Number(qEl.querySelector('.lf-q-points').value || 1),
                    options: options
                });
            });
            return {
                id: editId,
                title: val('lfTitle'),
                description: val('lfDescription'),
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
                mediaType: val('lfMediaType') || null,
                mediaUrl: val('lfMediaUrl') || null,
                questions: questions
            };
        }

        function hydrate(f) {
            editId = f.id;
            document.getElementById('lfTitle').value = f.title || '';
            document.getElementById('lfDescription').value = f.description || '';
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
            document.getElementById('lfMediaType').value = f.mediaType || '';
            document.getElementById('lfMediaUrl').value = f.mediaUrl || '';
            (f.questions || []).forEach(function (q) { addQuestion(q); });
        }

        timeMode.addEventListener('change', syncDuration);
        document.getElementById('lfAddQuestion').addEventListener('click', function () {
            addQuestion(null);
        });

        function rewriteAllDataImages() {
            var editors = [];
            questionsHost.querySelectorAll('.lf-question').forEach(function (qEl) {
                if (qEl._quill) editors.push(qEl._quill);
                qEl.querySelectorAll('.lf-option').forEach(function (oEl) {
                    if (oEl._quill) editors.push(oEl._quill);
                });
            });
            var chain = Promise.resolve(true);
            editors.forEach(function (quill) {
                chain = chain.then(function (ok) {
                    if (!ok) return false;
                    return rewriteDataImages(quill);
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
                var emptyQ = payload.questions.some(function (q) { return isEmptyHtml(q.content); });
                if (emptyQ) {
                    toast('error', 'Nội dung câu hỏi không được để trống');
                    return;
                }
                var emptyO = payload.questions.some(function (q) {
                    return (q.options || []).some(function (o) { return isEmptyHtml(o.content); });
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
            addQuestion(null);
        }
        syncDuration();
        refreshEmptyHint();
    }

    window.LfForm = { mount: mount };
    ready(mount);
})();
