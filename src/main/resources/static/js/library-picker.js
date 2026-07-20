/* Shared library picker modal for lesson form (PDF / video / attachments).
 *
 * Usage:
 *   window.UlpLibraryPicker.open({ kind: 'DOCUMENT', onSelect: function (item) {} })
 * Esc / backdrop closes. Loads GET /lecturer/library/api with CSRF-safe GET.
 */
(function () {
  'use strict';

  var API = '/lecturer/library/api';
  var state = {
    kind: '',
    page: 0,
    q: '',
    onSelect: null,
    totalPages: 0
  };

  function toast(kind, message) {
    if (window.UlpToast && typeof window.UlpToast[kind] === 'function') {
      window.UlpToast[kind](message);
    }
  }

  function el(id) {
    return document.getElementById(id);
  }

  function ensureModal() {
    var modal = el('libraryPickerModal');
    if (modal) return modal;
    modal = document.createElement('div');
    modal.id = 'libraryPickerModal';
    modal.className = 'library-picker-modal';
    modal.hidden = true;
    modal.innerHTML =
      '<div class="library-picker-backdrop" data-picker-close></div>' +
      '<div class="library-picker-dialog" role="dialog" aria-modal="true" aria-labelledby="libraryPickerTitle">' +
      '  <div class="library-picker-head">' +
      '    <h3 id="libraryPickerTitle">Chọn từ kho học liệu</h3>' +
      '    <button type="button" class="btn btn-ghost btn-sm" data-picker-close aria-label="Đóng">Đóng</button>' +
      '  </div>' +
      '  <div class="library-picker-tools">' +
      '    <input type="search" id="libraryPickerQ" placeholder="Tìm theo tên..." />' +
      '    <button type="button" class="btn btn-secondary btn-sm" id="libraryPickerSearch">Tìm</button>' +
      '  </div>' +
      '  <div class="library-picker-body" id="libraryPickerBody">' +
      '    <div class="library-picker-loading">Đang tải…</div>' +
      '  </div>' +
      '  <div class="library-picker-foot">' +
      '    <button type="button" class="btn btn-ghost btn-sm" id="libraryPickerPrev">Trước</button>' +
      '    <span id="libraryPickerPageLabel"></span>' +
      '    <button type="button" class="btn btn-ghost btn-sm" id="libraryPickerNext">Sau</button>' +
      '  </div>' +
      '</div>';
    document.body.appendChild(modal);

    modal.addEventListener('click', function (e) {
      if (e.target && e.target.hasAttribute('data-picker-close')) close();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !modal.hidden) close();
    });
    el('libraryPickerSearch').addEventListener('click', function () {
      state.q = el('libraryPickerQ').value || '';
      state.page = 0;
      load();
    });
    el('libraryPickerQ').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') {
        e.preventDefault();
        el('libraryPickerSearch').click();
      }
    });
    el('libraryPickerPrev').addEventListener('click', function () {
      if (state.page > 0) {
        state.page -= 1;
        load();
      }
    });
    el('libraryPickerNext').addEventListener('click', function () {
      if (state.page + 1 < state.totalPages) {
        state.page += 1;
        load();
      }
    });
    return modal;
  }

  function close() {
    var modal = el('libraryPickerModal');
    if (modal) modal.hidden = true;
    state.onSelect = null;
  }

  function formatSize(bytes) {
    var n = Number(bytes);
    if (!isFinite(n) || n < 0) return '—';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / (1024 * 1024)).toFixed(1) + ' MB';
  }

  function render(page) {
    var body = el('libraryPickerBody');
    var items = (page && page.items) || [];
    state.totalPages = page ? (page.totalPages || 0) : 0;
    el('libraryPickerPageLabel').textContent =
      state.totalPages > 0
        ? ('Trang ' + (state.page + 1) + ' / ' + state.totalPages)
        : '';
    el('libraryPickerPrev').disabled = state.page <= 0;
    el('libraryPickerNext').disabled = state.page + 1 >= state.totalPages;

    if (!items.length) {
      body.innerHTML = '<div class="library-picker-empty">Không có học liệu phù hợp.</div>';
      return;
    }
    body.innerHTML = '';
    items.forEach(function (item) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'library-picker-item';
      btn.innerHTML =
        '<span><span class="library-picker-item-title"></span>' +
        '<br><span class="library-picker-item-meta"></span></span>' +
        '<span class="btn btn-secondary btn-sm">Chọn</span>';
      btn.querySelector('.library-picker-item-title').textContent = item.title || item.originalFilename;
      btn.querySelector('.library-picker-item-meta').textContent =
        (item.originalFilename || '') + ' · ' + formatSize(item.sizeBytes) +
        (item.kind ? ' · ' + item.kind : '');
      btn.addEventListener('click', function () {
        var cb = state.onSelect;
        close();
        if (typeof cb === 'function') cb(item);
      });
      body.appendChild(btn);
    });
  }

  function load() {
    var body = el('libraryPickerBody');
    body.innerHTML = '<div class="library-picker-loading">Đang tải…</div>';
    var url = API + '?page=' + encodeURIComponent(state.page) +
      '&q=' + encodeURIComponent(state.q || '') +
      '&kind=' + encodeURIComponent(state.kind || '') +
      '&size=12';
    fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
      .then(function (res) {
        if (!res.ok) throw new Error('load failed');
        return res.json();
      })
      .then(render)
      .catch(function () {
        body.innerHTML = '<div class="library-picker-error">Không tải được kho học liệu.</div>';
        toast('error', 'Không tải được kho học liệu');
      });
  }

  function open(opts) {
    opts = opts || {};
    ensureModal();
    state.kind = opts.kind || '';
    state.page = 0;
    state.q = '';
    state.onSelect = opts.onSelect || null;
    el('libraryPickerQ').value = '';
    el('libraryPickerModal').hidden = false;
    el('libraryPickerQ').focus();
    load();
  }

  window.UlpLibraryPicker = { open: open, close: close };
})();
