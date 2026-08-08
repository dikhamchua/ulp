/* Lesson / template clone wizard — class → section, then POST clone.
 * Single: open({ mode, cloneUrl, title })
 * Bulk:   open({ mode:'lesson', cloneUrls:[] }) or open({ lessons:[{title,cloneUrl}] })
 * Reuses /lecturer/library/targets for editable class/section lists.
 */
(function () {
  'use strict';

  var TARGETS = '/lecturer/library/targets';
  var STEP_CLASS = 1;
  var STEP_SECTION = 2;

  var state = {
    open: false,
    step: STEP_CLASS,
    mode: 'template',
    cloneUrl: null,
    /** @type {{title:string,cloneUrl:string}[]} */
    lessons: [],
    bulk: false,
    title: '',
    classId: null,
    className: '',
    sectionId: null,
    sectionTitle: '',
    classPage: 0,
    classTotalPages: 0,
    classQ: '',
    binding: false
  };

  var picker = null;

  function toast(kind, message) {
    if (window.UlpToast && typeof window.UlpToast[kind] === 'function') {
      window.UlpToast[kind](message);
    }
  }

  function csrfHeaders() {
    var tokenMeta = document.querySelector('meta[name="_csrf"]');
    var headerMeta = document.querySelector('meta[name="_csrf_header"]');
    var headers = { 'Accept': 'application/json' };
    if (tokenMeta && headerMeta) {
      var token = tokenMeta.getAttribute('content');
      var header = headerMeta.getAttribute('content') || 'X-CSRF-TOKEN';
      if (token) headers[header] = token;
    }
    return headers;
  }


  function normalizeLessons(opts) {
    var list = [];
    if (opts.lessons && opts.lessons.length) {
      opts.lessons.forEach(function (item) {
        if (!item) return;
        var url = item.cloneUrl || item.url;
        if (!url) return;
        list.push({ title: item.title || '', cloneUrl: url });
      });
      return list;
    }
    if (opts.cloneUrls && opts.cloneUrls.length) {
      opts.cloneUrls.forEach(function (url) {
        if (!url) return;
        list.push({ title: '', cloneUrl: url });
      });
      return list;
    }
    if (opts.cloneUrl) {
      list.push({ title: opts.title || '', cloneUrl: opts.cloneUrl });
    }
    return list;
  }

  function open(opts) {
    opts = opts || {};
    var lessons = normalizeLessons(opts);
    if (!lessons.length) {
      toast('error', 'Thiếu URL clone');
      return;
    }
    state.open = true;
    state.step = STEP_CLASS;
    state.mode = opts.mode || (lessons.length > 1 ? 'lesson' : 'template');
    state.lessons = lessons;
    state.bulk = lessons.length > 1;
    state.cloneUrl = lessons[0].cloneUrl;
    state.title = state.bulk
      ? ('Clone ' + lessons.length + ' bài giảng')
      : (lessons[0].title || opts.title || '');
    state.classId = null;
    state.className = '';
    state.sectionId = null;
    state.sectionTitle = '';
    state.classPage = 0;
    state.classTotalPages = 0;
    state.classQ = '';
    state.binding = false;

    picker = window.UlpModal.picker({
      title: 'Clone sang lớp',
      subtitle: state.title,
      steps: ['Lớp', 'Chương'],
      onCancel: function () { state.open = false; }
    });
    // Server-side search: firing per keystroke would hit /targets/classes on
    // every letter, so the query only runs on Enter or the Tìm button.
    picker.setSearch(function (q) {
      state.classQ = q;
      state.classPage = 0;
      loadClasses();
    }, 'Tìm lớp theo tên hoặc mã…', 'submit');
    picker.setButtons({
      back: { onClick: goBack },
      next: { onClick: goNext },
      finish: {
        onClick: doClone,
        label: state.bulk ? ('Clone ' + lessons.length + ' bài (nháp)') : 'Clone (nháp)'
      }
    });
    syncChrome();
    loadClasses();
  }

  function close() {
    state.open = false;
    if (picker) picker.close();
  }

  function syncChrome() {
    if (!picker) return;
    picker.setStep(state.step);
    // Search only applies to the class step; the section list is short.
    picker.showSearch(state.step === STEP_CLASS);
    picker.setButtons({
      back: { hidden: state.step === STEP_CLASS },
      next: { hidden: state.step !== STEP_CLASS, disabled: !state.classId },
      finish: {
        hidden: state.step !== STEP_SECTION,
        disabled: !state.sectionId || state.binding
      }
    });
  }

  function setBodyHtml(html) {
    if (picker) picker.body().innerHTML = html;
  }

  function loadClasses() {
    setBodyHtml('<div class="ulp-picker-loading">Đang tải lớp…</div>');
    var q = encodeURIComponent(state.classQ || '');
    var url = TARGETS + '/classes?page=' + state.classPage + '&size=12&q=' + q;
    fetch(url, { headers: csrfHeaders(), credentials: 'same-origin' })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, body: j }; }); })
      .then(function (res) {
        if (!res.ok) {
          setBodyHtml('<div class="ulp-picker-empty">Không tải được danh sách lớp.</div>');
          return;
        }
        var items = (res.body && res.body.items) || [];
        state.classTotalPages = (res.body && res.body.totalPages) || 0;
        if (!items.length) {
          setBodyHtml('<div class="ulp-picker-empty">Không có lớp nào bạn được chỉnh sửa.</div>');
          return;
        }
        var html = '<ul class="ulp-picker-list">';
        items.forEach(function (c) {
          var selected = state.classId === c.id ? ' is-selected' : '';
          html += '<li class="ulp-picker-item' + selected + '" data-class-id="' + c.id + '"'
            + ' data-class-name="' + escapeAttr(c.name || '') + '">'
            + '<span class="ulp-picker-item-title">' + escapeHtml(c.name || '') + '</span>'
            + (c.code ? '<span class="ulp-picker-item-meta">' + escapeHtml(c.code) + '</span>' : '')
            + '</li>';
        });
        html += '</ul>';
        if (state.classTotalPages > 1) {
          html += '<div class="ulp-picker-pager">';
          if (state.classPage > 0) {
            html += '<button type="button" class="btn-ghost btn-sm" data-clone-page="' + (state.classPage - 1) + '">‹ Trước</button>';
          }
          html += '<span>Trang ' + (state.classPage + 1) + ' / ' + state.classTotalPages + '</span>';
          if (state.classPage + 1 < state.classTotalPages) {
            html += '<button type="button" class="btn-ghost btn-sm" data-clone-page="' + (state.classPage + 1) + '">Sau ›</button>';
          }
          html += '</div>';
        }
        setBodyHtml(html);
        bindClassClicks();
      })
      .catch(function () {
        setBodyHtml('<div class="ulp-picker-empty">Không tải được danh sách lớp.</div>');
      });
  }

  function bindClassClicks() {
    var body = picker && picker.body();
    if (!body) return;
    body.querySelectorAll('[data-class-id]').forEach(function (el) {
      el.addEventListener('click', function () {
        state.classId = parseInt(el.getAttribute('data-class-id'), 10);
        state.className = el.getAttribute('data-class-name') || '';
        state.sectionId = null;
        body.querySelectorAll('.ulp-picker-item').forEach(function (li) {
          li.classList.toggle('is-selected', li === el);
        });
        syncChrome();
      });
    });
    body.querySelectorAll('[data-clone-page]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        state.classPage = parseInt(btn.getAttribute('data-clone-page'), 10) || 0;
        loadClasses();
      });
    });
  }

  function renderSections(items) {
    var html = '<ul class="ulp-picker-list">';
    items.forEach(function (s) {
      var selected = state.sectionId === s.id ? ' is-selected' : '';
      html += '<li class="ulp-picker-item' + selected + '" data-section-id="' + s.id + '"'
        + ' data-section-title="' + escapeAttr(s.title || '') + '">'
        + '<span class="ulp-picker-item-title">' + escapeHtml(s.title || '') + '</span>'
        + '</li>';
    });
    html += '</ul>';
    setBodyHtml(html);
    var body = picker && picker.body();
    body.querySelectorAll('[data-section-id]').forEach(function (el) {
      el.addEventListener('click', function () {
        state.sectionId = parseInt(el.getAttribute('data-section-id'), 10);
        state.sectionTitle = el.getAttribute('data-section-title') || '';
        body.querySelectorAll('.ulp-picker-item').forEach(function (li) {
          li.classList.toggle('is-selected', li === el);
        });
        syncChrome();
      });
    });
    // Single section (incl. auto-created "Chương 1") — pre-select so Finish is ready.
    if (items.length === 1 && !state.sectionId) {
      state.sectionId = items[0].id;
      state.sectionTitle = items[0].title || '';
      var only = body.querySelector('[data-section-id]');
      if (only) only.classList.add('is-selected');
      syncChrome();
    }
  }

  function loadSections() {
    setBodyHtml('<div class="ulp-picker-loading">Đang tải chương…</div>');
    var url = TARGETS + '/classes/' + state.classId + '/sections';
    fetch(url, { headers: csrfHeaders(), credentials: 'same-origin' })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, body: j }; }); })
      .then(function (res) {
        if (!res.ok) {
          setBodyHtml('<div class="ulp-picker-empty">Không tải được danh sách chương.</div>');
          return;
        }
        var items = Array.isArray(res.body) ? res.body : [];
        // Backend auto-creates "Chương 1" when empty; still guard empty responses.
        if (!items.length) {
          setBodyHtml('<div class="ulp-picker-empty">Không tạo được chương mặc định. Thử lại.</div>');
          return;
        }
        renderSections(items);
      })
      .catch(function () {
        setBodyHtml('<div class="ulp-picker-empty">Không tải được danh sách chương.</div>');
      });
  }

  function goNext() {
    if (state.step === STEP_CLASS && state.classId) {
      state.step = STEP_SECTION;
      syncChrome();
      loadSections();
    }
  }

  function goBack() {
    if (state.step === STEP_SECTION) {
      state.step = STEP_CLASS;
      state.sectionId = null;
      syncChrome();
      loadClasses();
    }
  }

  function buildCloneParams() {
    var params = new URLSearchParams();
    // Template clone uses classId/sectionId; lesson clone uses targetClassId/targetSectionId.
    if (state.mode === 'lesson' || state.bulk) {
      params.set('targetClassId', String(state.classId));
      params.set('targetSectionId', String(state.sectionId));
    } else {
      params.set('classId', String(state.classId));
      params.set('sectionId', String(state.sectionId));
    }
    return params;
  }

  function postClone(cloneUrl) {
    var headers = csrfHeaders();
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    return fetch(cloneUrl, {
      method: 'POST',
      headers: headers,
      credentials: 'same-origin',
      body: buildCloneParams().toString()
    }).then(function (r) {
      return r.json().then(function (j) {
        return { ok: r.ok, status: r.status, body: j };
      }).catch(function () {
        return { ok: r.ok, status: r.status, body: null };
      });
    });
  }

  function doClone() {
    if (!state.lessons.length || !state.classId || !state.sectionId || state.binding) return;
    state.binding = true;
    syncChrome();
    if (picker) picker.setButtons({ finish: { label: 'Đang clone…' } });

    if (!state.bulk) {
      postClone(state.lessons[0].cloneUrl)
        .then(function (res) {
          state.binding = false;
          if (finish) finish.textContent = 'Clone (nháp)';
          syncChrome();
          if (!res.ok) {
            var msg = (res.body && (res.body.message || res.body.error)) || 'Không clone được bài giảng';
            toast('error', msg);
            return;
          }
          toast('success', (res.body && res.body.message) || 'Đã clone bài giảng sang lớp (bản nháp)');
          close();
          if (window.LibraryLessonBulkSelect && typeof window.LibraryLessonBulkSelect.clear === 'function') {
            window.LibraryLessonBulkSelect.clear();
          }
          if (res.body && res.body.editUrl) {
            window.location.href = res.body.editUrl;
          } else if (res.body && res.body.classId && res.body.sectionId && res.body.lessonId) {
            window.location.href = '/lecturer/classes/' + res.body.classId
              + '/sections/' + res.body.sectionId
              + '/lessons/' + res.body.lessonId + '/edit';
          } else {
            window.location.reload();
          }
        })
        .catch(function () {
          state.binding = false;
          if (finish) finish.textContent = 'Clone (nháp)';
          syncChrome();
          toast('error', 'Không clone được bài giảng');
        });
      return;
    }

    // Bulk: sequential POSTs so server load stays predictable.
    var ok = 0;
    var fail = 0;
    var idx = 0;
    var jobs = state.lessons.slice();

    function next() {
      if (idx >= jobs.length) {
        state.binding = false;
        if (finish) finish.textContent = 'Clone (nháp)';
        syncChrome();
        if (ok > 0 && fail === 0) {
          toast('success', 'Đã clone ' + ok + ' bài giảng sang lớp (bản nháp)');
        } else if (ok > 0) {
          toast('success', 'Đã clone ' + ok + ' bài, ' + fail + ' bài lỗi');
        } else {
          toast('error', 'Không clone được bài giảng đã chọn');
        }
        close();
        if (ok > 0 && window.LibraryLessonBulkSelect
            && typeof window.LibraryLessonBulkSelect.clear === 'function') {
          window.LibraryLessonBulkSelect.clear();
        }
        if (ok > 0) {
          window.location.reload();
        }
        return;
      }
      var job = jobs[idx++];
      if (finish) {
        finish.textContent = 'Đang clone ' + idx + '/' + jobs.length + '…';
      }
      postClone(job.cloneUrl)
        .then(function (res) {
          if (res.ok) ok += 1;
          else fail += 1;
          next();
        })
        .catch(function () {
          fail += 1;
          next();
        });
    }
    next();
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function escapeAttr(s) {
    return escapeHtml(s).replace(/'/g, '&#39;');
  }

  window.LessonCloneWizard = { open: open, close: close };

  function ready(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn);
    } else {
      fn();
    }
  }

  // Bind edit-form / lessons-list clone buttons.
  ready(function () {
    var btn = document.getElementById('lessonCloneBtn');
    if (btn) {
      btn.addEventListener('click', function () {
        var url = btn.getAttribute('data-clone-url');
        var title = btn.getAttribute('data-lesson-title') || '';
        if (!url) return;
        open({ mode: 'lesson', cloneUrl: url, title: title });
      });
    }
    document.querySelectorAll('[data-action="clone-lesson"]').forEach(function (el) {
      el.addEventListener('click', function () {
        var url = el.getAttribute('data-clone-url');
        var title = el.getAttribute('data-lesson-title') || '';
        if (!url) return;
        open({ mode: 'lesson', cloneUrl: url, title: title });
      });
    });
  });
})();
