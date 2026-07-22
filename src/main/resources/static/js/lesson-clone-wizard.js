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

  var root = null;

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

  function ensureDom() {
    if (root) return root;
    root = document.createElement('div');
    root.id = 'lessonCloneWizard';
    root.className = 'library-attach-modal';
    root.hidden = true;
    root.innerHTML =
      '<div class="library-attach-backdrop" data-clone-close></div>' +
      '<div class="library-attach-dialog" role="dialog" aria-modal="true" aria-labelledby="lessonCloneTitle">' +
      '  <div class="library-attach-head">' +
      '    <div>' +
      '      <h3 id="lessonCloneTitle">Clone sang lớp</h3>' +
      '      <p class="library-attach-asset" id="lessonCloneLabel"></p>' +
      '    </div>' +
      '    <button type="button" class="btn-ghost btn-sm" data-clone-close aria-label="Đóng">Đóng</button>' +
      '  </div>' +
      '  <ol class="library-attach-steps" id="lessonCloneSteps" aria-label="Các bước">' +
      '    <li data-step="1" class="is-active">Lớp</li>' +
      '    <li data-step="2">Chương</li>' +
      '  </ol>' +
      '  <div class="library-attach-tools" id="lessonCloneTools">' +
      '    <input type="search" id="lessonCloneClassQ" placeholder="Tìm lớp theo tên hoặc mã…" autocomplete="off"/>' +
      '    <button type="button" class="btn-ghost btn-sm" id="lessonCloneClassSearch">Tìm</button>' +
      '  </div>' +
      '  <div class="library-attach-body" id="lessonCloneBody"><div class="library-attach-loading">Đang tải…</div></div>' +
      '  <div class="library-attach-foot">' +
      '    <button type="button" class="btn-ghost" id="lessonCloneBack" hidden>Quay lại</button>' +
      '    <div class="library-attach-foot-spacer"></div>' +
      '    <button type="button" class="btn-ghost" data-clone-close>Huỷ</button>' +
      '    <button type="button" class="btn-primary" id="lessonCloneNext" disabled>Tiếp</button>' +
      '    <button type="button" class="btn-primary" id="lessonCloneFinish" hidden disabled>Clone (nháp)</button>' +
      '  </div>' +
      '</div>';
    document.body.appendChild(root);

    root.querySelectorAll('[data-clone-close]').forEach(function (el) {
      el.addEventListener('click', close);
    });
    var back = document.getElementById('lessonCloneBack');
    var next = document.getElementById('lessonCloneNext');
    var finish = document.getElementById('lessonCloneFinish');
    var searchBtn = document.getElementById('lessonCloneClassSearch');
    var searchInput = document.getElementById('lessonCloneClassQ');
    if (back) back.addEventListener('click', goBack);
    if (next) next.addEventListener('click', goNext);
    if (finish) finish.addEventListener('click', doClone);
    if (searchBtn) searchBtn.addEventListener('click', function () {
      state.classQ = (searchInput && searchInput.value) || '';
      state.classPage = 0;
      loadClasses();
    });
    if (searchInput) {
      searchInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
          e.preventDefault();
          state.classQ = searchInput.value || '';
          state.classPage = 0;
          loadClasses();
        }
      });
    }
    return root;
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
    ensureDom();
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
    root.hidden = false;
    var label = document.getElementById('lessonCloneLabel');
    if (label) label.textContent = state.title || '';
    var finish = document.getElementById('lessonCloneFinish');
    if (finish) {
      finish.textContent = state.bulk
        ? ('Clone ' + lessons.length + ' bài (nháp)')
        : 'Clone (nháp)';
    }
    syncChrome();
    loadClasses();
  }

  function close() {
    if (!root) return;
    state.open = false;
    root.hidden = true;
  }

  function syncChrome() {
    var steps = document.getElementById('lessonCloneSteps');
    if (steps) {
      steps.querySelectorAll('li').forEach(function (li) {
        var s = parseInt(li.getAttribute('data-step'), 10);
        li.classList.toggle('is-active', s === state.step);
        li.classList.toggle('is-done', s < state.step);
      });
    }
    var tools = document.getElementById('lessonCloneTools');
    if (tools) tools.hidden = state.step !== STEP_CLASS;
    var back = document.getElementById('lessonCloneBack');
    var next = document.getElementById('lessonCloneNext');
    var finish = document.getElementById('lessonCloneFinish');
    if (back) back.hidden = state.step === STEP_CLASS;
    if (next) {
      next.hidden = state.step !== STEP_CLASS;
      next.disabled = !state.classId;
    }
    if (finish) {
      finish.hidden = state.step !== STEP_SECTION;
      finish.disabled = !state.sectionId || state.binding;
    }
  }

  function setBodyHtml(html) {
    var body = document.getElementById('lessonCloneBody');
    if (body) body.innerHTML = html;
  }

  function loadClasses() {
    setBodyHtml('<div class="library-attach-loading">Đang tải lớp…</div>');
    var q = encodeURIComponent(state.classQ || '');
    var url = TARGETS + '/classes?page=' + state.classPage + '&size=12&q=' + q;
    fetch(url, { headers: csrfHeaders(), credentials: 'same-origin' })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, body: j }; }); })
      .then(function (res) {
        if (!res.ok) {
          setBodyHtml('<div class="library-attach-empty">Không tải được danh sách lớp.</div>');
          return;
        }
        var items = (res.body && res.body.items) || [];
        state.classTotalPages = (res.body && res.body.totalPages) || 0;
        if (!items.length) {
          setBodyHtml('<div class="library-attach-empty">Không có lớp nào bạn được chỉnh sửa.</div>');
          return;
        }
        var html = '<ul class="library-attach-list">';
        items.forEach(function (c) {
          var selected = state.classId === c.id ? ' is-selected' : '';
          html += '<li class="library-attach-item' + selected + '" data-class-id="' + c.id + '"'
            + ' data-class-name="' + escapeAttr(c.name || '') + '">'
            + '<span class="library-attach-item-title">' + escapeHtml(c.name || '') + '</span>'
            + (c.code ? '<span class="library-attach-item-meta">' + escapeHtml(c.code) + '</span>' : '')
            + '</li>';
        });
        html += '</ul>';
        if (state.classTotalPages > 1) {
          html += '<div class="library-attach-pager">';
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
        setBodyHtml('<div class="library-attach-empty">Không tải được danh sách lớp.</div>');
      });
  }

  function bindClassClicks() {
    var body = document.getElementById('lessonCloneBody');
    if (!body) return;
    body.querySelectorAll('[data-class-id]').forEach(function (el) {
      el.addEventListener('click', function () {
        state.classId = parseInt(el.getAttribute('data-class-id'), 10);
        state.className = el.getAttribute('data-class-name') || '';
        state.sectionId = null;
        body.querySelectorAll('.library-attach-item').forEach(function (li) {
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
    var html = '<ul class="library-attach-list">';
    items.forEach(function (s) {
      var selected = state.sectionId === s.id ? ' is-selected' : '';
      html += '<li class="library-attach-item' + selected + '" data-section-id="' + s.id + '"'
        + ' data-section-title="' + escapeAttr(s.title || '') + '">'
        + '<span class="library-attach-item-title">' + escapeHtml(s.title || '') + '</span>'
        + '</li>';
    });
    html += '</ul>';
    setBodyHtml(html);
    var body = document.getElementById('lessonCloneBody');
    body.querySelectorAll('[data-section-id]').forEach(function (el) {
      el.addEventListener('click', function () {
        state.sectionId = parseInt(el.getAttribute('data-section-id'), 10);
        state.sectionTitle = el.getAttribute('data-section-title') || '';
        body.querySelectorAll('.library-attach-item').forEach(function (li) {
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
    setBodyHtml('<div class="library-attach-loading">Đang tải chương…</div>');
    var url = TARGETS + '/classes/' + state.classId + '/sections';
    fetch(url, { headers: csrfHeaders(), credentials: 'same-origin' })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, body: j }; }); })
      .then(function (res) {
        if (!res.ok) {
          setBodyHtml('<div class="library-attach-empty">Không tải được danh sách chương.</div>');
          return;
        }
        var items = Array.isArray(res.body) ? res.body : [];
        // Backend auto-creates "Chương 1" when empty; still guard empty responses.
        if (!items.length) {
          setBodyHtml('<div class="library-attach-empty">Không tạo được chương mặc định. Thử lại.</div>');
          return;
        }
        renderSections(items);
      })
      .catch(function () {
        setBodyHtml('<div class="library-attach-empty">Không tải được danh sách chương.</div>');
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
    var finish = document.getElementById('lessonCloneFinish');
    if (finish) finish.textContent = 'Đang clone…';

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
