/* ═══════════════════════════════════════════════════════════════════════
   ULP — Section actions on the lessons tab (ULP-4.0a / Scope A redesign)
   Vanilla JS for soft-delete, drag-reorder, search filter, and the new
   3-dot action menu in the folders column.

   The lessons page layout has 3 columns: class-sidebar | folders (sections)
   | content (lessons placeholder for ULP-4.0b). Folder click navigates via
   plain <a href> with ?section={id} — no JS needed for selection.

   Create + rename live on dedicated full-page forms — see SectionsController
   GET /lessons/new and /lessons/sections/{id}/edit.
   Page markup: templates/classes/detail-lessons.html
   ════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var state = {
    classId: null,
    canEdit: false,
    baseUrl: null,
    selectedSectionId: null
  };

  function el(id) { return document.getElementById(id); }

  function csrfHeaders() {
    var tokenMeta = document.querySelector('meta[name="_csrf"]');
    var headerMeta = document.querySelector('meta[name="_csrf_header"]');
    var headers = {};
    if (tokenMeta && headerMeta && tokenMeta.content && headerMeta.content) {
      headers[headerMeta.content] = tokenMeta.content;
    }
    return headers;
  }

  function toast(kind, message) {
    if (window.UlpToast && typeof window.UlpToast[kind] === 'function') {
      window.UlpToast[kind](message);
    }
  }

  /**
   * Fetch helper that always returns {ok, message, data, status}.
   * Centralises CSRF + JSON content type + error envelope handling.
   */
  function api(method, url, body) {
    var headers = Object.assign({}, csrfHeaders());
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    return fetch(url, {
      method: method,
      headers: headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body)
    }).then(function (res) {
      return res.json()
        .catch(function () { return { ok: false, message: 'Phản hồi không hợp lệ' }; })
        .then(function (json) {
          return {
            status: res.status,
            ok: res.ok && json && json.ok === true,
            message: (json && json.message) || null,
            data: (json && json.data) || null
          };
        });
    }).catch(function () {
      return { status: 0, ok: false, message: 'Không kết nối được tới server.', data: null };
    });
  }

  /**
   * Toggles the "section list is empty" state after a delete. The "all
   * lessons" pseudo-folder is filtered out — only real .sec-item rows count.
   *
   * Also hides the search box when zero sections remain so the lecturer
   * doesn't see a search affordance with nothing to search through. The
   * server-side {@code th:if} hides it on initial render too — this is
   * the client-side mirror after AJAX delete drops the last row.
   */
  function refreshEmptyState() {
    var list = el('sectionList');
    var empty = el('sectionEmpty');
    var search = document.querySelector('.folders-search');
    var n = list ? list.querySelectorAll('.folder-item.sec-item').length : 0;
    if (empty) empty.style.display = n === 0 ? '' : 'none';
    if (search) search.hidden = n === 0;
  }

  // ── Client-side search ──────────────────────────────────────────────
  // Filters the folder list as the user types. Diacritic-insensitive +
  // case-insensitive so "chuong 1" matches "Chương 1". Pure DOM toggling
  // — no request to the server. The "all lessons" pseudo-folder is NEVER
  // hidden by the filter since it isn't a section.

  /**
   * Strips Vietnamese diacritics and lowercases the input so search
   * queries match regardless of how the user typed them. Uses NFD
   * normalization to decompose accented characters then drops the
   * combining marks (U+0300-U+036F) via an explicit Unicode-escape
   * RegExp so the source file stays plain ASCII. The 'đ' / 'Đ' glyphs
   * do NOT decompose via NFD so they are replaced explicitly using
   * their Unicode escapes (U+0111 / U+0110).
   */
  var DIACRITIC_MARKS = new RegExp('[\\u0300-\\u036F]', 'g');
  var D_LOWER = new RegExp('\\u0111', 'g');
  var D_UPPER = new RegExp('\\u0110', 'g');
  function normalise(text) {
    if (!text) return '';
    return text
      .normalize('NFD')
      .replace(DIACRITIC_MARKS, '')
      .replace(D_LOWER, 'd')
      .replace(D_UPPER, 'D')
      .toLowerCase()
      .trim();
  }

  function applySearch(query) {
    var list = el('sectionList');
    if (!list) return;
    var normalisedQuery = normalise(query);
    // Only filter real section rows — the "All lessons" pseudo-folder stays
    // visible regardless of the query.
    var items = list.querySelectorAll('.folder-item.sec-item');
    var visibleCount = 0;
    items.forEach(function (li) {
      var titleNode = li.querySelector('.sec-title');
      var title = titleNode ? titleNode.textContent : '';
      var match = normalisedQuery === '' ||
                  normalise(title).indexOf(normalisedQuery) !== -1;
      li.hidden = !match;
      if (match) visibleCount++;
    });
    // Toggle the "no result" empty state. The original "no sections at
    // all" empty state is left alone — it's a server-rendered branch.
    var noResult = el('sectionNoResult');
    if (noResult) {
      noResult.hidden = visibleCount > 0 || items.length === 0;
    }
    var clearBtn = el('sectionSearchClear');
    if (clearBtn) clearBtn.hidden = query.length === 0;
  }

  function initSearch() {
    var input = el('sectionSearchInput');
    if (!input) return;
    input.addEventListener('input', function () {
      applySearch(input.value);
    });
    var clearBtn = el('sectionSearchClear');
    if (clearBtn) {
      clearBtn.addEventListener('click', function () {
        input.value = '';
        applySearch('');
        input.focus();
      });
    }
    // ESC clears the field while focused — small UX nicety.
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && input.value !== '') {
        input.value = '';
        applySearch('');
      }
    });
  }

  // ── 3-dot action menu ───────────────────────────────────────────────
  // Each folder row has a hover-revealed menu trigger; clicking it toggles
  // the dropdown. Only one menu can be open at a time. Click outside any
  // menu (or press Escape) closes the currently open one.
  //
  // Lesson rows share the same data-menu-trigger / data-menu-dropdown
  // contract — closeAllMenus walks every wrapper class so opening one
  // lesson menu also closes any open section menu, and vice versa.

  /** Wrapper classes that host a single open-able menu at a time. */
  var MENU_WRAPPER_SELECTOR = '.folder-item-menu.is-open, .lesson-item-menu.is-open';

  function closeAllMenus(except) {
    document.querySelectorAll(MENU_WRAPPER_SELECTOR).forEach(function (menu) {
      if (menu === except) return;
      menu.classList.remove('is-open');
      var dropdown = menu.querySelector('[data-menu-dropdown]');
      if (dropdown) dropdown.hidden = true;
    });
  }

  /**
   * Wires up a single list's 3-dot menu triggers using event delegation.
   * Re-used for both the section folders list and the lesson list so the
   * UX (single open menu, outside-click close, Esc close) stays in sync.
   */
  function bindMenuToggles(list, wrapperSelector) {
    if (!list) return;
    list.addEventListener('click', function (event) {
      var trigger = event.target.closest('[data-menu-trigger]');
      if (!trigger) return;
      event.preventDefault();
      event.stopPropagation();
      var menu = trigger.closest(wrapperSelector);
      if (!menu) return;
      var dropdown = menu.querySelector('[data-menu-dropdown]');
      var willOpen = !menu.classList.contains('is-open');
      closeAllMenus(menu);
      menu.classList.toggle('is-open', willOpen);
      if (dropdown) dropdown.hidden = !willOpen;
    });
  }

  function initMenus() {
    bindMenuToggles(el('sectionList'), '.folder-item-menu');
    bindMenuToggles(el('lessonList'), '.lesson-item-menu');

    // Close menus on outside click — walks both wrappers so neither list
    // leaks an open dropdown.
    document.addEventListener('click', function (event) {
      if (event.target.closest('.folder-item-menu')) return;
      if (event.target.closest('.lesson-item-menu')) return;
      closeAllMenus(null);
    });

    // Close menus on Escape.
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') closeAllMenus(null);
    });
  }

  // ── Delete handler ──────────────────────────────────────────────────
  function onListClick(event) {
    var delBtn = event.target.closest('.sec-btn-del');
    if (!delBtn) return;
    var id = delBtn.getAttribute('data-section-id');
    var title = delBtn.getAttribute('data-section-title') || '';
    // Close the dropdown immediately so it doesn't linger behind the
    // confirm dialog.
    closeAllMenus(null);
    // Prefer the shared UlpModal.confirm dialog (defined in app.js) so
    // destructive actions across the app share one visual + i18n surface.
    // Native window.confirm() is a hard fallback in case app.js fails to
    // load — extremely unlikely but cheap to handle.
    if (window.UlpModal && typeof window.UlpModal.confirm === 'function') {
      window.UlpModal.confirm({
        title: 'Xác nhận xoá chương',
        body: 'Bạn có chắc muốn xoá chương "' + title + '"? Hành động này không thể hoàn tác.',
        confirmLabel: 'Xoá',
        onConfirm: function () { doDelete(id); }
      });
    } else if (window.confirm('Xoá chương "' + title + '"?')) {
      doDelete(id);
    }
  }

  function doDelete(sectionId) {
    return api('DELETE', state.baseUrl + '/sections/' + encodeURIComponent(sectionId))
      .then(function (res) {
        if (!res.ok) {
          toast('error', res.message || 'Xoá thất bại');
          return;
        }
        var li = document.querySelector(
            '.folder-item.sec-item[data-section-id="' + sectionId + '"]');
        if (li && li.parentNode) li.parentNode.removeChild(li);
        refreshEmptyState();
        toast('success', 'Đã xoá chương');
        // If the deleted section was the currently-selected folder, drop
        // the ?section= query param so the URL stays consistent.
        var url = new URL(window.location.href);
        if (url.searchParams.get('section') === String(sectionId)) {
          url.searchParams.delete('section');
          window.history.replaceState({}, '', url.toString());
        }
      });
  }

  // ── Drag-reorder via Sortable.js ────────────────────────────────────
  // Sortable mutates the DOM *before* the server confirms the new order.
  // If the server rejects the request, the DOM is now ahead of the truth
  // — we need to roll it back instead of forcing a full-page reload.
  // The pre-drag order is captured in onStart and restored in doReorder
  // when the API call fails.
  var preDragOrder = null;

  function snapshotCurrentOrder() {
    var ids = [];
    document.querySelectorAll('#sectionList .folder-item.sec-item').forEach(function (li) {
      ids.push(li.getAttribute('data-section-id'));
    });
    return ids;
  }

  /**
   * Re-arranges the existing DOM nodes to match the supplied id order.
   * No nodes are created or destroyed — we just move them in place, so
   * any event listeners or data attributes survive the rollback.
   */
  function restoreOrder(ids) {
    var list = el('sectionList');
    if (!list || !ids) return;
    ids.forEach(function (id) {
      var li = list.querySelector('.folder-item.sec-item[data-section-id="' + id + '"]');
      if (li) list.appendChild(li);
    });
  }

  function doReorder() {
    var ids = [];
    document.querySelectorAll('#sectionList .folder-item.sec-item').forEach(function (li) {
      ids.push(Number(li.getAttribute('data-section-id')));
    });
    var snapshot = preDragOrder;
    preDragOrder = null;
    return api('POST', state.baseUrl + '/sections/reorder', { orderedIds: ids })
      .then(function (res) {
        if (!res.ok) {
          toast('error', res.message || 'Sắp xếp thất bại');
          // Roll the DOM back to the pre-drag order so the user sees a
          // consistent state without a jarring full-page reload.
          restoreOrder(snapshot);
          return;
        }
        toast('success', 'Đã cập nhật thứ tự');
      });
  }

  function initSortable() {
    var list = el('sectionList');
    if (!list || !window.Sortable) return;
    if (!state.canEdit) return;
    window.Sortable.create(list, {
      handle: '.sec-handle',
      // The "All lessons" pseudo-folder must stay pinned at the top — it
      // isn't a real section, so excluding it from drag prevents the user
      // from interleaving real sections above/below it.
      filter: '.folder-item-all',
      animation: 150,
      ghostClass: 'is-ghost',
      dragClass: 'is-dragging',
      onStart: function () { preDragOrder = snapshotCurrentOrder(); },
      onEnd: doReorder
    });
  }

  // ── Lesson list — AJAX delete ───────────────────────────────────────
  // Lesson rows live in the content column. Edit + publish-toggle ride on
  // anchors / form-POSTs; only the destructive Delete action goes through
  // the JSON API so the DOM updates in place without a full reload.

  function onLessonListClick(event) {
    var delBtn = event.target.closest('.lesson-btn-del');
    if (!delBtn) return;
    var id = delBtn.getAttribute('data-lesson-id');
    var title = delBtn.getAttribute('data-lesson-title') || '';
    closeAllMenus(null);
    if (window.UlpModal && typeof window.UlpModal.confirm === 'function') {
      window.UlpModal.confirm({
        title: 'Xác nhận xoá bài giảng',
        body: 'Bạn có chắc muốn xoá bài giảng "' + title
              + '"? Hành động này không thể hoàn tác.',
        confirmLabel: 'Xoá',
        onConfirm: function () { doLessonDelete(id); }
      });
    } else if (window.confirm('Xoá bài giảng "' + title + '"?')) {
      doLessonDelete(id);
    }
  }

  function lessonsEndpoint(lessonId) {
    return '/lecturer/classes/' + state.classId
         + '/sections/' + state.selectedSectionId
         + '/lessons/' + encodeURIComponent(lessonId);
  }

  function doLessonDelete(lessonId) {
    if (!state.selectedSectionId) return;
    return api('DELETE', lessonsEndpoint(lessonId))
      .then(function (res) {
        if (!res.ok) {
          toast('error', res.message || 'Xoá bài giảng thất bại');
          return;
        }
        var li = document.querySelector(
            '.lesson-item[data-lesson-id="' + lessonId + '"]');
        if (li && li.parentNode) li.parentNode.removeChild(li);
        toast('success', 'Đã xoá bài giảng');
      });
  }

  // ── Bootstrap ───────────────────────────────────────────────────────
  document.addEventListener('DOMContentLoaded', function () {
    var panel = el('sectionsPanel');
    if (!panel) return;
    state.classId = panel.getAttribute('data-class-id');
    if (!state.classId) return;
    state.baseUrl = '/lecturer/classes/' + state.classId + '/lessons';
    state.selectedSectionId = panel.getAttribute('data-section-id');

    var list = el('sectionList');
    state.canEdit = list && list.getAttribute('data-can-edit') === 'true';

    if (list) list.addEventListener('click', onListClick);
    var lessonList = el('lessonList');
    if (lessonList) lessonList.addEventListener('click', onLessonListClick);

    initSortable();
    initSearch();
    initMenus();
    refreshEmptyState();
  });
})();
