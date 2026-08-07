/* Library "Bài test" rail — one-step clone picker + delete action.
 *
 * One step only: an exam has no section dimension, so picking the target class
 * is the whole dialog (unlike lesson-clone-wizard.js, which needs class then
 * chapter).
 *
 * Feedback goes straight to window.UlpToast. This script deliberately never
 * reads the flash payload — notifications.js is the single owner of that drain
 * (.claude/rules/flash-toast-drain.md); a second reader fires duplicate toasts.
 */
(function () {
  'use strict';

  var CLONE_BASE = '/lecturer/library/tests/';

  var state = {
    examId: null,
    examTitle: '',
    classId: null,
    query: '',
    busy: false
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

  /** Reads the led-class options straight off the rail's own class filter. */
  function ledClasses() {
    var select = document.getElementById('libExamClass');
    if (!select) return [];
    var out = [];
    Array.prototype.forEach.call(select.options, function (opt) {
      // The leading blank option is the "all classes" sentinel, not a class.
      if (opt.value) out.push({ id: opt.value, name: opt.textContent || '' });
    });
    return out;
  }

  function ensureDom() {
    if (root) return root;
    root = document.createElement('div');
    root.id = 'examCloneDialog';
    root.className = 'library-attach-modal';
    root.hidden = true;
    root.innerHTML =
      '<div class="library-attach-backdrop" data-exam-clone-close></div>' +
      '<div class="library-attach-dialog" role="dialog" aria-modal="true" aria-labelledby="examCloneTitle">' +
      '  <div class="library-attach-head">' +
      '    <div>' +
      '      <h3 id="examCloneTitle">Clone bài test sang lớp khác</h3>' +
      '      <p class="library-attach-asset" id="examCloneLabel"></p>' +
      '    </div>' +
      '    <button type="button" class="btn-ghost btn-sm" data-exam-clone-close aria-label="Đóng">Đóng</button>' +
      '  </div>' +
      // Same tools slot the lesson clone wizard uses; filtering is client-side
      // because the whole led-class list is already in the DOM.
      '  <div class="library-attach-tools">' +
      '    <input type="search" id="examCloneClassQ" autocomplete="off"' +
      '           aria-label="Tìm lớp" placeholder="Tìm lớp theo tên…"/>' +
      '  </div>' +
      '  <div class="library-attach-body" id="examCloneBody"></div>' +
      '  <div class="library-attach-foot">' +
      '    <div class="library-attach-foot-spacer"></div>' +
      '    <button type="button" class="btn-ghost" data-exam-clone-close>Huỷ</button>' +
      '    <button type="button" class="btn-primary" id="examCloneConfirm" disabled>Clone (nháp)</button>' +
      '  </div>' +
      '</div>';
    document.body.appendChild(root);

    root.querySelectorAll('[data-exam-clone-close]').forEach(function (el) {
      el.addEventListener('click', close);
    });
    var confirm = document.getElementById('examCloneConfirm');
    if (confirm) confirm.addEventListener('click', doClone);

    var search = document.getElementById('examCloneClassQ');
    if (search) {
      search.addEventListener('input', function () {
        state.query = search.value || '';
        renderClasses();
      });
      // Enter would otherwise bubble out and submit the surrounding page form.
      search.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') e.preventDefault();
      });
    }
    return root;
  }

  function syncConfirm() {
    var confirm = document.getElementById('examCloneConfirm');
    if (confirm) confirm.disabled = !state.classId || state.busy;
  }

  /**
   * Renders the led-class list, narrowed by the search box. Reuses the attach
   * wizard's .library-attach-item so rows keep the dialog's own gutters instead
   * of running edge to edge.
   *
   * <p>Selection survives filtering: the selected id lives in state, so a class
   * filtered out of view stays chosen and the confirm button stays enabled.
   */
  function renderClasses() {
    var body = document.getElementById('examCloneBody');
    if (!body) return;
    var classes = ledClasses();
    if (!classes.length) {
      body.innerHTML = '<div class="exam-clone-empty">Bạn chưa phụ trách lớp nào để clone sang.</div>';
      return;
    }
    var needle = state.query.trim().toLowerCase();
    var shown = needle
      ? classes.filter(function (c) { return c.name.toLowerCase().indexOf(needle) !== -1; })
      : classes;
    if (!shown.length) {
      body.innerHTML = '<div class="exam-clone-empty">Không tìm thấy lớp phù hợp.</div>';
      return;
    }
    var html = '<div class="library-attach-list">';
    shown.forEach(function (c) {
      var selected = String(c.id) === String(state.classId);
      html += '<button type="button" class="library-attach-item'
        + (selected ? ' is-selected' : '')
        + '" data-class-id="' + escapeAttr(c.id) + '">'
        + '<span class="library-attach-item-title">' + escapeHtml(c.name) + '</span>'
        + '</button>';
    });
    html += '</div>';
    body.innerHTML = html;
    body.querySelectorAll('[data-class-id]').forEach(function (el) {
      el.addEventListener('click', function () {
        state.classId = el.getAttribute('data-class-id');
        body.querySelectorAll('.library-attach-item').forEach(function (item) {
          item.classList.toggle('is-selected', item === el);
        });
        syncConfirm();
      });
    });
  }

  function open(examId, examTitle) {
    ensureDom();
    state.examId = examId;
    state.examTitle = examTitle || '';
    state.classId = null;
    state.query = '';
    state.busy = false;
    root.hidden = false;
    var search = document.getElementById('examCloneClassQ');
    if (search) search.value = '';
    var label = document.getElementById('examCloneLabel');
    if (label) label.textContent = state.examTitle;
    var confirm = document.getElementById('examCloneConfirm');
    if (confirm) confirm.textContent = 'Clone (nháp)';
    renderClasses();
    syncConfirm();
  }

  function close() {
    if (root) root.hidden = true;
  }

  function postJson(url, body) {
    var headers = csrfHeaders();
    if (body) headers['Content-Type'] = 'application/x-www-form-urlencoded';
    return fetch(url, {
      method: 'POST',
      headers: headers,
      credentials: 'same-origin',
      body: body || null
    }).then(function (r) {
      return r.json()
        .then(function (j) { return { ok: r.ok, body: j }; })
        .catch(function () { return { ok: r.ok, body: null }; });
    });
  }

  /** Pulls the server's message out of either JSON envelope shape. */
  function messageOf(res, fallback) {
    return (res.body && (res.body.message || res.body.error)) || fallback;
  }

  function doClone() {
    if (!state.examId || !state.classId || state.busy) return;
    state.busy = true;
    syncConfirm();
    var confirm = document.getElementById('examCloneConfirm');
    if (confirm) confirm.textContent = 'Đang clone…';

    var params = new URLSearchParams();
    params.set('targetClassId', String(state.classId));

    postJson(CLONE_BASE + state.examId + '/clone', params.toString())
      .then(function (res) {
        state.busy = false;
        if (confirm) confirm.textContent = 'Clone (nháp)';
        syncConfirm();
        if (!res.ok) {
          toast('error', messageOf(res, 'Không clone được bài test'));
          return;
        }
        toast('success', messageOf(res, 'Đã clone bài test sang lớp (bản nháp)'));
        close();
        reloadList();
      })
      .catch(function () {
        state.busy = false;
        if (confirm) confirm.textContent = 'Clone (nháp)';
        syncConfirm();
        toast('error', 'Không clone được bài test');
      });
  }

  /**
   * Confirms through the shared UlpModal rather than window.confirm: native
   * dialogs are banned project-wide (CLAUDE.md §9) and they block the page
   * thread. Deletion only fires from the modal's confirm handler.
   */
  function doDelete(examId, examTitle) {
    window.UlpModal.confirm({
      title: 'Xoá bài test?',
      body: 'Xoá bài test “' + examTitle + '”? Thao tác này không thể hoàn tác.',
      confirmLabel: 'Xoá',
      onConfirm: function () { sendDelete(examId); }
    });
  }

  function sendDelete(examId) {
    postJson(CLONE_BASE + examId + '/delete', null)
      .then(function (res) {
        if (!res.ok) {
          toast('error', messageOf(res, 'Không xoá được bài test'));
          return;
        }
        toast('success', messageOf(res, 'Đã xoá bài test'));
        reloadList();
      })
      .catch(function () {
        toast('error', 'Không xoá được bài test');
      });
  }

  /**
   * Re-fetches the current rail URL and swaps in the refreshed exam table,
   * filter bar, empty state and pager — so the lecturer stays on the rail with
   * the active filter intact instead of being navigated away.
   *
   * The kind badges live in the sidebar, outside .library-content, so they are
   * swapped too: cloning or deleting changes the exam count, and leaving the
   * sidebar untouched left the badge disagreeing with the table until F5.
   */
  function reloadList() {
    fetch(window.location.href, {
      headers: { 'Accept': 'text/html' },
      credentials: 'same-origin'
    })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var fresh = doc.querySelector('.library-content');
        var current = document.querySelector('.library-content');
        if (!fresh || !current) {
          window.location.reload();
          return;
        }
        current.replaceWith(fresh);

        // Best-effort: a missing sidebar is not worth a full reload when the
        // table already swapped successfully.
        var freshKinds = doc.querySelector('.library-kinds');
        var currentKinds = document.querySelector('.library-kinds');
        if (freshKinds && currentKinds) {
          currentKinds.replaceWith(freshKinds);
        }
      })
      .catch(function () {
        window.location.reload();
      });
  }

  /**
   * One delegated listener on document, bound once, instead of re-binding every
   * row after each reload. Per-node binding would stack a fresh handler on every
   * reloadList() the moment the swap became in-place rather than a replaceWith,
   * firing two dialogs / two confirms per click.
   */
  function bindRowActions() {
    document.addEventListener('click', function (e) {
      var el = e.target.closest('[data-action="clone-exam"], [data-action="delete-exam"]');
      if (!el) return;
      var examId = el.getAttribute('data-exam-id');
      var title = el.getAttribute('data-exam-title') || '';
      if (el.getAttribute('data-action') === 'clone-exam') {
        open(examId, title);
      } else {
        doDelete(examId, title);
      }
    });
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

  window.ExamCloneDialog = { open: open, close: close };

  function ready(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn);
    } else {
      fn();
    }
  }

  ready(bindRowActions);
})();
