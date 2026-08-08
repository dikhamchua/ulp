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

  function syncConfirm() {
    if (!picker) return;
    picker.setButtons({
      next: {
        disabled: !state.classId || state.busy,
        label: state.busy ? 'Đang clone…' : 'Clone (nháp)'
      }
    });
  }

  /**
   * Renders the led-class list, narrowed by the search box. Reuses the attach
   * shared .ulp-picker-item so rows keep the dialog's own gutters instead of
   * running edge to edge.
   *
   * <p>Selection survives filtering: the selected id lives in state, so a class
   * filtered out of view stays chosen and the confirm button stays enabled.
   */
  function renderClasses() {
    if (!picker) return;
    var body = picker.body();
    var classes = ledClasses();
    if (!classes.length) {
      picker.empty('Bạn chưa phụ trách lớp nào để clone sang.');
      return;
    }
    var needle = state.query.trim().toLowerCase();
    var shown = needle
      ? classes.filter(function (c) { return c.name.toLowerCase().indexOf(needle) !== -1; })
      : classes;
    if (!shown.length) {
      picker.empty('Không tìm thấy lớp phù hợp.');
      return;
    }
    var html = '<div class="ulp-picker-list">';
    shown.forEach(function (c) {
      var selected = String(c.id) === String(state.classId);
      html += '<button type="button" class="ulp-picker-item'
        + (selected ? ' is-selected' : '')
        + '" data-class-id="' + escapeAttr(c.id) + '">'
        + '<span class="ulp-picker-item-title">' + escapeHtml(c.name) + '</span>'
        + '</button>';
    });
    html += '</div>';
    body.innerHTML = html;
    body.querySelectorAll('[data-class-id]').forEach(function (el) {
      el.addEventListener('click', function () {
        state.classId = el.getAttribute('data-class-id');
        body.querySelectorAll('.ulp-picker-item').forEach(function (item) {
          item.classList.toggle('is-selected', item === el);
        });
        syncConfirm();
      });
    });
  }

  function open(examId, examTitle) {
    state.examId = examId;
    state.examTitle = examTitle || '';
    state.classId = null;
    state.query = '';
    state.busy = false;

    picker = window.UlpModal.picker({
      title: 'Clone bài test sang lớp khác',
      subtitle: state.examTitle,
      confirmLabel: 'Clone (nháp)'
    });
    picker.setSearch(function (q) {
      state.query = q;
      renderClasses();
    }, 'Tìm lớp theo tên…');
    picker.setButtons({ next: { onClick: doClone } });
    renderClasses();
    syncConfirm();
  }

  function close() {
    if (picker) picker.close();
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
    // syncConfirm() owns both the label and the disabled state, so the busy
    // flag alone drives the "Đang clone…" text.
    state.busy = true;
    syncConfirm();

    var params = new URLSearchParams();
    params.set('targetClassId', String(state.classId));

    postJson(CLONE_BASE + state.examId + '/clone', params.toString())
      .then(function (res) {
        state.busy = false;
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
