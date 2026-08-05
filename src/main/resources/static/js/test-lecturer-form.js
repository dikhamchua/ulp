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
        var bankSearchUrl = form.getAttribute('data-bank-search-url') || '';
        // Create mode has no Test row yet, so the picker resolves its scope from
        // the selected class instead. The CLASS_ID token is substituted
        // client-side. It must not be written __CLASS_ID__: Thymeleaf reads
        // double-underscore pairs inside @{...} as its own preprocessing syntax
        // and strips them, so the token would never survive rendering.
        var bankClassSearchUrl = form.getAttribute('data-bank-class-search-url') || '';
        var bankClassChaptersUrl = form.getAttribute('data-bank-class-chapters-url') || '';
        var isCreateMode = form.getAttribute('data-bank-mode') === 'create';
        var editId = null;
        // Last rendered picker page, so create mode can rebuild a card from a
        // snapshot without a second round trip.
        var lastBankItems = [];

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
                questions: builder.collectQuestions(),
                questionBankLocked: form.dataset.questionBankLocked === '1'
            };
        }

        function hydrate(f) {
            editId = f.id;
            form.dataset.questionBankLocked = f.questionBankLocked ? '1' : '0';
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

        // Substitutes the selected class into a class-scoped picker URL. Returns
        // '' when no class is chosen so callers can stop before firing a request
        // that would still carry the literal CLASS_ID token.
        function withSelectedClass(template) {
            if (!template) return '';
            var classId = numOrNull('lfClass');
            if (classId == null) return '';
            return template.replace('CLASS_ID', encodeURIComponent(String(classId)));
        }

        function readBankSearchUrl() {
            // Edit mode keeps the test-scoped URL; create mode derives it from #lfClass.
            return isCreateMode ? withSelectedClass(bankClassSearchUrl) : bankSearchUrl;
        }

        // Selects the given class in the picker when it is one of the rendered
        // options; a stale/foreign id leaves the picker untouched.
        function preselectClass(rawId) {
            if (!rawId) return;
            var select = document.getElementById('lfClass');
            if (!select) return;
            var match = select.querySelector('option[value="' + rawId + '"]');
            if (match) select.value = rawId;
        }

        // Post-save landing page: the class tests tab when the exam belongs to a
        // class (read at save time, so switching the picker retargets), else the
        // global exam list. data-list-url is the server-rendered fallback.
        function listUrlFor(classId) {
            if (classId != null) return '/lecturer/classes/' + classId + '/tests';
            return form.getAttribute('data-list-url') || '/lecturer/tests';
        }

        function escapeHtml(value) {
            return String(value || '')
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/\"/g, '&quot;')
                .replace(/'/g, '&#39;');
        }

        function plainPreview(html) {
            var host = document.createElement('div');
            host.innerHTML = html || '';
            return (host.textContent || host.innerText || '').replace(/\s+/g, ' ').trim();
        }

        function isQuestionBankLocked() {
            return form.dataset.questionBankLocked === '1';
        }

        function bindQuestionAddButton(button, handler) {
            if (!button) return;
            button.addEventListener('click', function () {
                if (isQuestionBankLocked()) {
                    toast('error', 'Bài test đã có bài nộp nên không thể thêm câu hỏi mới từ ngân hàng.');
                    return;
                }
                handler();
            });
        }

        // Explains an empty result by cause instead of one generic line: a
        // filter that matched nothing, a class with no subject bound, or a
        // subject whose bank is genuinely empty. Inline empty-list UI — not a
        // notification, so it never goes through #flash-data or UlpToast.
        function renderBankEmptyState(scope) {
            var state = document.getElementById('lfBankState');
            var actions = document.getElementById('lfBankStateActions');
            if (!state) return;
            if (actions) actions.innerHTML = '';
            // Create mode before a class is picked: no scope exists to search yet.
            if (scope && scope.classMissing) {
                state.textContent = 'Chọn lớp trước để tải câu hỏi của môn tương ứng.';
                return;
            }
            if (scope && scope.chapterFilterApplied) {
                state.textContent = 'Không có câu hỏi khớp bộ lọc. Thử bỏ chương hoặc từ khoá.';
                if (actions) {
                    var clearBtn = document.createElement('button');
                    clearBtn.type = 'button';
                    clearBtn.className = 'tst-btn';
                    clearBtn.textContent = 'Xoá bộ lọc';
                    clearBtn.addEventListener('click', function () {
                        var chapter = document.getElementById('lfBankChapter');
                        var query = document.getElementById('lfBankQuery');
                        if (chapter) chapter.value = '';
                        if (query) query.value = '';
                        runBankSearch();
                    });
                    actions.appendChild(clearBtn);
                }
                return;
            }
            if (scope && scope.subjectBound === false) {
                state.textContent = 'Lớp này chưa được gán môn học nên chỉ tìm được câu hỏi chung của bộ môn. '
                    + 'Gán môn cho lớp để dùng đúng ngân hàng câu hỏi của môn đó.';
                if (actions && scope.classId) {
                    var classLink = document.createElement('a');
                    classLink.className = 'tst-btn';
                    classLink.href = '/lecturer/classes/' + encodeURIComponent(scope.classId) + '/edit';
                    classLink.textContent = 'Gán môn cho lớp';
                    actions.appendChild(classLink);
                }
                return;
            }
            var subject = scope && scope.subjectLabel ? scope.subjectLabel : '';
            state.textContent = subject
                ? ('Môn ' + subject + ' chưa có câu hỏi hoạt động nào trong ngân hàng.')
                : 'Chưa có câu hỏi hoạt động nào trong ngân hàng.';
            if (actions) {
                var bankLink = document.createElement('a');
                bankLink.className = 'tst-btn';
                bankLink.href = '/lecturer/question-bank';
                bankLink.textContent = 'Mở ngân hàng câu hỏi';
                actions.appendChild(bankLink);
            }
        }

        function renderBankResults(payload) {
            var host = document.getElementById('lfBankResults');
            var state = document.getElementById('lfBankState');
            var actions = document.getElementById('lfBankStateActions');
            if (!host || !state) return;
            var items = payload && payload.items ? payload.items : [];
            lastBankItems = items;
            if (!items.length) {
                host.innerHTML = '';
                renderBankEmptyState(payload ? payload.scope : null);
                return;
            }
            if (actions) actions.innerHTML = '';
            state.textContent = 'Chọn câu hỏi hoạt động (ngân hàng cá nhân hoặc ngân hàng bộ môn) để chèn snapshot vào bài test hiện tại.';
            host.innerHTML = items.map(function (item) {
                var preview = escapeHtml(plainPreview(item.content));
                var optionHtml = (item.options || []).map(function (opt) {
                    return '<li class="lf-bank-option' + (opt.correct ? ' is-correct' : '') + '">'
                        + escapeHtml(plainPreview(opt.content)) + (opt.correct ? ' (Đúng)' : '') + '</li>';
                }).join('');
                var sourceLabel = item.source === 'HEAD_BANK' ? 'Ngân hàng bộ môn' : 'Ngân hàng cá nhân';
                return '<article class="lf-bank-item">'
                    + '<div class="lf-bank-item-head">'
                    + '<div><div class="lf-bank-meta"><span>' + escapeHtml(item.subjectLabel || '—') + '</span>'
                    + '<span>' + escapeHtml(sourceLabel) + '</span>'
                    + '<span>' + escapeHtml(item.questionType || 'MCQ') + '</span></div>'
                    + '<div class="lf-bank-preview">' + preview + '</div></div>'
                    + '<button type="button" class="tst-btn lf-bank-add" data-bank-id="' + escapeHtml(String(item.id)) + '">Chèn vào đề</button>'
                    + '</div>'
                    + '<ol class="lf-bank-options">' + optionHtml + '</ol>'
                    + '</article>';
            }).join('');
            host.querySelectorAll('[data-bank-id]').forEach(function (btn) {
                btn.addEventListener('click', function () {
                    if (isQuestionBankLocked()) {
                        toast('error', 'Bài test đã có bài nộp nên không thể thêm câu hỏi mới từ ngân hàng.');
                        return;
                    }
                    insertIntoBuilder(Number(btn.getAttribute('data-bank-id')));
                });
            });
        }

        // Picking a bank question only builds a card client-side, in both create
        // and edit mode; the normal "Lưu bài test" persists it through the same
        // save path as a hand-typed question. Deliberately no server call — Save
        // is the only commit point (.claude/rules/deferred-upload-on-save.md).
        // Edit mode used to POST the snapshot and reload, committing the change
        // before the author had reviewed it.
        function insertIntoBuilder(itemId) {
            var snapshot = null;
            for (var i = 0; i < lastBankItems.length; i++) {
                if (Number(lastBankItems[i].id) === Number(itemId)) {
                    snapshot = lastBankItems[i];
                    break;
                }
            }
            if (!snapshot) {
                toast('error', 'Không tìm thấy câu hỏi vừa chọn, hãy tìm lại.');
                return;
            }
            // id: null marks the card as new so collectQuestions() sends it as an
            // insert. Type is omitted on purpose — the builder derives MCQ/MR from
            // the ticked options, same as for a hand-typed question.
            builder.addQuestion({
                id: null,
                content: snapshot.content,
                explanation: snapshot.explanation,
                points: 1,
                options: (snapshot.options || []).map(function (opt) {
                    return { id: null, content: opt.content, correct: opt.correct };
                })
            });
            toast('success', 'Đã thêm câu hỏi vào đề. Nhấn Lưu bài test để lưu.');
        }

        function runBankSearch() {
            var state = document.getElementById('lfBankState');
            var host = document.getElementById('lfBankResults');
            var actions = document.getElementById('lfBankStateActions');
            if (actions) actions.innerHTML = '';
            if (state) state.textContent = 'Đang tải câu hỏi ngân hàng hoạt động...';
            if (host) host.innerHTML = '';
            var base = readBankSearchUrl();
            if (!base) {
                // No class chosen yet: stop here so no request carries CLASS_ID.
                lastBankItems = [];
                renderBankEmptyState({ classMissing: true });
                return;
            }
            var url = new URL(base, window.location.origin);
            var chapterId = val('lfBankChapter');
            var query = val('lfBankQuery');
            if (chapterId) url.searchParams.set('chapterId', chapterId);
            if (query) url.searchParams.set('q', query);
            fetch(url.toString(), { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
                .then(function (res) {
                    return res.json().then(function (data) {
                        if (!res.ok || !data || !data.ok) {
                            throw new Error((data && data.message) || 'Không tải được danh sách câu hỏi cộng tác của bài test.');
                        }
                        // Envelope: { items, scope }. scope drives the empty state.
                        return data.data || { items: [], scope: null };
                    });
                })
                .then(renderBankResults)
                .catch(function (err) {
                    if (state) state.textContent = err.message || 'Không tải được danh sách câu hỏi cộng tác của bài test.';
                });
        }

        // Create mode renders an empty chapter list server-side because the class
        // is unknown then; this refills it from the class the author just picked.
        function loadBankChapters() {
            var select = document.getElementById('lfBankChapter');
            if (!select) return;
            var url = withSelectedClass(bankClassChaptersUrl);
            if (!url) {
                select.innerHTML = '<option value="">Tất cả chương</option>';
                return;
            }
            fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
                .then(function (res) {
                    return res.json().then(function (data) {
                        if (!res.ok || !data || !data.ok) throw new Error('');
                        return data.data || [];
                    });
                })
                .then(function (chapters) {
                    var html = '<option value="">Tất cả chương</option>';
                    chapters.forEach(function (ch) {
                        html += '<option value="' + escapeHtml(String(ch.id)) + '">'
                            + escapeHtml(ch.name || '') + '</option>';
                    });
                    select.innerHTML = html;
                })
                .catch(function () {
                    // Chapter filter is optional; a failure leaves "all chapters".
                    select.innerHTML = '<option value="">Tất cả chương</option>';
                });
        }

        function bindBankPicker() {
            var picker = document.getElementById('lfBankPicker');
            var openBtn = document.getElementById('lfOpenBankPicker');
            var closeBtn = document.getElementById('lfBankClose');
            var searchBtn = document.getElementById('lfBankSearch');
            if (!picker || !openBtn || !closeBtn || !searchBtn) return;
            closeBtn.addEventListener('click', function () {
                picker.hidden = true;
            });
            searchBtn.addEventListener('click', runBankSearch);
            if (isCreateMode) {
                var classSelect = document.getElementById('lfClass');
                if (classSelect) {
                    classSelect.addEventListener('change', function () {
                        loadBankChapters();
                        // Results belong to the previous class; drop them.
                        var host = document.getElementById('lfBankResults');
                        if (host) host.innerHTML = '';
                        lastBankItems = [];
                    });
                }
            }
            document.getElementById('lfBankQuery').addEventListener('keydown', function (e) {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    runBankSearch();
                }
            });
        }

        mode.bind();
        bindQuestionAddButton(document.getElementById('lfAddQuestion'), function () {
            builder.addQuestion(null);
        });
        bindQuestionAddButton(document.getElementById('lfOpenBankPicker'), function () {
            var picker = document.getElementById('lfBankPicker');
            if (picker) picker.hidden = false;
            // Create mode: chapters depend on the class, so refresh them on open.
            if (isCreateMode) loadBankChapters();
        });
        bindBankPicker();

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
                        window.location.href = listUrlFor(payload.classId);
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
            form.dataset.questionBankLocked = '0';
            // Entered from a class tests tab: preselect that class so the exam is
            // bound to it without the lecturer picking it again.
            preselectClass(form.getAttribute('data-preselected-class-id'));
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
