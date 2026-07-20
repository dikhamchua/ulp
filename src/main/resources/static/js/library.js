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

  // Flash toasts are drained once by notifications.js (loaded app-wide from
  // app-header). Do not drain here — a second pass caused duplicate toasts.

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
    // Template already seeds hidden inputs from libraryKind; re-sync labels.
    if (kindHidden && kindHidden.value === 'DOCUMENT') {
      if (kindLabel) kindLabel.textContent = 'Tài liệu';
    } else if (kindHidden && kindHidden.value === 'VIDEO') {
      if (kindLabel) kindLabel.textContent = 'Video MP4';
      // Video rail: only accept MP4 in the file picker.
      if (input) input.setAttribute('accept', 'video/mp4,.mp4');
    }

    document.querySelectorAll('[data-upload-kind]').forEach(function (item) {
      item.addEventListener('click', function () {
        var kind = item.getAttribute('data-upload-kind') || '';
        var label = item.getAttribute('data-upload-label') || 'Tự nhận diện';
        if (kindHidden) kindHidden.value = kind;
        if (kindLabel) kindLabel.textContent = label;
        if (input) {
          input.setAttribute(
            'accept',
            kind === 'VIDEO' ? 'video/mp4,.mp4' : '.pdf,.docx,.pptx,.xlsx,.zip,video/mp4,.mp4'
          );
        }
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
    var renameDlg = document.getElementById('libraryRenameDialog');
    var renameInput = document.getElementById('libraryRenameInput');
    var renameForm = document.getElementById('libraryRenameDialogForm');
    var renameCancel = document.getElementById('libraryRenameCancel');
    var pendingRenameId = null;

    function closeRenameDialog() {
      pendingRenameId = null;
      if (!renameDlg) return;
      if (typeof renameDlg.close === 'function' && renameDlg.open) {
        renameDlg.close();
      } else {
        renameDlg.removeAttribute('open');
      }
    }

    function openRenameDialog(id, currentTitle) {
      if (!renameDlg || !renameInput) return;
      pendingRenameId = id;
      renameInput.value = currentTitle || '';
      if (typeof renameDlg.showModal === 'function') {
        renameDlg.showModal();
      } else {
        renameDlg.setAttribute('open', '');
      }
      setTimeout(function () {
        renameInput.focus();
        renameInput.select();
      }, 30);
    }

    function submitRename() {
      if (!pendingRenameId || !renameInput) return;
      var next = String(renameInput.value || '').trim();
      if (!next) {
        toast('error', 'Tên hiển thị không được để trống');
        renameInput.focus();
        return;
      }
      var form = document.getElementById('rename-form-' + pendingRenameId);
      var hidden = document.getElementById('rename-title-' + pendingRenameId);
      if (!form || !hidden) return;
      hidden.value = next;
      // Close first so the native dialog does not block navigation.
      closeRenameDialog();
      form.submit();
    }

    document.querySelectorAll('[data-action="rename-asset"]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var id = btn.getAttribute('data-asset-id');
        var current = btn.getAttribute('data-asset-title') || '';
        if (!id) return;
        openRenameDialog(id, current);
      });
    });

    if (renameForm) {
      renameForm.addEventListener('submit', function (e) {
        // Prevent dialog default close-without-action; we submit the real form.
        e.preventDefault();
        submitRename();
      });
    }
    if (renameCancel) {
      renameCancel.addEventListener('click', function () {
        closeRenameDialog();
      });
    }
    if (renameDlg) {
      renameDlg.addEventListener('cancel', function () {
        pendingRenameId = null;
      });
    }

    document.querySelectorAll('[data-action="delete-asset"]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var id = btn.getAttribute('data-asset-id');
        var title = btn.getAttribute('data-asset-title') || 'học liệu này';
        if (!id) return;
        var form = document.getElementById('delete-form-' + id);
        if (!form) return;

        function doDelete() {
          form.submit();
        }

        if (window.UlpModal && typeof window.UlpModal.confirm === 'function') {
          window.UlpModal.confirm({
            title: 'Xóa khỏi kho?',
            body: 'Xóa "' + title + '" khỏi kho? Chỉ xóa được khi không còn bài giảng nào đang dùng.',
            confirmLabel: 'Xóa',
            onConfirm: doDelete
          });
        } else if (window.confirm('Xóa "' + title + '" khỏi kho?\nChỉ xóa được khi không còn bài giảng nào đang dùng.')) {
          doDelete();
        }
      });
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
    bindUpload();
    bindSearchClear();
    bindRowActions();
  });
})();
