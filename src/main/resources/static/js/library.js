/* ULP — Lecturer library page (2-column kinds + content).
 * Flash → toast, hidden upload form, row rename/delete, thumb decorate.
 * Requires app.js (UlpToast + dropdown toggle).
 */
(function () {
  'use strict';

  function toast(kind, message) {
    if (window.UlpToast && typeof window.UlpToast[kind] === 'function') {
      window.UlpToast[kind](message);
    }
  }

  function drainFlash() {
    var el = document.getElementById('flash-data');
    if (!el) return;
    var ok = el.getAttribute('data-flash-success');
    var err = el.getAttribute('data-flash-error');
    if (ok) toast('success', ok);
    if (err) toast('error', err);
  }

  function bindUpload() {
    var form = document.getElementById('libraryUploadForm');
    var input = document.getElementById('libraryUploadInput');
    var kindHidden = document.getElementById('libraryUploadKind');
    var kindLabel = document.getElementById('uploadKindLabel');
    if (!form || !input) return;

    function openPicker() {
      input.click();
    }

    var btn = document.getElementById('libraryUploadBtn');
    var btnEmpty = document.getElementById('libraryUploadBtnEmpty');
    if (btn) btn.addEventListener('click', openPicker);
    if (btnEmpty) btnEmpty.addEventListener('click', openPicker);

    // Prefer current sidebar kind as default upload kind when DOCUMENT/VIDEO.
    var activeKind = document.querySelector('.kind-item.active .kind-item-link');
    if (activeKind && kindHidden) {
      try {
        var href = activeKind.getAttribute('href') || '';
        if (href.indexOf('kind=DOCUMENT') >= 0) {
          kindHidden.value = 'DOCUMENT';
          if (kindLabel) kindLabel.textContent = 'Tài liệu';
        } else if (href.indexOf('kind=VIDEO') >= 0) {
          kindHidden.value = 'VIDEO';
          if (kindLabel) kindLabel.textContent = 'Video MP4';
        }
      } catch (e) { /* ignore */ }
    }

    document.querySelectorAll('[data-upload-kind]').forEach(function (item) {
      item.addEventListener('click', function () {
        var kind = item.getAttribute('data-upload-kind') || '';
        var label = item.getAttribute('data-upload-label') || 'Tự nhận diện';
        if (kindHidden) kindHidden.value = kind;
        if (kindLabel) kindLabel.textContent = label;
        var dd = document.getElementById('uploadKindDd');
        if (dd) dd.classList.remove('open');
      });
    });

    input.addEventListener('change', function () {
      if (!input.files || !input.files.length) return;
      if (btn) btn.disabled = true;
      if (btnEmpty) btnEmpty.disabled = true;
      form.submit();
    });
  }

  function bindSearchClear() {
    var input = document.getElementById('librarySearchInput');
    var clearBtn = document.getElementById('librarySearchClear');
    var form = document.querySelector('form.content-search');
    if (!input || !clearBtn || !form) return;

    function syncClear() {
      clearBtn.hidden = !(input.value && input.value.trim());
    }

    input.addEventListener('input', syncClear);
    clearBtn.addEventListener('click', function () {
      input.value = '';
      form.submit();
    });
    syncClear();
  }

  function bindRowActions() {
    document.querySelectorAll('[data-action="rename-asset"]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var id = btn.getAttribute('data-asset-id');
        var current = btn.getAttribute('data-asset-title') || '';
        if (!id) return;
        var next = window.prompt('Tên hiển thị mới (tệp gốc không đổi):', current);
        if (next == null) return;
        next = String(next).trim();
        if (!next) {
          toast('error', 'Tên hiển thị không được để trống');
          return;
        }
        var form = document.getElementById('rename-form-' + id);
        var hidden = document.getElementById('rename-title-' + id);
        if (!form || !hidden) return;
        hidden.value = next;
        form.submit();
      });
    });

    document.querySelectorAll('[data-action="delete-asset"]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var id = btn.getAttribute('data-asset-id');
        var title = btn.getAttribute('data-asset-title') || 'học liệu này';
        if (!id) return;
        if (!window.confirm('Xóa "' + title + '" khỏi kho?\nChỉ xóa được khi không còn bài giảng nào đang dùng.')) {
          return;
        }
        var form = document.getElementById('delete-form-' + id);
        if (form) form.submit();
      });
    });
  }

  function decorateThumbs() {
    document.querySelectorAll('.asset-thumb[data-ext]').forEach(function (el) {
      var name = (el.getAttribute('data-ext') || '').toLowerCase();
      var ext = name.indexOf('.') >= 0 ? name.split('.').pop() : '';
      var map = {
        pdf: 'thumb-pdf',
        doc: 'thumb-doc', docx: 'thumb-doc',
        ppt: 'thumb-ppt', pptx: 'thumb-ppt',
        xls: 'thumb-xls', xlsx: 'thumb-xls',
        zip: 'thumb-zip',
        mp4: 'thumb-video'
      };
      var cls = map[ext] || 'thumb-doc';
      el.classList.remove('thumb-doc', 'thumb-pdf', 'thumb-ppt', 'thumb-xls', 'thumb-zip', 'thumb-video');
      el.classList.add(cls);
      if (ext && ext !== 'mp4') {
        el.textContent = ext.toUpperCase();
      } else if (ext === 'mp4') {
        el.textContent = 'MP4';
      }
    });
  }

  function ready(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn);
    } else {
      fn();
    }
  }

  ready(function () {
    drainFlash();
    bindUpload();
    bindSearchClear();
    bindRowActions();
    decorateThumbs();
  });
})();
